package com.gokapi.bridge.grpc;

import com.gokapi.bridge.EventConverter;
import com.gokapi.bridge.PartDTOConverter;
import com.gokapi.bridge.model.*;
import com.gokapi.bridge.proto.*;
import com.gokapi.bridge.util.FilterRegistry;
import com.gokapi.bridge.util.ParameterApplier;
import com.gokapi.bridge.util.ParameterFlattener;
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
    private byte[] currentContent;

    private final CountDownLatch shutdownLatch = new CountDownLatch(1);
    private final ExecutorService mergeExecutor = Executors.newCachedThreadPool();
    private final Map<String, ParameterFlattener> flattenerCache = new ConcurrentHashMap<>();

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
            byte[] content = request.getContent().toByteArray();
            String uri = request.getUri();
            String sourcePath = request.getSourcePath();
            String sourceLocale = request.getSourceLocale().isEmpty() ? "en" : request.getSourceLocale();
            String targetLocale = request.getTargetLocale().isEmpty() ? "fr" : request.getTargetLocale();
            String encoding = request.getEncoding().isEmpty() ? "UTF-8" : request.getEncoding();

            currentContent = content;

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

            // Use source_path for direct disk access when available.
            // This enables relative URI resolution for auxiliary files
            // (e.g. ITS standoff annotations, external skeleton files).
            File inputFile;
            if (sourcePath != null && !sourcePath.isEmpty()) {
                inputFile = new File(sourcePath);
                if (!inputFile.exists()) {
                    inputFile = writeTempFile(content, uri);
                }
            } else {
                inputFile = writeTempFile(content, uri);
            }

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
            private Future<byte[]> mergeFuture;

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
                    byte[] output = mergeFuture.get();
                    responseObserver.onNext(WriteResponse.newBuilder()
                            .setOutput(ByteString.copyFrom(output))
                            .build());
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
        currentContent = null;

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
    private byte[] performWrite(WriteHeader header, List<PartDTO> parts) throws Exception {
        String filterClass = header.getFilterClass();
        String locale = header.getLocale().isEmpty() ? "en" : header.getLocale();
        String encoding = header.getEncoding().isEmpty() ? "UTF-8" : header.getEncoding();
        byte[] originalContent = header.getOriginalContent().toByteArray();
        String sourcePath = header.getSourcePath();

        // For XLIFF filters, strip empty target-language="" from the original content.
        // Okapi's XLIFF writer sets target-language from the RawDocument target locale,
        // but doesn't remove a pre-existing empty one from the skeleton, producing
        // invalid XML with duplicate attributes.
        if (isXliffFilter(filterClass)) {
            originalContent = stripEmptyTargetLanguage(originalContent, sourcePath);
            // We modified the content, so we can't use sourcePath for reading.
            // But we still need the directory context for auxiliary file resolution
            // (e.g., ITS standoff annotations like lqiTestIssues.xml).
            // Write modified content as a sibling temp file in the source directory.
            if (sourcePath != null && !sourcePath.isEmpty()) {
                File sourceDir = new File(sourcePath).getParentFile();
                if (sourceDir != null && sourceDir.isDirectory()) {
                    File siblingTemp = File.createTempFile("gokapi-bridge-", ".xlf", sourceDir);
                    siblingTemp.deleteOnExit();
                    Files.write(siblingTemp.toPath(), originalContent);
                    sourcePath = siblingTemp.getAbsolutePath();
                } else {
                    sourcePath = "";
                }
            }
        }

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

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        writer.setOptions(LocaleId.fromString(locale), encoding);
        writer.setOutput(outputStream);

        // Use source_path for direct disk access when available.
        File inputFile;
        if (sourcePath != null && !sourcePath.isEmpty()) {
            inputFile = new File(sourcePath);
            if (!inputFile.exists()) {
                inputFile = writeTempFile(originalContent, "");
            }
        } else {
            inputFile = writeTempFile(originalContent, "");
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

        System.err.println("[bridge] Wrote output (" + outputStream.size() + " bytes)");
        return outputStream.toByteArray();
    }

    /**
     * Perform the streaming write (roundtrip) operation: re-read the original document
     * through the filter, apply translations on-demand from the queue, and write output.
     * The merge thread runs concurrently with part arrival from Go.
     */
    private byte[] performWriteStreaming(WriteHeader header,
                                         BlockingQueue<TranslationEntry> queue) throws Exception {
        String filterClass = header.getFilterClass();
        String locale = header.getLocale().isEmpty() ? "en" : header.getLocale();
        String encoding = header.getEncoding().isEmpty() ? "UTF-8" : header.getEncoding();
        byte[] originalContent = header.getOriginalContent().toByteArray();
        String sourcePath = header.getSourcePath();

        // XLIFF empty target-language stripping (same as performWrite).
        if (isXliffFilter(filterClass)) {
            originalContent = stripEmptyTargetLanguage(originalContent, sourcePath);
            if (sourcePath != null && !sourcePath.isEmpty()) {
                File sourceDir = new File(sourcePath).getParentFile();
                if (sourceDir != null && sourceDir.isDirectory()) {
                    File siblingTemp = File.createTempFile("gokapi-bridge-", ".xlf", sourceDir);
                    siblingTemp.deleteOnExit();
                    Files.write(siblingTemp.toPath(), originalContent);
                    sourcePath = siblingTemp.getAbsolutePath();
                } else {
                    sourcePath = "";
                }
            }
        }

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

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        writer.setOptions(LocaleId.fromString(locale), encoding);
        writer.setOutput(outputStream);

        File inputFile;
        if (sourcePath != null && !sourcePath.isEmpty()) {
            inputFile = new File(sourcePath);
            if (!inputFile.exists()) {
                inputFile = writeTempFile(originalContent, "");
            }
        } else {
            inputFile = writeTempFile(originalContent, "");
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

        System.err.println("[bridge] Wrote output (" + outputStream.size() + " bytes, streaming)");
        return outputStream.toByteArray();
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
     * Write content to a temp file for filters needing random access.
     * The file extension is derived from the URI (if any) to help Okapi
     * auto-detect the format.
     */
    private File writeTempFile(byte[] content, String uri) throws IOException {
        String ext = "";
        if (uri != null && !uri.isEmpty()) {
            int dotIdx = uri.lastIndexOf('.');
            if (dotIdx >= 0) {
                ext = uri.substring(dotIdx);
            }
        }
        File tempFile = File.createTempFile("gokapi-bridge-", ext);
        tempFile.deleteOnExit();
        Files.write(tempFile.toPath(), content);
        return tempFile;
    }

    /**
     * Apply filter parameters from the gRPC map<string, string> format.
     * Values that look like JSON are parsed back to JsonElements for
     * the ParameterApplier, which expects a JsonObject.
     *
     * Special handling for "configFile": when present, the file is read and
     * its content is loaded via params.fromString() before applying any
     * remaining parameters. This supports filters with non-YAML config formats
     * (e.g., .fprm files used by RegexFilter, PlainTextFilter).
     */
    private void applyFilterParams(IFilter filter, Map<String, String> params) {
        IParameters filterParameters = filter.getParameters();
        if (filterParameters == null) {
            System.err.println("[bridge] Warning: Filter does not support parameters");
            return;
        }

        // Handle configFile: load the entire configuration from a file.
        // This is essential for filters like RegexFilter whose .fprm configs
        // define rules, escape settings, and other structured parameters that
        // cannot be applied as individual key-value pairs.
        //
        // We prefer load(URL) over fromString() because load(URL) is what
        // Okapi's own test infrastructure uses. Some filters (e.g., RegexFilter)
        // have fromString() implementations that silently succeed but produce
        // incomplete configurations — load(URL) handles all format versions
        // correctly including the #v1 header in .fprm files.
        String configFilePath = params.get("configFile");
        if (configFilePath != null && !configFilePath.isEmpty()) {
            try {
                java.net.URL configUrl = new File(configFilePath).toURI().toURL();
                filterParameters.load(configUrl, false);
                System.err.println("[bridge] Loaded config from file: " + configFilePath);
            } catch (Exception e) {
                // Fall back to fromString() if load(URL) fails.
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

        // Convert remaining params (excluding configFile) to JsonObject.
        // The Go side JSON-encodes non-string values (booleans, numbers, objects).
        JsonObject jsonParams = new JsonObject();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if ("configFile".equals(entry.getKey())) {
                continue; // Already handled above
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
     * Check if a filter class is an XLIFF filter (1.2 or 2.0).
     */
    private static boolean isXliffFilter(String filterClass) {
        return filterClass != null && (
                filterClass.contains("XLIFFFilter") ||
                filterClass.contains("XLIFF2Filter"));
    }

    /**
     * Strip empty target-language="" attributes from XLIFF content.
     * When sourcePath is set, reads the file content first. Returns the
     * cleaned content bytes (always use temp file after this).
     */
    private byte[] stripEmptyTargetLanguage(byte[] content, String sourcePath) {
        byte[] raw = content;
        if ((raw == null || raw.length == 0) && sourcePath != null && !sourcePath.isEmpty()) {
            try {
                raw = Files.readAllBytes(new File(sourcePath).toPath());
            } catch (IOException e) {
                return content; // fall back to original
            }
        }
        if (raw == null || raw.length == 0) {
            return content;
        }
        String text = new String(raw, java.nio.charset.StandardCharsets.UTF_8);
        // Remove empty target-language="" or target-language='' attributes.
        // This prevents duplicate attributes when Okapi's XLIFF writer sets
        // the target-language from the RawDocument target locale.
        String cleaned = text.replaceAll("\\s+target-language\\s*=\\s*[\"'][\"']", "");
        return cleaned.getBytes(java.nio.charset.StandardCharsets.UTF_8);
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
     */
    private JsonObject loadSchema(String filterId) {
        String[] paths = {
            "/schemas/" + filterId + ".schema.json",
            "schemas/" + filterId + ".schema.json"
        };
        for (String path : paths) {
            try (InputStream is = getClass().getResourceAsStream(path)) {
                if (is != null) {
                    try (InputStreamReader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
                        return JsonParser.parseReader(reader).getAsJsonObject();
                    }
                }
            } catch (Exception e) {
                System.err.println("[bridge] Could not load schema for " + filterId + ": " + e.getMessage());
            }
        }
        return null;
    }

    private static String nullSafe(String s) {
        return s != null ? s : "";
    }
}
