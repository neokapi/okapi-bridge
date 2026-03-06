package com.gokapi.bridge.grpc;

import com.gokapi.bridge.EventConverter;
import com.gokapi.bridge.PartDTOConverter;
import com.gokapi.bridge.io.ContentResolver;
import com.gokapi.bridge.io.OutputWriter;
import com.gokapi.bridge.io.WriteResult;
import com.gokapi.bridge.model.*;
import com.gokapi.bridge.proto.*;
import com.gokapi.bridge.util.FilterRegistry;
import com.gokapi.bridge.util.ParameterApplier;
import com.gokapi.bridge.util.ParameterFlattener;
import com.gokapi.bridge.util.SchemaValidator;
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

/**
 * gRPC implementation of the BridgeService.
 * Delegates to existing EventConverter/PartDTOConverter for Okapi event handling.
 */
public class BridgeServiceImpl extends BridgeServiceGrpc.BridgeServiceImplBase {

    private static final int QUEUE_CAPACITY = 1024;

    private IFilter currentFilter;

    private final ContentResolver contentResolver;
    private final OutputWriter outputWriter;
    private final CountDownLatch shutdownLatch = new CountDownLatch(1);
    private final ExecutorService mergeExecutor = Executors.newCachedThreadPool();
    private final Map<String, ParameterFlattener> flattenerCache = new ConcurrentHashMap<>();
    private final Map<String, JsonObject> schemaCache = new ConcurrentHashMap<>();

    public BridgeServiceImpl(ContentResolver contentResolver, OutputWriter outputWriter) {
        this.contentResolver = contentResolver;
        this.outputWriter = outputWriter;
    }

    /**
     * Block until the Shutdown RPC is called.
     */
    public void awaitShutdown() throws InterruptedException {
        shutdownLatch.await();
    }

