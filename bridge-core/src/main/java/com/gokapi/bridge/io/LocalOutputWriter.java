package com.gokapi.bridge.io;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Output writer for local filesystem destinations.
 * Handles local paths and file:// URIs.
 */
public class LocalOutputWriter implements OutputWriter {

    @Override
    public String writePath(String path, byte[] content) throws IOException {
        Path target = new File(path).toPath();
        // Create parent directories if they don't exist.
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(target, content);
        return target.toFile().getCanonicalPath();
    }

    @Override
    public String writeUri(String uri, byte[] content) throws IOException {
        URI parsed;
        try {
            parsed = URI.create(uri);
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid URI: " + uri, e);
        }

        String scheme = parsed.getScheme();
        if (scheme == null || "file".equals(scheme)) {
            String path;
            if ("file".equals(scheme)) {
                try {
                    path = new File(parsed).getAbsolutePath();
                } catch (IllegalArgumentException e) {
                    path = parsed.getPath();
                }
            } else {
                path = uri;
            }
            return writePath(path, content);
        }

        throw new IOException("Unsupported URI scheme '" + scheme + "'. " +
                "Only local file paths and file:// URIs are currently supported. " +
                "Remote storage (s3://, gs://) requires additional output writer implementations.");
    }

    @Override
    public String resolveUri(String uri) throws IOException {
        URI parsed;
        try {
            parsed = URI.create(uri);
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid URI: " + uri, e);
        }

        String scheme = parsed.getScheme();
        if (scheme == null || "file".equals(scheme)) {
            String path;
            if ("file".equals(scheme)) {
                try {
                    path = new File(parsed).getAbsolutePath();
                } catch (IllegalArgumentException e) {
                    path = parsed.getPath();
                }
            } else {
                path = uri;
            }
            // Ensure parent directories exist.
            File parent = new File(path).getParentFile();
            if (parent != null) {
                Files.createDirectories(parent.toPath());
            }
            return path;
        }

        throw new IOException("Unsupported URI scheme '" + scheme + "'. " +
                "Only local file paths and file:// URIs are currently supported. " +
                "Remote storage (s3://, gs://) requires additional output writer implementations.");
    }
}
