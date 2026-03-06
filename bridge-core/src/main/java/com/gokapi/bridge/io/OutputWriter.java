package com.gokapi.bridge.io;

import java.io.IOException;

/**
 * Writes output content to a referenced destination.
 * Implementations handle different destination types: local paths and remote URIs.
 */
public interface OutputWriter {

    /**
     * Write output content to a local filesystem path.
     *
     * @param path    the absolute filesystem path to write to
     * @param content the output bytes
     * @return the canonical path where content was written
     */
    String writePath(String path, byte[] content) throws IOException;

    /**
     * Write output content to a URI destination.
     *
     * @param uri     the destination URI
     * @param content the output bytes
     * @return the resolved path/URI where content was written
     */
    String writeUri(String uri, byte[] content) throws IOException;

    /**
     * Resolve a URI to a local file path that can be used for direct file output.
     * For file:// URIs, returns the local path. For remote URIs, returns a local
     * staging path (the implementation is responsible for uploading on close).
     *
     * @param uri the destination URI
     * @return the resolved local file path
     */
    String resolveUri(String uri) throws IOException;
}