    @Override
    public void info(InfoRequest request, StreamObserver<InfoResponse> responseObserver) {
        try {
            FilterInfo info = FilterRegistry.getFilterInfo(request.getFilterClass());
            if (info == null) {
                responseObserver.onError(
                        io.grpc.Status.NOT_FOUND
                                .withDescription("filter class not found: " + request.getFilterClass())
                                .asRuntimeException());
                return;
            }

            InfoResponse.Builder resp = InfoResponse.newBuilder()
                    .setName(nullSafe(info.getName()))
                    .setDisplayName(nullSafe(info.getDisplayName()));
            if (info.getMimeTypes() != null) {
                resp.addAllMimeTypes(info.getMimeTypes());
            }
            if (info.getExtensions() != null) {
                resp.addAllExtensions(info.getExtensions());
            }

            responseObserver.onNext(resp.build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            responseObserver.onError(
                    io.grpc.Status.INTERNAL
                            .withDescription(e.getMessage())
                            .asRuntimeException());
        }
    }

    @Override
    public void listFilters(ListFiltersRequest request, StreamObserver<ListFiltersResponse> responseObserver) {
        try {
            List<FilterInfo> filters = FilterRegistry.listFilters();
            ListFiltersResponse.Builder resp = ListFiltersResponse.newBuilder();

            for (FilterInfo info : filters) {
                FilterEntry.Builder entry = FilterEntry.newBuilder()
                        .setFilterClass(info.getFilterClass())
                        .setName(nullSafe(info.getName()))
                        .setDisplayName(nullSafe(info.getDisplayName()));
                if (info.getMimeTypes() != null) {
                    entry.addAllMimeTypes(info.getMimeTypes());
                }
                if (info.getExtensions() != null) {
                    entry.addAllExtensions(info.getExtensions());
                }
                resp.addFilters(entry);
            }

            responseObserver.onNext(resp.build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            responseObserver.onError(
                    io.grpc.Status.INTERNAL
                            .withDescription(e.getMessage())
                            .asRuntimeException());
        }
    }

    @Override
    public void open(OpenRequest request, StreamObserver<OpenResponse> responseObserver) {
        try {
            String filterClass = request.getFilterClass();
            String uri = request.getUri();
            String sourceLocale = request.getSourceLocale().isEmpty() ? "en" : request.getSourceLocale();
            String targetLocale = request.getTargetLocale().isEmpty() ? "fr" : request.getTargetLocale();
            String encoding = request.getEncoding().isEmpty() ? "UTF-8" : request.getEncoding();

            // Instantiate filter.
            IFilter filter = FilterRegistry.createFilter(filterClass);
            if (filter == null) {
                responseObserver.onNext(OpenResponse.newBuilder()
                        .setError("cannot instantiate filter: " + filterClass)
                        .build());
                responseObserver.onCompleted();
                return;
            }

            // Apply filter parameters.
            Map<String, String> filterParams = request.getFilterParamsMap();
            if (filterParams != null && !filterParams.isEmpty()) {
                applyFilterParams(filter, filterParams);
            }

            // Resolve content to a local file via the content resolver.
            File inputFile = resolveOpenContent(request);

            // Set up FilterConfigurationMapper for sub-filtering support.
            // Filters like RegexFilter with useCodeFinder/subfilter configs need
            // a mapper to resolve sub-filter classes (e.g., okf_html -> HTMLFilter).
            setupFilterConfigurationMapper(filter);

            LocaleId srcLocale = LocaleId.fromString(sourceLocale);
            LocaleId tgtLocale = LocaleId.fromString(targetLocale);
            RawDocument rawDoc = new RawDocument(inputFile.toURI(), encoding, srcLocale, tgtLocale);

            filter.open(rawDoc);
            currentFilter = filter;

            System.err.println("[bridge] Opened filter " + filterClass + " for " + uri);
            responseObserver.onNext(OpenResponse.newBuilder().build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            System.err.println("[bridge] Open error: " + e.getMessage());
            e.printStackTrace(System.err);
            responseObserver.onNext(OpenResponse.newBuilder()
                    .setError(e.getMessage())
                    .build());
            responseObserver.onCompleted();
        }
    }

    @Override
    public void read(ReadRequest request, StreamObserver<PartMessage> responseObserver) {
        try {
            if (currentFilter == null) {
                responseObserver.onError(
                        io.grpc.Status.FAILED_PRECONDITION
                                .withDescription("no filter is currently open")
                                .asRuntimeException());
                return;
            }

            int count = 0;
            while (currentFilter.hasNext()) {
                Event event = currentFilter.next();
                PartDTO partDTO = EventConverter.convert(event);
                if (partDTO != null) {
                    PartMessage protoMsg = ProtoAdapter.toProto(partDTO);
                    responseObserver.onNext(protoMsg);
                    count++;
                }
            }

            System.err.println("[bridge] Streamed " + count + " parts");
            responseObserver.onCompleted();
        } catch (Exception e) {
            System.err.println("[bridge] Read error: " + e.getMessage());
            e.printStackTrace(System.err);
            responseObserver.onError(
                    io.grpc.Status.INTERNAL
                            .withDescription(e.getMessage())
                            .asRuntimeException());
        }
    }

    @Override
    public StreamObserver<WriteChunk> write(StreamObserver<WriteResponse> responseObserver) {
        return new StreamObserver<WriteChunk>() {
            private WriteHeader header;
            private final BlockingQueue<TranslationEntry> queue =
                    new LinkedBlockingQueue<>(QUEUE_CAPACITY);
            private Future<WriteResult> mergeFuture;

            @Override
            public void onNext(WriteChunk chunk) {
                if (chunk.hasHeader()) {
                    header = chunk.getHeader();
                    // Start merge thread immediately — it blocks on queue.poll()
                    // until parts arrive. Filter setup happens while Go is still
                    // streaming parts.
                    mergeFuture = mergeExecutor.submit(() -> {
                        try {
                            return performWriteStreaming(header, queue);
                        } catch (Exception e) {
                            // Drain the queue so onNext's queue.put() doesn't block
                            // the gRPC thread indefinitely.
                            queue.clear();
                            queue.offer(TranslationEntry.END);
                            throw e;
                        }
                    });
                } else if (chunk.hasPart()) {
                    PartMessage msg = chunk.getPart();
                    if (msg.getPartType() == PartDTO.TYPE_BLOCK && msg.hasBlock()) {
                        BlockMessage blockMsg = msg.getBlock();
                        String locale = header != null ? header.getLocale() : "";
                        List<FragmentDTO> fragments = extractTargetFragments(blockMsg, locale);
                        if (fragments != null) {
                            try {
                                // Use offer with timeout instead of blocking put.
                                // If the merge thread failed early, the queue is drained
                                // and the END sentinel is placed. Offer will succeed after
                                // the queue drains, or time out gracefully.
                                if (!queue.offer(new TranslationEntry(blockMsg.getId(), fragments),
                                        5, TimeUnit.SECONDS)) {
                                    System.err.println("[bridge] Write queue full, dropping part");
                                }
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        }
                    }
                    // Non-block parts discarded — skeleton provides structure.
                }
            }

            @Override
            public void onError(Throwable t) {
                System.err.println("[bridge] Write stream error: " + t.getMessage());
                queue.clear(); // Make room for sentinel.
                queue.offer(TranslationEntry.END); // Unblock merge thread.
                if (mergeFuture != null) {
                    mergeFuture.cancel(true); // Interrupt merge thread.
                }
            }

            @Override
            public void onCompleted() {
                try {
                    if (header == null) {
                        responseObserver.onNext(WriteResponse.newBuilder()
                                .setError("no header received")
                                .build());
                        responseObserver.onCompleted();
                        return;
                    }

                    // Signal end of stream and wait for merge to complete.
                    queue.put(TranslationEntry.END);
                    WriteResult result = mergeFuture.get();
                    WriteResponse.Builder resp = WriteResponse.newBuilder();
                    if (result.isReferenced()) {
                        resp.setOutputPath(result.getOutputPath());
                    } else {
                        resp.setOutput(ByteString.copyFrom(result.getBytes()));
                    }
                    responseObserver.onNext(resp.build());
                    responseObserver.onCompleted();
                } catch (Exception e) {
                    Throwable cause = e instanceof ExecutionException ? e.getCause() : e;
                    System.err.println("[bridge] Write error: " + cause.getMessage());
                    cause.printStackTrace(System.err);
                    responseObserver.onNext(WriteResponse.newBuilder()
                            .setError(cause.getMessage())
                            .build());
                    responseObserver.onCompleted();
                }
            }
        };
    }

    @Override
    public void close(CloseRequest request, StreamObserver<CloseResponse> responseObserver) {
        try {
            closeCurrentFilter();
            responseObserver.onNext(CloseResponse.newBuilder().build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            responseObserver.onNext(CloseResponse.newBuilder()
                    .setError(e.getMessage())
                    .build());
            responseObserver.onCompleted();
        }
    }

    /**
     * Close the current filter and release all associated resources.
     * Nullifies references eagerly to prevent stale state when the bridge
     * is reused from the pool for the next document. This is especially
     * important for complex filters like OpenXML that manage ZIP archives
     * with internal sub-filter pipelines.
     */
    private void closeCurrentFilter() {
        if (currentFilter == null) {
            return;
        }

        IFilter filter = currentFilter;
        currentFilter = null;

        try {
            filter.close();
        } catch (Exception e) {
            System.err.println("[bridge] Error closing filter: " + e.getMessage());
        }
    }

    @Override
    public void shutdown(ShutdownRequest request, StreamObserver<ShutdownResponse> responseObserver) {
        System.err.println("[bridge] Shutdown requested");
        mergeExecutor.shutdownNow();
        responseObserver.onNext(ShutdownResponse.newBuilder().build());
        responseObserver.onCompleted();
        shutdownLatch.countDown();
    }

    // ── Private helpers ─────────────────────────────────────────────────────

    /**
     * Perform the write (roundtrip) operation: re-read the original document
     * through the filter, apply translations from parts, and write output.
     */
    private WriteResult performWrite(WriteHeader header, List<PartDTO> parts) throws Exception {
        String filterClass = header.getFilterClass();
        String locale = header.getLocale().isEmpty() ? "en" : header.getLocale();
        String encoding = header.getEncoding().isEmpty() ? "UTF-8" : header.getEncoding();

        // Resolve input content via the content resolver.
        File inputFile = resolveWriteContent(header);

        // Create filter for reading the skeleton.
        IFilter filter = FilterRegistry.createFilter(filterClass);
        if (filter == null) {
            throw new IllegalStateException("cannot instantiate filter: " + filterClass);
        }

        // Apply filter parameters.
        Map<String, String> filterParams = header.getFilterParamsMap();
        if (filterParams != null && !filterParams.isEmpty()) {
            applyFilterParams(filter, filterParams);
        }

        // Set up FilterConfigurationMapper for sub-filtering support in the write phase.
        // Without this, filters that use sub-filters (e.g., RegexFilter with useCodeFinder)
        // fail with NullPointerException when writing because the mapper is null.
        setupFilterConfigurationMapper(filter);

        // Create filter writer.
        IFilterWriter writer = filter.createFilterWriter();
        if (writer == null) {
            throw new IllegalStateException("filter does not support writing: " + filterClass);
        }

        // Resolve output destination before processing. When output_ref is set,
        // Okapi writes directly to disk — no in-memory buffering needed.
        String outputPath = resolveOutputPath(header);
        ByteArrayOutputStream outputStream = null;
        writer.setOptions(LocaleId.fromString(locale), encoding);
        if (outputPath != null) {
            writer.setOutput(outputPath);
        } else {
            outputStream = new ByteArrayOutputStream();
            writer.setOutput(outputStream);
        }

        LocaleId srcLocale = LocaleId.fromString("en");
        LocaleId tgtLocale = LocaleId.fromString(locale);
        RawDocument rawDoc = new RawDocument(inputFile.toURI(), encoding, srcLocale, tgtLocale);
        filter.open(rawDoc);

        // Feed events through the filter writer, replacing text units with translations.
        PartDTOConverter converter = new PartDTOConverter(parts, LocaleId.fromString(locale));
        while (filter.hasNext()) {
            Event event = filter.next();
            Event modified = converter.applyTranslations(event);
            writer.handleEvent(modified);
        }

        filter.close();
        writer.close();

        if (outputPath != null) {
            System.err.println("[bridge] Wrote output to " + outputPath);
            return WriteResult.ofPath(outputPath);
        } else {
            byte[] output = outputStream.toByteArray();
            System.err.println("[bridge] Wrote output (" + output.length + " bytes)");
            return WriteResult.ofBytes(output);
        }
    }

    /**
     * Perform the streaming write (roundtrip) operation: re-read the original document
     * through the filter, apply translations on-demand from the queue, and write output.
     * The merge thread runs concurrently with part arrival from Go.
     */
    private WriteResult performWriteStreaming(WriteHeader header,
                                              BlockingQueue<TranslationEntry> queue) throws Exception {
        String filterClass = header.getFilterClass();
        String locale = header.getLocale().isEmpty() ? "en" : header.getLocale();
        String encoding = header.getEncoding().isEmpty() ? "UTF-8" : header.getEncoding();

        // Resolve input content via the content resolver.
        File inputFile = resolveWriteContent(header);

        IFilter filter = FilterRegistry.createFilter(filterClass);
        if (filter == null) {
            throw new IllegalStateException("cannot instantiate filter: " + filterClass);
        }

        Map<String, String> filterParams = header.getFilterParamsMap();
        if (filterParams != null && !filterParams.isEmpty()) {
            applyFilterParams(filter, filterParams);
        }

        // Set up FilterConfigurationMapper for sub-filtering support in the write phase.
        setupFilterConfigurationMapper(filter);

        IFilterWriter writer = filter.createFilterWriter();
        if (writer == null) {
            throw new IllegalStateException("filter does not support writing: " + filterClass);
        }

        // Resolve output destination before processing. When output_ref is set,
        // Okapi writes directly to disk — no in-memory buffering needed.
        String outputPath = resolveOutputPath(header);
        ByteArrayOutputStream outputStream = null;
        writer.setOptions(LocaleId.fromString(locale), encoding);
        if (outputPath != null) {
            writer.setOutput(outputPath);
        } else {
            outputStream = new ByteArrayOutputStream();
            writer.setOutput(outputStream);
        }

        LocaleId srcLocale = LocaleId.fromString("en");
        LocaleId tgtLocale = LocaleId.fromString(locale);
        RawDocument rawDoc = new RawDocument(inputFile.toURI(), encoding, srcLocale, tgtLocale);
        filter.open(rawDoc);

        // Stream translations on-demand from the queue.
        StreamingTranslationApplier applier =
                new StreamingTranslationApplier(queue, LocaleId.fromString(locale));
        while (filter.hasNext()) {
            Event event = filter.next();
            Event modified = applier.applyTranslations(event);
            writer.handleEvent(modified);
        }

        filter.close();
        writer.close();

        if (outputPath != null) {
            System.err.println("[bridge] Wrote output to " + outputPath + " (streaming)");
            return WriteResult.ofPath(outputPath);
        } else {
            byte[] output = outputStream.toByteArray();
            System.err.println("[bridge] Wrote output (" + output.length + " bytes, streaming)");
            return WriteResult.ofBytes(output);
        }
    }

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

    /**
     * Resolve content for an Open request using the content resolver.
     * Checks content_ref first (new API), then falls back to legacy source_path/content fields.
     */
    private File resolveOpenContent(OpenRequest request) throws IOException {
        String extensionHint = extensionFromUri(request.getUri());

        // Prefer content_ref (new unified API).
        if (request.hasContentRef()) {
            ContentRef ref = request.getContentRef();
            switch (ref.getLocationCase()) {
                case PATH:
                    return contentResolver.resolvePath(ref.getPath());
                case URI:
                    return contentResolver.resolveUri(ref.getUri(), extensionHint);
                case INLINE:
                    return contentResolver.resolveInline(ref.getInline().toByteArray(), extensionHint);
                default:
                    break;
            }
        }

        // Legacy fallback: source_path takes precedence over content.
        String sourcePath = request.getSourcePath();
        if (sourcePath != null && !sourcePath.isEmpty()) {
            File file = new File(sourcePath);
            if (file.exists()) {
                return file;
            }
            // source_path set but file doesn't exist — fall through to inline content.
        }

        return contentResolver.resolveInline(request.getContent().toByteArray(), extensionHint);
    }

    /**
     * Resolve content for a Write request using the content resolver.
     * Checks original_content_ref first (new API), then falls back to legacy fields.
     */
    private File resolveWriteContent(WriteHeader header) throws IOException {
        // Prefer original_content_ref (new unified API).
        if (header.hasOriginalContentRef()) {
            ContentRef ref = header.getOriginalContentRef();
            switch (ref.getLocationCase()) {
                case PATH:
                    return contentResolver.resolvePath(ref.getPath());
                case URI:
                    return contentResolver.resolveUri(ref.getUri(), "");
                case INLINE:
                    return contentResolver.resolveInline(ref.getInline().toByteArray(), "");
                default:
                    break;
            }
        }

        // Legacy fallback.
        String sourcePath = header.getSourcePath();
        if (sourcePath != null && !sourcePath.isEmpty()) {
            File file = new File(sourcePath);
            if (file.exists()) {
                return file;
            }
        }

        return contentResolver.resolveInline(header.getOriginalContent().toByteArray(), "");
    }

    /**
     * Resolve the output file path from the header's output_ref.
     * Returns null if no output_ref is set (output should be returned inline).
     * When a path is returned, Okapi's filter writer can write directly to it
     * via setOutput(String), avoiding in-memory buffering entirely.
     */
    private String resolveOutputPath(WriteHeader header) throws IOException {
        if (!header.hasOutputRef()) return null;
        OutputRef ref = header.getOutputRef();
        switch (ref.getDestinationCase()) {
            case PATH:
                // Ensure parent directories exist.
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

    /**
     * Extract file extension from a URI string (e.g., "document.html" -> ".html").
     */
    private static String extensionFromUri(String uri) {
        if (uri == null || uri.isEmpty()) return "";
        int dotIdx = uri.lastIndexOf('.');
        return (dotIdx >= 0) ? uri.substring(dotIdx) : "";
    }

    /** Reserved param keys handled specially by the bridge. */
    private static final java.util.Set<String> RESERVED_PARAMS = new java.util.HashSet<>(
            java.util.Arrays.asList("configFile", "fprmContent"));

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
    private void applyFilterParams(IFilter filter, Map<String, String> params) {
        IParameters filterParameters = filter.getParameters();
        if (filterParameters == null) {
            System.err.println("[bridge] Warning: Filter does not support parameters");
            return;
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

    /**
     * Set up a FilterConfigurationMapper on the filter so that sub-filtering works.
     * Filters like RegexFilter with useCodeFinder need to resolve sub-filter IDs
     * (e.g., "okf_html") to their filter classes. Without this mapper, opening a
     * filter with sub-filtering config causes a NullPointerException on fcMapper.
     */
    private void setupFilterConfigurationMapper(IFilter filter) {
        try {
            // Create a FilterConfigurationMapper with all discovered filters.
            net.sf.okapi.common.filters.FilterConfigurationMapper fcMapper =
                    new net.sf.okapi.common.filters.FilterConfigurationMapper();

            // Register all discovered filters and their configurations.
            for (String fc : FilterRegistry.getFilterClasses()) {
                try {
                    IFilter f = FilterRegistry.createFilter(fc);
                    if (f != null) {
                        List<net.sf.okapi.common.filters.FilterConfiguration> configs = f.getConfigurations();
                        if (configs != null) {
                            for (net.sf.okapi.common.filters.FilterConfiguration cfg : configs) {
                                fcMapper.addConfigurations(fc);
                                break; // Only need to register each filter class once
                            }
                        }
                    }
                } catch (Exception e) {
                    // Skip filters that fail to instantiate
                }
            }

            filter.setFilterConfigurationMapper(fcMapper);
        } catch (Exception e) {
            // Non-fatal: sub-filtering won't work but basic filtering will.
            System.err.println("[bridge] Warning: Could not set up FilterConfigurationMapper: " + e.getMessage());
        }
    }

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
