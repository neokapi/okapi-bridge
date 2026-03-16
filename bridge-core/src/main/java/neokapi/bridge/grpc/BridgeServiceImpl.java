package neokapi.bridge.grpc;

import neokapi.bridge.EventConverter;
import neokapi.bridge.io.ContentResolver;
import neokapi.bridge.io.OutputWriter;
import neokapi.bridge.model.*;
import neokapi.bridge.proto.*;
import neokapi.bridge.util.FilterRegistry;
import neokapi.bridge.util.ParameterApplier;
import neokapi.bridge.util.ParameterFlattener;
import neokapi.bridge.util.SchemaValidator;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.protobuf.ByteString;
import io.grpc.stub.StreamObserver;
import net.sf.okapi.common.Event;
import net.sf.okapi.common.IParameters;
import net.sf.okapi.common.LocaleId;
import net.sf.okapi.common.filters.IFilter;
import net.sf.okapi.common.filterwriter.IFilterWriter;
import net.sf.okapi.common.resource.RawDocument;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * gRPC implementation of the BridgeService.
 * Implements the single bidirectional-streaming Process RPC with a batched
 * single-pass design:
 * <ul>
 *   <li>One filter read pass: events are streamed to Go as encountered, but
 *       writing is deferred until a batch of subscribed (Block) events
 *       accumulates. By the time the batch is flushed to the writer, Go has
 *       had time to process and return translations for the earlier blocks.</li>
 *   <li>The translation queue acts as a buffer between send and write within
 *       each batch, amortizing gRPC round-trip latency across BATCH_SIZE
 *       blocks.</li>
 * </ul>
 */
public class BridgeServiceImpl extends BridgeServiceGrpc.BridgeServiceImplBase {

    private final ContentResolver contentResolver;
    private final OutputWriter outputWriter;
    private final CountDownLatch shutdownLatch = new CountDownLatch(1);
    private final ExecutorService filterPool;
    private final long stuckTimeoutSeconds;
    private final Map<String, ParameterFlattener> flattenerCache = new ConcurrentHashMap<>();
    private final Map<String, JsonObject> schemaCache = new ConcurrentHashMap<>();

    // Idle timeout tracking.
    private final AtomicInteger activeStreams = new AtomicInteger(0);
    private final AtomicLong lastActivityNanos = new AtomicLong(System.nanoTime());
    private final long idleTimeoutNanos;
    private ScheduledExecutorService idleTimer;

