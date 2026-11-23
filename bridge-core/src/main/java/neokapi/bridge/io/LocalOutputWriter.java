package neokapi.bridge.io;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Output writer for local filesystem destinations.
 * Handles local paths and file:// URIs. A destination is written only when it
 * resolves inside one of the {@link AllowedRoots}, and missing parent
 * directories are created only there.
 */
public class LocalOutputWriter implements OutputWriter {

    private final AllowedRoots roots;

    /** A writer confined to the roots the host declared in the environment. */
    public LocalOutputWriter() {
        this(AllowedRoots.fromEnvironment());
    }

    public LocalOutputWriter(AllowedRoots roots) {
        this.roots = roots;
    }

    @Override
    public String writePath(String path, byte[] content) throws IOException {
        Path target = Paths.get(resolvePath(path));
        Files.write(target, content);
        return target.toString();
    }

    @Override
    public String resolvePath(String path) throws IOException {
        Path target = roots.checkOutput(path);
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        return target.toString();
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
            return resolvePath(path);
        }

        throw new IOException("Unsupported URI scheme '" + scheme + "'. " +
                "Only local file paths and file:// URIs are currently supported. " +
                "Remote storage (s3://, gs://) requires additional output writer implementations.");
    }
}
