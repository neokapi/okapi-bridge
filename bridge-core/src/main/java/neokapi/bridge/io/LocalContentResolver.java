package neokapi.bridge.io;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;

/**
 * Content resolver for local filesystem operations.
 * Handles inline bytes (writes to temp file), local paths and file:// URIs.
 * A path is opened only when it resolves to a regular file inside one of the
 * {@link AllowedRoots}; the returned File is its real path.
 */
public class LocalContentResolver implements ContentResolver {

    private final AllowedRoots roots;

    /** A resolver confined to the roots the host declared in the environment. */
    public LocalContentResolver() {
        this(AllowedRoots.fromEnvironment());
    }

    public LocalContentResolver(AllowedRoots roots) {
        this.roots = roots;
    }

    @Override
    public File resolveInline(byte[] content, String extensionHint) throws IOException {
        String ext = (extensionHint != null && !extensionHint.isEmpty()) ? extensionHint : "";
        File tempFile = File.createTempFile("neokapi-bridge-", ext);
        tempFile.deleteOnExit();
        Files.write(tempFile.toPath(), content);
        return tempFile;
    }

    @Override
    public File resolvePath(String path) throws IOException {
        return roots.checkInput(path).toFile();
    }

    @Override
    public File resolveUri(String uri, String extensionHint) throws IOException {
        URI parsed;
        try {
            parsed = URI.create(uri);
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid URI: " + uri, e);
        }

        String scheme = parsed.getScheme();
        if (scheme == null || "file".equals(scheme)) {
            // file:// URI or no scheme — treat as local path.
            File file = new File(parsed.getSchemeSpecificPart() != null
                    ? parsed.getSchemeSpecificPart() : uri);
            if ("file".equals(scheme)) {
                try {
                    file = new File(parsed);
                } catch (IllegalArgumentException e) {
                    file = new File(parsed.getPath());
                }
            }
            return resolvePath(file.getPath());
        }

        throw new IOException("Unsupported URI scheme '" + scheme + "'. " +
                "Only local file paths and file:// URIs are currently supported. " +
                "Remote storage (s3://, gs://) requires additional resolver implementations.");
    }
}
