package com.gokapi.bridge.grpc;

import com.gokapi.bridge.EventConverter;
import com.gokapi.bridge.PartDTOConverter;
import com.gokapi.bridge.model.*;
import com.gokapi.bridge.proto.*;
import com.gokapi.bridge.util.FilterRegistry;
import com.gokapi.bridge.util.ParameterApplier;
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
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.CountDownLatch;

/**
 * gRPC implementation of the BridgeService.
 * Delegates to existing EventConverter/PartDTOConverter for Okapi event handling.
 */
public class BridgeServiceImpl extends BridgeServiceGrpc.BridgeServiceImplBase {

    private IFilter currentFilter;
    private byte[] currentContent;

    private final CountDownLatch shutdownLatch = new CountDownLatch(1);

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

            // Write content to temp file for filters needing random access.
            String ext = "";
            int dotIdx = uri.lastIndexOf('.');
            if (dotIdx >= 0) {
                ext = uri.substring(dotIdx);
            }
            File tempFile = File.createTempFile("gokapi-bridge-", ext);
            tempFile.deleteOnExit();
            Files.write(tempFile.toPath(), content);

            LocaleId srcLocale = LocaleId.fromString(sourceLocale);
            LocaleId tgtLocale = LocaleId.fromString(targetLocale);
            RawDocument rawDoc = new RawDocument(tempFile.toURI(), encoding, srcLocale, tgtLocale);

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
            private final List<PartDTO> parts = new ArrayList<>();

            @Override
            public void onNext(WriteChunk chunk) {
                if (chunk.hasHeader()) {
                    header = chunk.getHeader();
                } else if (chunk.hasPart()) {
                    parts.add(ProtoAdapter.fromProto(chunk.getPart()));
                }
            }

            @Override
            public void onError(Throwable t) {
                System.err.println("[bridge] Write stream error: " + t.getMessage());
                // Client errored, nothing to respond to.
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

                    byte[] output = performWrite(header, parts);
                    responseObserver.onNext(WriteResponse.newBuilder()
                            .setOutput(ByteString.copyFrom(output))
                            .build());
                    responseObserver.onCompleted();
                } catch (Exception e) {
                    System.err.println("[bridge] Write error: " + e.getMessage());
                    e.printStackTrace(System.err);
                    responseObserver.onNext(WriteResponse.newBuilder()
                            .setError(e.getMessage())
                            .build());
                    responseObserver.onCompleted();
                }
            }
        };
    }

    @Override
    public void close(CloseRequest request, StreamObserver<CloseResponse> responseObserver) {
        try {
            if (currentFilter != null) {
                try {
                    currentFilter.close();
                } catch (Exception e) {
                    System.err.println("[bridge] Error closing filter: " + e.getMessage());
                }
                currentFilter = null;
                currentContent = null;
            }
            responseObserver.onNext(CloseResponse.newBuilder().build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            responseObserver.onNext(CloseResponse.newBuilder()
                    .setError(e.getMessage())
                    .build());
            responseObserver.onCompleted();
        }
    }

    @Override
    public void shutdown(ShutdownRequest request, StreamObserver<ShutdownResponse> responseObserver) {
        System.err.println("[bridge] Shutdown requested");
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

        // Create filter writer.
        IFilterWriter writer = filter.createFilterWriter();
        if (writer == null) {
            throw new IllegalStateException("filter does not support writing: " + filterClass);
        }

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        writer.setOptions(LocaleId.fromString(locale), encoding);
        writer.setOutput(outputStream);

        // Write original content to temp file.
        File tempFile = File.createTempFile("gokapi-bridge-write-", "");
        tempFile.deleteOnExit();
        Files.write(tempFile.toPath(), originalContent);

        LocaleId srcLocale = LocaleId.fromString("en");
        LocaleId tgtLocale = LocaleId.fromString(locale);
        RawDocument rawDoc = new RawDocument(tempFile.toURI(), encoding, srcLocale, tgtLocale);
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
     * Apply filter parameters from the gRPC map<string, string> format.
     * Values that look like JSON are parsed back to JsonElements for
     * the ParameterApplier, which expects a JsonObject.
     */
    private void applyFilterParams(IFilter filter, Map<String, String> params) {
        IParameters filterParameters = filter.getParameters();
        if (filterParameters == null) {
            System.err.println("[bridge] Warning: Filter does not support parameters");
            return;
        }

        // Convert map<string, string> back to JsonObject.
        // The Go side JSON-encodes non-string values (booleans, numbers, objects).
        JsonObject jsonParams = new JsonObject();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            String value = entry.getValue();
            try {
                JsonElement parsed = JsonParser.parseString(value);
                jsonParams.add(entry.getKey(), parsed);
            } catch (Exception e) {
                // Not valid JSON — use as plain string.
                jsonParams.addProperty(entry.getKey(), value);
            }
        }

        boolean success = ParameterApplier.applyParameters(filterParameters, jsonParams);
        if (success) {
            System.err.println("[bridge] Applied " + params.size() + " filter parameters");
        } else {
            System.err.println("[bridge] Warning: Some filter parameters could not be applied");
        }
    }

    private static String nullSafe(String s) {
        return s != null ? s : "";
    }
}
