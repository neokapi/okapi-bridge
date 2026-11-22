package neokapi.bridge.io;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class AllowedRootsTest {

    @TempDir
    Path tmp;

    private Path root;
    private Path outside;
    private AllowedRoots roots;

    @BeforeEach
    void setUp() throws IOException {
        root = Files.createDirectories(tmp.resolve("root")).toRealPath();
        outside = Files.createDirectories(tmp.resolve("outside")).toRealPath();
        roots = AllowedRoots.of(root);
    }

    private static void assertRefused(String fragment, ThrowingCall call) {
        IOException ex = assertThrows(IOException.class, call::run);
        assertTrue(ex.getMessage().contains(fragment), () -> "message: " + ex.getMessage());
    }

    @FunctionalInterface
    private interface ThrowingCall {
        void run() throws IOException;
    }

    // ── Inputs ──────────────────────────────────────────────────────────────

    @Test
    void input_insideRoot_isAccepted() throws IOException {
        Path file = Files.write(root.resolve("a.html"), "<p>x</p>".getBytes());
        assertEquals(file, roots.checkInput(file.toString()));
    }

    @Test
    void input_outsideRoot_isRefused() throws IOException {
        Path file = Files.write(outside.resolve("a.html"), "x".getBytes());
        assertRefused("outside the directories", () -> roots.checkInput(file.toString()));
    }

    @Test
    void input_linkOutOfRoot_isRefused() throws IOException {
        Path target = Files.write(outside.resolve("secret.txt"), "x".getBytes());
        Path link = Files.createSymbolicLink(root.resolve("link.txt"), target);
        assertRefused("outside the directories", () -> roots.checkInput(link.toString()));
    }

    @Test
    void input_linkWithinRoot_resolvesToTarget() throws IOException {
        Path target = Files.write(root.resolve("real.txt"), "x".getBytes());
        Path link = Files.createSymbolicLink(root.resolve("alias.txt"), target);
        assertEquals(target, roots.checkInput(link.toString()));
    }

    @Test
    void input_dotDotOutOfRoot_isRefused() throws IOException {
        Files.write(outside.resolve("a.txt"), "x".getBytes());
        String path = root + File.separator + ".." + File.separator + "outside" + File.separator + "a.txt";
        assertRefused("outside the directories", () -> roots.checkInput(path));
    }

    @Test
    void input_dotDotWithinRoot_isAccepted() throws IOException {
        Path file = Files.write(root.resolve("a.txt"), "x".getBytes());
        Files.createDirectories(root.resolve("sub"));
        String path = root + File.separator + "sub" + File.separator + ".." + File.separator + "a.txt";
        assertEquals(file, roots.checkInput(path));
    }

    @Test
    void input_directory_isRefused() throws IOException {
        Path dir = Files.createDirectories(root.resolve("dir"));
        assertRefused("not a regular file", () -> roots.checkInput(dir.toString()));
    }

    @Test
    void input_missing_isNotFound() {
        assertRefused("Content file not found", () -> roots.checkInput(root.resolve("nope.txt").toString()));
    }

    @Test
    void input_relative_isRefused() {
        assertRefused("not an absolute path", () -> roots.checkInput("a.txt"));
    }

    @Test
    void input_rootPrefixIsNotARoot() throws IOException {
        // "root-sibling" shares the string prefix "root" but is not inside it.
        Path sibling = Files.createDirectories(tmp.resolve("root-sibling"));
        Path file = Files.write(sibling.resolve("a.txt"), "x".getBytes());
        assertRefused("outside the directories", () -> roots.checkInput(file.toString()));
    }

    // ── Outputs ─────────────────────────────────────────────────────────────

    @Test
    void output_missingFileInsideRoot_isAccepted() throws IOException {
        Path out = root.resolve("sub/dir/out.xlf");
        assertEquals(out, roots.checkOutput(out.toString()));
        assertFalse(Files.exists(root.resolve("sub")), "checkOutput must not create directories");
    }

    @Test
    void output_existingFileInsideRoot_isAccepted() throws IOException {
        Path out = Files.write(root.resolve("out.xlf"), "x".getBytes());
        assertEquals(out, roots.checkOutput(out.toString()));
    }

    @Test
    void output_outsideRoot_isRefused() {
        Path out = outside.resolve("out.xlf");
        assertRefused("outside the directories", () -> roots.checkOutput(out.toString()));
    }

    @Test
    void output_beneathLinkedDirectory_isRefused() throws IOException {
        Path link = Files.createSymbolicLink(root.resolve("linked"), outside);
        Path out = link.resolve("new/dir/out.xlf");
        assertRefused("outside the directories", () -> roots.checkOutput(out.toString()));
        assertFalse(Files.exists(outside.resolve("new")));
    }

    @Test
    void output_existingLinkOutOfRoot_isRefused() throws IOException {
        Path target = Files.write(outside.resolve("victim.txt"), "x".getBytes());
        Path link = Files.createSymbolicLink(root.resolve("out.txt"), target);
        assertRefused("outside the directories", () -> roots.checkOutput(link.toString()));
    }

    @Test
    void output_danglingLink_isRefused() throws IOException {
        Path link = Files.createSymbolicLink(root.resolve("out.txt"), outside.resolve("not-yet.txt"));
        assertRefused("link to a missing file", () -> roots.checkOutput(link.toString()));
    }

    @Test
    void output_dotDot_isRefused() {
        String path = root + File.separator + ".." + File.separator + "outside" + File.separator + "out.xlf";
        assertRefused("'..' segment", () -> roots.checkOutput(path));
    }

    @Test
    void output_directory_isRefused() throws IOException {
        Path dir = Files.createDirectories(root.resolve("dir"));
        assertRefused("not a regular file", () -> roots.checkOutput(dir.toString()));
    }

    // ── No roots ────────────────────────────────────────────────────────────

    @Test
    void noRoots_refusesEveryPathAndNamesTheRemedy() throws IOException {
        AllowedRoots none = AllowedRoots.parse(null);
        assertTrue(none.isEmpty());
        Path file = Files.write(root.resolve("a.txt"), "x".getBytes());
        assertRefused(AllowedRoots.ENV, () -> none.checkInput(file.toString()));
        assertRefused("update kapi", () -> none.checkInput(file.toString()));
        assertRefused(AllowedRoots.ENV, () -> none.checkOutput(root.resolve("out.txt").toString()));
    }

    @Test
    void parse_skipsRelativeMissingAndFileEntries() throws IOException {
        Path file = Files.write(tmp.resolve("plain.txt"), "x".getBytes());
        String value = String.join(File.pathSeparator,
                "relative/dir", tmp.resolve("missing").toString(), file.toString(), "", root.toString());
        AllowedRoots parsed = AllowedRoots.parse(value);
        assertEquals(java.util.List.of(root), parsed.roots());
    }

    @Test
    void parse_resolvesALinkedRootToItsRealPath() throws IOException {
        Path link = Files.createSymbolicLink(tmp.resolve("root-link"), root);
        AllowedRoots viaLink = AllowedRoots.of(link);
        Path file = Files.write(root.resolve("a.txt"), "x".getBytes());
        assertEquals(file, viaLink.checkInput(link.resolve("a.txt").toString()));
    }
}