    public BridgeServiceImpl(ContentResolver contentResolver, OutputWriter outputWriter,
                             int concurrency, long idleTimeoutSeconds, long stuckTimeoutSeconds) {
        this.contentResolver = contentResolver;
        this.outputWriter = outputWriter;
        this.stuckTimeoutSeconds = stuckTimeoutSeconds;
        this.filterPool = new ThreadPoolExecutor(
                concurrency, concurrency, 60, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(),
                r -> { Thread t = new Thread(r, "filter-pool"); t.setDaemon(true); return t; }
        );
        this.idleTimeoutNanos = TimeUnit.SECONDS.toNanos(idleTimeoutSeconds);

        if (idleTimeoutNanos > 0) {
            long checkInterval = Math.max(idleTimeoutSeconds / 2, 1);
            idleTimer = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "idle-timer");
                t.setDaemon(true);
                return t;
            });
            idleTimer.scheduleAtFixedRate(this::checkIdle,
                    checkInterval, checkInterval, TimeUnit.SECONDS);
        }
    }

    public BridgeServiceImpl(ContentResolver contentResolver, OutputWriter outputWriter) {
        this(contentResolver, outputWriter,
                Runtime.getRuntime().availableProcessors(), 0, 120);
    }

    public BridgeServiceImpl(ContentResolver contentResolver, OutputWriter outputWriter,
                             long idleTimeoutSeconds) {
        this(contentResolver, outputWriter,
                Runtime.getRuntime().availableProcessors(), idleTimeoutSeconds, 120);
    }

    private void checkIdle() {
        if (activeStreams.get() > 0) return;
        long elapsed = System.nanoTime() - lastActivityNanos.get();
        if (elapsed >= idleTimeoutNanos) {
            System.err.println("[bridge] Idle timeout (" + TimeUnit.NANOSECONDS.toSeconds(idleTimeoutNanos) + "s), shutting down");
            shutdownLatch.countDown();
        }
    }

    private void streamStarted() {
        activeStreams.incrementAndGet();
        lastActivityNanos.set(System.nanoTime());
    }

    private void streamEnded() {
        activeStreams.decrementAndGet();
        lastActivityNanos.set(System.nanoTime());
    }

    /**
     * Block until the Shutdown RPC is called or idle timeout triggers.
     */
    public void awaitShutdown() throws InterruptedException {
        shutdownLatch.await();
    }

    // ── Process RPC ────────────────────────────────────────────────────────────

    @Override
    public StreamObserver<ProcessRequest> process(StreamObserver<ProcessResponse> responseObserver) {
        streamStarted();

        return new StreamObserver<ProcessRequest>() {
            private ProcessHeader header;
            private final BlockingQueue<TranslationEntry> translationQueue = new LinkedBlockingQueue<>();

            @Override
            public void onNext(ProcessRequest request) {
                switch (request.getRequestCase()) {
                    case HEADER:
                        header = request.getHeader();
                        filterPool.submit(() -> runPipeline(header, translationQueue, responseObserver));
                        break;

                    case PART:
                        handleProcessedPart(request.getPart());
                        break;

                    case PART_BATCH:
                        for (PartMessage pm : request.getPartBatch().getPartsList()) {
                            handleProcessedPart(pm);
                        }
                        break;

                    default:
                        break;
                }
            }

            @Override
            public void onCompleted() {
                translationQueue.offer(TranslationEntry.END);
            }

            @Override
            public void onError(Throwable t) {
                System.err.println("[bridge] Process stream error: " + t.getMessage());
                translationQueue.clear();
                translationQueue.offer(TranslationEntry.END);
            }

            private void handleProcessedPart(PartMessage pm) {
                if (pm.getPartType() == PartDTO.TYPE_BLOCK && pm.hasBlock()) {
                    BlockMessage blockMsg = pm.getBlock();
                    String locale = header.getOutputLocale().isEmpty()
                            ? header.getTargetLocale() : header.getOutputLocale();
                    List<FragmentDTO> fragments = extractTargetFragments(blockMsg, locale);
                    try {
                        translationQueue.offer(
                                new TranslationEntry(blockMsg.getId(), fragments),
                                stuckTimeoutSeconds, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        };
    }

    /** Number of parts to accumulate before sending a PartBatch message to Go. */
    private static final int SEND_BATCH_SIZE = 64;

    /** Capacity of the event queue between the reader and writer threads. */
    private static final int EVENT_QUEUE_CAPACITY = 256;

    /** Sentinel event marking the end of the event stream. */
    private static final Event END_OF_EVENTS = new Event(net.sf.okapi.common.EventType.NO_OP);

    /**
     * Two-thread single-pass pipeline — one filter read, no double I/O.
     *
     * <p>Thread 1 (reader, this thread): reads events from the filter, sends
     * subscribed parts to Go via gRPC, and enqueues events into an event queue.
     *
     * <p>Thread 2 (writer): dequeues events, applies translations from the
     * translation queue, and writes to the filter writer.
     *
     * <p>The two threads are decoupled: the reader never blocks on translations
     * and the writer never blocks on gRPC sends. The bounded event queue provides
     * back-pressure so the reader doesn't outrun the writer by too much.
     */
    private void runPipeline(ProcessHeader header,
                             BlockingQueue<TranslationEntry> translationQueue,
                             StreamObserver<ProcessResponse> respObserver) {
        IFilter filter = null;
        IFilterWriter writer = null;
        try {
            File inputFile = resolveProcessContent(header);
            String filterClass = header.getFilterClass();
            String sourceLocale = header.getSourceLocale();
            String targetLocale = header.getTargetLocale();
            String encoding = header.getEncoding().isEmpty() ? "UTF-8" : header.getEncoding();
            String outputLocale = header.getOutputLocale().isEmpty() ? targetLocale : header.getOutputLocale();
            boolean writeEnabled = header.hasOutput() || !header.getOutputLocale().isEmpty();

            // Build subscription filter.
            Set<Integer> subscribedTypes = new HashSet<>();
            for (int pt : header.getSubscribePartsList()) {
                subscribedTypes.add(pt);
            }
            boolean sendAll = subscribedTypes.isEmpty();

            filter = FilterRegistry.createFilter(filterClass);
            if (filter == null) {
                sendComplete(respObserver, "cannot instantiate filter: " + filterClass);
                return;
            }

            Map<String, String> filterParams = header.getFilterParamsMap();
            if (filterParams != null && !filterParams.isEmpty()) {
                applyFilterParams(filter, filterParams);
            }
            setupFilterConfigurationMapper(filter);

            LocaleId srcLocale = LocaleId.fromString(sourceLocale);
            LocaleId tgtLocale = LocaleId.fromString(targetLocale);
            RawDocument rawDoc = new RawDocument(inputFile.toURI(), encoding, srcLocale, tgtLocale);
            filter.open(rawDoc);

            System.err.println("[bridge] Opened filter " + filter.getClass().getName()
                    + " for " + inputFile.getName());

            // Create writer BEFORE iterating (same filter instance — single read).
            String outputPath = null;
            ByteArrayOutputStream outputStream = null;
            if (writeEnabled) {
                writer = filter.createFilterWriter();
                if (writer == null) {
                    throw new IllegalStateException("filter does not support writing: " + filterClass);
                }
                outputPath = resolveProcessOutputPath(header);
                writer.setOptions(LocaleId.fromString(outputLocale), encoding);
                if (outputPath != null) {
                    writer.setOutput(outputPath);
                } else {
                    outputStream = new ByteArrayOutputStream();
                    writer.setOutput(outputStream);
                }
            }

            // ── Start writer thread ──
            BlockingQueue<Event> eventQueue = writeEnabled
                    ? new ArrayBlockingQueue<>(EVENT_QUEUE_CAPACITY) : null;
            Future<Void> writerFuture = null;
            if (writeEnabled) {
                IFilterWriter writerRef = writer;
                StreamingTranslationApplier applier = new StreamingTranslationApplier(
                        translationQueue, LocaleId.fromString(outputLocale), stuckTimeoutSeconds);
                writerFuture = filterPool.submit(() -> {
                    Event ev;
                    while ((ev = eventQueue.poll(stuckTimeoutSeconds, TimeUnit.SECONDS)) != END_OF_EVENTS) {
                        if (ev == null) {
                            throw new RuntimeException("Writer thread timed out waiting for events");
                        }
                        Event modified = applier.applyTranslations(ev);
                        writerRef.handleEvent(modified);
                    }
                    return null;
                });
            }

            // ── Reader thread (this thread): read events, send parts, enqueue ──
            List<PartMessage> sendBatch = new ArrayList<>(SEND_BATCH_SIZE);
            int totalSent = 0;

            while (filter.hasNext()) {
                Event event = filter.next();

                // Enqueue for writer thread.
                if (eventQueue != null) {
                    eventQueue.put(event);
                }

                // Send subscribed parts to Go.
                PartDTO partDTO = EventConverter.convert(event);
                if (partDTO != null) {
                    boolean subscribed = sendAll || subscribedTypes.contains(partDTO.getPartType());
                    if (subscribed) {
                        sendBatch.add(ProtoAdapter.toProto(partDTO));
                        totalSent++;
                        if (sendBatch.size() >= SEND_BATCH_SIZE) {
                            respObserver.onNext(ProcessResponse.newBuilder()
                                    .setPartBatch(PartBatch.newBuilder()
                                            .addAllParts(sendBatch).build())
                                    .build());
                            sendBatch.clear();
                        }
                    }
                }
            }

            // Flush remaining send batch.
            if (!sendBatch.isEmpty()) {
                respObserver.onNext(ProcessResponse.newBuilder()
                        .setPartBatch(PartBatch.newBuilder()
                                .addAllParts(sendBatch).build())
                        .build());
            }

            // Signal end of events to writer thread.
            if (eventQueue != null) {
                eventQueue.put(END_OF_EVENTS);
            }

            // Wait for writer thread to finish.
            if (writerFuture != null) {
                writerFuture.get();
            }

            // Close writer then filter.
            if (writer != null) { writer.close(); writer = null; }
            filter.close(); filter = null;

            // Signal read done.
            respObserver.onNext(ProcessResponse.newBuilder()
                    .setReadDone(ProcessReadDone.newBuilder().build()).build());

            System.err.println("[bridge] Pipeline complete: " + totalSent + " parts (single-pass)");

            // Build and send Complete.
            ProcessComplete.Builder complete = ProcessComplete.newBuilder();
            if (writeEnabled) {
                if (outputPath != null) {
                    complete.setOutputPath(outputPath);
                } else if (outputStream != null) {
                    complete.setOutput(ByteString.copyFrom(outputStream.toByteArray()));
                }
            }
            sendCompleteMessage(respObserver, complete.build());

        } catch (Exception e) {
            Throwable cause = e instanceof ExecutionException ? e.getCause() : e;
            String errMsg = cause.getMessage();
            if (errMsg == null || errMsg.isEmpty()) errMsg = cause.getClass().getSimpleName();
            System.err.println("[bridge] Process error: " + errMsg);
            cause.printStackTrace(System.err);
            if (writer != null) {
                try { writer.close(); } catch (Exception ignored) {}
            }
            if (filter != null) {
                try { filter.close(); } catch (Exception ignored) {}
            }
            sendComplete(respObserver, errMsg);
        }
    }

    /**
     * Two-pass write phase (legacy fallback, not called from runPipeline).
     * Opens its own filter instance and re-reads the document, applying
     * translations from the queue. Kept as a reference for cases where the
     * batched single-pass approach cannot be used (e.g., filters that don't
     * support createFilterWriter() before iteration completes).
     * Returns the ProcessComplete message (caller is responsible for sending it).
     */
    private ProcessComplete performWritePhase(ProcessHeader header,
                                               BlockingQueue<TranslationEntry> translationQueue) throws Exception {
        String outputLocale = header.getOutputLocale().isEmpty()
                ? header.getTargetLocale() : header.getOutputLocale();
        String sourceLocale = header.getSourceLocale();
        String encoding = header.getEncoding().isEmpty() ? "UTF-8" : header.getEncoding();

        File inputFile = resolveProcessContent(header);

        IFilter writeFilter = FilterRegistry.createFilter(header.getFilterClass());
        if (writeFilter == null) {
            throw new IllegalStateException("cannot instantiate filter for write: " + header.getFilterClass());
        }

        Map<String, String> filterParams = header.getFilterParamsMap();
        if (filterParams != null && !filterParams.isEmpty()) {
            applyFilterParams(writeFilter, filterParams);
        }
        setupFilterConfigurationMapper(writeFilter);

        IFilterWriter writer = writeFilter.createFilterWriter();
        if (writer == null) {
            throw new IllegalStateException("filter does not support writing: " + header.getFilterClass());
        }

        String outputPath = resolveProcessOutputPath(header);
        ByteArrayOutputStream outputStream = null;
        writer.setOptions(LocaleId.fromString(outputLocale), encoding);
        if (outputPath != null) {
            writer.setOutput(outputPath);
        } else {
            outputStream = new ByteArrayOutputStream();
            writer.setOutput(outputStream);
        }

        LocaleId srcLocale = LocaleId.fromString(sourceLocale);
        LocaleId tgtLocale = LocaleId.fromString(outputLocale);
        RawDocument rawDoc = new RawDocument(inputFile.toURI(), encoding, srcLocale, tgtLocale);
        writeFilter.open(rawDoc);

        StreamingTranslationApplier applier =
                new StreamingTranslationApplier(translationQueue, LocaleId.fromString(outputLocale), stuckTimeoutSeconds);

        while (writeFilter.hasNext()) {
            Event event = writeFilter.next();
            Event modified = applier.applyTranslations(event);
            writer.handleEvent(modified);
        }

        writeFilter.close();
        writer.close();

        ProcessComplete.Builder complete = ProcessComplete.newBuilder();
        if (outputPath != null) {
            complete.setOutputPath(outputPath);
        } else if (outputStream != null) {
            complete.setOutput(ByteString.copyFrom(outputStream.toByteArray()));
        }

        System.err.println("[bridge] Process write complete");
        return complete.build();
    }

    /**
     * Send a ProcessComplete with an optional error and close the response stream.
     */
    private void sendComplete(StreamObserver<ProcessResponse> respObserver, String error) {
        ProcessComplete.Builder complete = ProcessComplete.newBuilder();
        if (error != null) {
            complete.setError(error);
        }
        sendCompleteMessage(respObserver, complete.build());
    }

    /**
     * Send a ProcessComplete message and close the response stream.
     * Synchronized on the respObserver to avoid concurrent onNext/onCompleted calls.
     */
    private void sendCompleteMessage(StreamObserver<ProcessResponse> respObserver,
                                      ProcessComplete complete) {
        synchronized (respObserver) {
            respObserver.onNext(ProcessResponse.newBuilder()
                    .setComplete(complete)
                    .build());
            respObserver.onCompleted();
        }
        streamEnded();
    }

    // ── Shutdown RPC ───────────────────────────────────────────────────────────

    @Override
    public void shutdown(ShutdownRequest request, StreamObserver<ShutdownResponse> responseObserver) {
        System.err.println("[bridge] Shutdown requested");
        filterPool.shutdownNow();
        responseObserver.onNext(ShutdownResponse.newBuilder().build());
        responseObserver.onCompleted();
        shutdownLatch.countDown();
    }

    // ── Content resolution helpers ─────────────────────────────────────────────

    /**
     * Resolve content for a Process request from the ContentRef in the header.
     */
    private File resolveProcessContent(ProcessHeader header) throws IOException {
        if (!header.hasInput()) {
            throw new IOException("Process requires input in header");
        }

        ContentRef ref = header.getInput();
        String extensionHint = "";

        switch (ref.getLocationCase()) {
            case PATH:
                return contentResolver.resolvePath(ref.getPath());
            case URI:
                return contentResolver.resolveUri(ref.getUri(), extensionHint);
            case INLINE:
                return contentResolver.resolveInline(ref.getInline().toByteArray(), extensionHint);
            default:
                throw new IOException("Process requires content_ref with path, uri, or inline data");
        }
    }

    /**
     * Resolve the output file path from the Process header's output ref.
     * Returns null if no output is set (output should be returned inline as bytes).
     */
    private String resolveProcessOutputPath(ProcessHeader header) throws IOException {
        if (!header.hasOutput()) return null;
        OutputRef ref = header.getOutput();
        switch (ref.getDestinationCase()) {
            case PATH:
                File parent = new File(ref.getPath()).getParentFile();
                if (parent != null) {
                    Files.createDirectories(parent.toPath());
                }
                return ref.getPath();
            case URI:
                return outputWriter.resolveUri(ref.getUri());
            default:
                return null;
        }
    }

    // ── Target extraction ──────────────────────────────────────────────────────

    /**
     * Extract target fragments for the given locale directly from a proto BlockMessage.
     * Works on proto messages without converting to DTOs, avoiding intermediate allocations.
     */
    private static List<FragmentDTO> extractTargetFragments(BlockMessage block, String locale) {
        for (TargetEntry target : block.getTargetsList()) {
            if (target.getLocale().equals(locale)) {
                List<FragmentDTO> fragments = new ArrayList<>(target.getSegmentsCount());
                for (SegmentMessage seg : target.getSegmentsList()) {
                    if (seg.hasContent()) {
                        fragments.add(ProtoAdapter.fromProto(seg.getContent()));
                    }
                }
                return fragments;
            }
        }
        return null;
    }

    // ── Filter parameter helpers ───────────────────────────────────────────────

    /** Reserved param keys handled specially by the bridge. */
    private static final java.util.Set<String> RESERVED_PARAMS = new java.util.HashSet<>(
            java.util.Arrays.asList("configFile", "fprmContent", "apiVersion", "kind", "spec"));

    /**
     * Apply filter parameters from the gRPC map&lt;string, string&gt; format.
     * Values that look like JSON are parsed back to JsonElements for
     * the ParameterApplier, which expects a JsonObject.
     *
     * <p>Special handling:
     * <ul>
     *   <li>{@code configFile} — load native Okapi config from a file path (.fprm, YAML, etc.)</li>
     *   <li>{@code fprmContent} — load native Okapi config from inline content (same formats)</li>
     * </ul>
     *
     * <p>When a schema is available for the filter, incoming JSON config is
     * validated against it (warnings logged) and hierarchical params are
     * flattened to flat Okapi names via {@link ParameterFlattener}.
     */
    private void applyFilterParams(IFilter filter, Map<String, String> originalParams) {
        // Make a mutable copy — proto maps are unmodifiable and envelope
        // unwrapping may replace the map entirely.
        Map<String, String> params = new HashMap<>(originalParams);
        IParameters filterParameters = filter.getParameters();
        if (filterParameters == null) {
            System.err.println("[bridge] Warning: Filter does not support parameters");
            return;
        }

        // Handle enveloped config: if kind is an Okf*FilterConfig, validate and unwrap.
        // The envelope format is: apiVersion (vN), kind (Okf{Format}FilterConfig),
        // spec (JSON-encoded params).
        String kind = params.get("kind");
        if (kind != null && !kind.isEmpty()) {
            // Validate kind format
            if (!kind.startsWith("Okf") || !kind.endsWith("FilterConfig")) {
                System.err.println("[bridge] Warning: unexpected kind '" + kind +
                        "' (expected Okf{Format}FilterConfig), proceeding anyway");
            }

            // If spec is present, use it as the source of filter parameters
            String specJson = params.get("spec");
            if (specJson != null && !specJson.isEmpty()) {
                try {
                    JsonElement specElement = JsonParser.parseString(specJson);
                    if (specElement.isJsonObject()) {
                        JsonObject specParams = specElement.getAsJsonObject();
                        String apiVersion = params.get("apiVersion");
                        // Replace params with the unwrapped spec (pass to the flattener + applier below)
                        params = new HashMap<>();
                        for (Map.Entry<String, JsonElement> entry : specParams.entrySet()) {
                            params.put(entry.getKey(), entry.getValue().toString());
                        }
                        System.err.println("[bridge] Unwrapped envelope config (kind=" + kind +
                                ", apiVersion=" + (apiVersion != null ? apiVersion : "unset") +
                                "), " + specParams.size() + " spec params");
                    }
                } catch (Exception e) {
                    System.err.println("[bridge] Warning: Could not parse spec from envelope: " + e.getMessage());
                }
            }
        }

        // Handle configFile: load the entire configuration from a file.
        String configFilePath = params.get("configFile");
        if (configFilePath != null && !configFilePath.isEmpty()) {
            loadConfigFromFile(filterParameters, configFilePath);
        }

        // Handle fprmContent: load native Okapi config from inline content.
        // This allows sending .fprm or YAML config inline without writing to disk.
        String fprmContent = params.get("fprmContent");
        if (fprmContent != null && !fprmContent.isEmpty()) {
            try {
                filterParameters.fromString(fprmContent);
                System.err.println("[bridge] Loaded config from inline fprmContent");
            } catch (Exception e) {
                System.err.println("[bridge] Warning: Could not parse fprmContent: " + e.getMessage());
            }
        }

        // Convert remaining params (excluding reserved keys) to JsonObject.
        // The Go side JSON-encodes non-string values (booleans, numbers, objects).
        JsonObject jsonParams = new JsonObject();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (RESERVED_PARAMS.contains(entry.getKey())) {
                continue;
            }
            String value = entry.getValue();
            try {
                JsonElement parsed = JsonParser.parseString(value);
                jsonParams.add(entry.getKey(), parsed);
            } catch (Exception e) {
                // Not valid JSON — use as plain string.
                jsonParams.addProperty(entry.getKey(), value);
            }
        }

        if (jsonParams.size() > 0) {
            // Load schema for validation and flattening.
            JsonObject schema = loadSchema(
                    FilterRegistry.getFilterId(filter.getClass().getName()));

            // Validate against schema (warnings only — don't reject config).
            if (schema != null) {
                SchemaValidator validator = new SchemaValidator(schema);
                SchemaValidator.ValidationResult result = validator.validate(jsonParams);
                if (!result.getErrors().isEmpty()) {
                    System.err.println("[bridge] Config validation errors: " +
                            String.join("; ", result.getErrors()));
                }
                if (!result.getWarnings().isEmpty()) {
                    System.err.println("[bridge] Config validation warnings: " +
                            String.join("; ", result.getWarnings()));
                }
            }

            // Flatten hierarchical params if a schema with x-flattenPath is available.
            // This is backwards-compatible: flat input passes through unchanged.
            ParameterFlattener flattener = getFlattener(filter.getClass().getName());
            if (flattener != null) {
                jsonParams = flattener.flatten(jsonParams);
            }

            boolean success = ParameterApplier.applyParameters(filterParameters, jsonParams);
            if (success) {
                System.err.println("[bridge] Applied " + jsonParams.size() + " additional filter parameters");
            } else {
                System.err.println("[bridge] Warning: Some filter parameters could not be applied");
            }
        }

        System.err.println("[bridge] Applied " + params.size() + " filter parameters");
    }

    /**
     * Load native Okapi configuration from a file path (.fprm, YAML, etc.).
     * Prefers load(URL) over fromString() for correct #v1 header handling.
     */
    private void loadConfigFromFile(IParameters filterParameters, String configFilePath) {
        try {
            java.net.URL configUrl = new File(configFilePath).toURI().toURL();
            filterParameters.load(configUrl, false);
            System.err.println("[bridge] Loaded config from file: " + configFilePath);
        } catch (Exception e) {
            try {
                String configContent = new String(Files.readAllBytes(
                        new File(configFilePath).toPath()), java.nio.charset.StandardCharsets.UTF_8);
                filterParameters.fromString(configContent);
                System.err.println("[bridge] Loaded config from file via fromString: " + configFilePath);
            } catch (Exception e2) {
                System.err.println("[bridge] Warning: Could not load config file " +
                        configFilePath + ": " + e2.getMessage());
            }
        }
    }

    // ── FilterConfigurationMapper ──────────────────────────────────────────────

    /**
     * Lazily-initialized FilterConfigurationMapper with all discovered filters
     * registered. Built once and reused across all filter instances so that
     * container/delegating filters (Archive, AutoXLIFF, MultiParsers, SdlPackage,
     * WsxzPackage) can resolve any sub-filter ID (e.g., "okf_xliff", "okf_tmx",
     * "okf_markdown") to the correct filter class.
     */
    private volatile net.sf.okapi.common.filters.FilterConfigurationMapper sharedFcMapper;

    private net.sf.okapi.common.filters.FilterConfigurationMapper getSharedFilterConfigurationMapper() {
        if (sharedFcMapper == null) {
            synchronized (this) {
                if (sharedFcMapper == null) {
                    net.sf.okapi.common.filters.FilterConfigurationMapper mapper =
                            new net.sf.okapi.common.filters.FilterConfigurationMapper();
                    for (String fc : FilterRegistry.scanFilterClassNames()) {
                        try {
                            mapper.addConfigurations(fc);
                        } catch (Exception e) {
                            // Skip filters that fail to register (e.g., missing dependencies)
                        }
                    }
                    sharedFcMapper = mapper;
                }
            }
        }
        return sharedFcMapper;
    }

    /**
     * Set up a FilterConfigurationMapper on the filter so that sub-filtering works.
     * Uses a shared mapper with all discovered filters registered, enabling
     * container filters to delegate to any sub-filter available on the classpath.
     */
    private void setupFilterConfigurationMapper(IFilter filter) {
        try {
            filter.setFilterConfigurationMapper(getSharedFilterConfigurationMapper());
        } catch (Exception e) {
            // Non-fatal: sub-filtering won't work but basic filtering will.
            System.err.println("[bridge] Warning: Could not set up FilterConfigurationMapper: " + e.getMessage());
        }
    }

    // ── Schema / Flattener caches ──────────────────────────────────────────────

    /**
     * Get or create a ParameterFlattener for the given filter class.
     * Returns null if no schema is available (flattening will be skipped).
     */
    private ParameterFlattener getFlattener(String filterClass) {
        return flattenerCache.computeIfAbsent(filterClass, fc -> {
            String filterId = FilterRegistry.getFilterId(fc);
            if (filterId == null) return null;
            JsonObject schema = loadSchema(filterId);
            if (schema == null) return null;
            return new ParameterFlattener(schema);
        });
    }

    /**
     * Load a composite JSON Schema from the classpath for the given filter ID.
     * Results are cached for the lifetime of the bridge instance.
     */
    private JsonObject loadSchema(String filterId) {
        if (filterId == null) return null;
        return schemaCache.computeIfAbsent(filterId, id -> {
            String[] paths = {
                "/schemas/" + id + ".schema.json",
                "schemas/" + id + ".schema.json"
            };
            for (String path : paths) {
                try (InputStream is = getClass().getResourceAsStream(path)) {
                    if (is != null) {
                        try (InputStreamReader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
                            return JsonParser.parseReader(reader).getAsJsonObject();
                        }
                    }
                } catch (Exception e) {
                    System.err.println("[bridge] Could not load schema for " + id + ": " + e.getMessage());
                }
            }
            return null;
        });
    }

    private static String nullSafe(String s) {
        return s != null ? s : "";
    }
}
