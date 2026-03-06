package com.gokapi.bridge.io;

import java.io.File;
import java.io.IOException;

/**
 * Resolves a content reference to a local file that Okapi can process.
 * Implementations handle different location types: inline bytes, local paths,
 * and remote URIs (S3, GCS, etc.).
 */
public interface ContentResolver {

    /**
     * Resolve inline bytes to a local file.
     *
     * @param content       the raw bytes
     * @param extensionHint file extension hint for temp files (e.g., ".html"), may be empty
     * @return a File that Okapi can read via URI
     */
    File resolveInline(byte[] content, String extensionHint) throws IOException;

    /**
     * Resolve a local filesystem path to a File.
     *
     * @param path the absolute filesystem path
     * @return the File, validated to exist
     */
    File resolvePath(String path) throws IOException;

    /**
     * Resolve a URI reference to a local file.
     * For file:// URIs, returns the local file directly.
     * For remote URIs (s3://, gs://), downloads to a temp file.
     *
     * @param uri           the URI string
     * @param extensionHint file extension hint for temp files
     * @return a File that Okapi can read via URI
     */
    File resolveUri(String uri, String extensionHint) throws IOException;
}
