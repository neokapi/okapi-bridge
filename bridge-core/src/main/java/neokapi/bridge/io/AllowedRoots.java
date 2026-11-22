package neokapi.bridge.io;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

/**
 * The directories the host lets the bridge read and write.
 *
 * <p>The host names them in {@value #ENV} when it starts the bridge, separated
 * by the platform path separator. Each root is resolved to its real path once.
 * Every file path the host sends is resolved to its real path too, links
 * followed, and refused unless it lies inside a root. An input must be an
 * existing regular file. An output that does not exist yet is resolved through
 * its nearest existing ancestor, so the directories created for it are always
 * inside a root.
 *
 * <p>With no root declared, every file path is refused with a message naming
 * the remedy. Inline content needs no root.
 */
public final class AllowedRoots {

    /** The environment variable the host declares the roots in. */
    public static final String ENV = "KAPI_PLUGIN_ROOTS";

    private final List<Path> roots;

    private AllowedRoots(List<Path> roots) {
        this.roots = Collections.unmodifiableList(roots);
    }

    /** Reads the roots from {@value #ENV}. */
    public static AllowedRoots fromEnvironment() {
        return parse(System.getenv(ENV));
    }

    /**
     * Parses a {@link File#pathSeparator}-separated list of directories. An
     * entry that is relative, missing or not a directory is reported on stderr
     * and left out.
     */
    public static AllowedRoots parse(String value) {
        List<Path> parsed = new ArrayList<>();
        if (value != null) {
            for (String entry : value.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
                if (entry.isEmpty()) {
                    continue;
                }
                try {
                    Path p = Paths.get(entry);
                    if (!p.isAbsolute()) {
                        System.err.println("[bridge] Ignoring " + ENV + " entry " + entry + ": not an absolute path");
                        continue;
                    }
                    Path real = p.toRealPath();
                    if (!Files.isDirectory(real)) {
                        System.err.println("[bridge] Ignoring " + ENV + " entry " + entry + ": not a directory");
                        continue;
                    }
                    parsed.add(real);
                } catch (IOException | InvalidPathException e) {
                    System.err.println("[bridge] Ignoring " + ENV + " entry " + entry + ": " + e.getMessage());
                }
            }
        }
        return new AllowedRoots(parsed);
    }

    /** Roots given directly, resolved like the entries of {@value #ENV}. */
    public static AllowedRoots of(Path... dirs) {
        StringBuilder sb = new StringBuilder();
        for (Path dir : dirs) {
            if (sb.length() > 0) {
                sb.append(File.pathSeparator);
            }
            sb.append(dir.toAbsolutePath());
        }
        return parse(sb.toString());
    }

    /** True when the host declared no usable root. */
    public boolean isEmpty() {
        return roots.isEmpty();
    }

    /** The roots as real paths. */
    public List<Path> roots() {
        return roots;
    }

    /**
     * Resolves an input path: it must exist, be a regular file once links are
     * followed, and lie inside a root.
     *
     * @return the real path of the file
     */
    public Path checkInput(String path) throws IOException {
        Path p = absolute(path, "input");
        Path real;
        try {
            real = p.toRealPath();
        } catch (NoSuchFileException e) {
            throw new IOException("Content file not found: " + path, e);
        }
        if (!Files.isRegularFile(real)) {
            throw new IOException("okapi-bridge refuses input " + path + ": " + real + " is not a regular file");
        }
        return within(real, path, "input");
    }

    /**
     * Resolves an output path. An existing output must be a regular file once
     * links are followed. A missing one is placed under the real path of its
     * nearest existing ancestor. Either way the result must lie inside a root.
     * The path may not contain {@code .} or {@code ..} segments.
     *
     * @return the real path to write, whose missing parent directories all lie
     *         inside a root
     */
    public Path checkOutput(String path) throws IOException {
        Path p = absolute(path, "output");
        for (Path segment : p) {
            String s = segment.toString();
            if (s.equals(".") || s.equals("..")) {
                throw new IOException("okapi-bridge refuses output " + path + ": it contains a '" + s + "' segment");
            }
        }
        if (Files.exists(p)) {
            Path real = p.toRealPath();
            if (!Files.isRegularFile(real)) {
                throw new IOException("okapi-bridge refuses output " + path + ": " + real + " is not a regular file");
            }
            return within(real, path, "output");
        }

        Deque<Path> missing = new ArrayDeque<>();
        Path ancestor = p;
        while (ancestor != null && !Files.exists(ancestor)) {
            if (Files.isSymbolicLink(ancestor)) {
                throw new IOException("okapi-bridge refuses output " + path + ": " + ancestor
                        + " is a link to a missing file");
            }
            missing.push(ancestor.getFileName());
            ancestor = ancestor.getParent();
        }
        if (ancestor == null) {
            throw new IOException("okapi-bridge refuses output " + path + ": no part of it exists");
        }
        Path real = ancestor.toRealPath();
        if (!Files.isDirectory(real)) {
            throw new IOException("okapi-bridge refuses output " + path + ": " + real + " is not a directory");
        }
        for (Path name : missing) {
            real = real.resolve(name.toString());
        }
        return within(real, path, "output");
    }

    private Path absolute(String path, String role) throws IOException {
        if (roots.isEmpty()) {
            throw new IOException("okapi-bridge refuses " + role + " " + path
                    + ": the host declared no directories the bridge may use. "
                    + "kapi declares them in " + ENV + " when it starts the bridge; "
                    + "update kapi, or set " + ENV + " to the directories the bridge may read and write, "
                    + "separated by '" + File.pathSeparator + "'.");
        }
        Path p;
        try {
            p = Paths.get(path);
        } catch (InvalidPathException e) {
            throw new IOException("okapi-bridge refuses " + role + " " + path + ": " + e.getMessage(), e);
        }
        if (!p.isAbsolute()) {
            throw new IOException("okapi-bridge refuses " + role + " " + path + ": not an absolute path");
        }
        return p;
    }

    private Path within(Path real, String path, String role) throws IOException {
        for (Path root : roots) {
            if (real.startsWith(root)) {
                return real;
            }
        }
        throw new IOException("okapi-bridge refuses " + role + " " + path + ": it resolves to " + real
                + ", outside the directories the host declared in " + ENV + " " + roots);
    }
}
