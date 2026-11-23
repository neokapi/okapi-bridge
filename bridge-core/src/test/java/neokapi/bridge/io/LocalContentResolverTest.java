package neokapi.bridge.io;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class LocalContentResolverTest {

    @TempDir
    Path root;

    private LocalContentResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new LocalContentResolver(AllowedRoots.of(root));
    }

    @Test
    void resolveInline_writesToTempFile(@TempDir Path tempDir) throws IOException {
        byte[] content = "Hello, World!".getBytes();
        File result = resolver.resolveInline(content, ".txt");
        assertTrue(result.exists());
        assertEquals("Hello, World!", new String(Files.readAllBytes(result.toPath())));
        assertTrue(result.getName().endsWith(".txt"));
    }

    @Test
    void resolveInline_emptyExtension_succeeds() throws IOException {
        byte[] content = "test".getBytes();
        File result = resolver.resolveInline(content, "");
        assertTrue(result.exists());
        assertEquals("test", new String(Files.readAllBytes(result.toPath())));
    }

    @Test
    void resolvePath_existingFile_returnsRealPath() throws IOException {
        Path file = root.resolve("test.html");
        Files.write(file, "<html>test</html>".getBytes());

        File result = resolver.resolvePath(file.toString());
        assertEquals(file.toRealPath().toFile(), result);
    }

    @Test
    void resolvePath_outsideRoot_throwsIOException(@TempDir Path elsewhere) throws IOException {
        Path file = Files.write(elsewhere.resolve("test.html"), "x".getBytes());
        IOException ex = assertThrows(IOException.class, () -> resolver.resolvePath(file.toString()));
        assertTrue(ex.getMessage().contains("outside the directories"));
    }

    @Test
    void resolveUri_fileUriOutsideRoot_throwsIOException(@TempDir Path elsewhere) throws IOException {
        Path file = Files.write(elsewhere.resolve("doc.xml"), "<doc/>".getBytes());
        assertThrows(IOException.class, () -> resolver.resolveUri(file.toUri().toString(), ".xml"));
    }

    @Test
    void resolveUri_relativeSchemelessPath_throwsIOException() {
        IOException ex = assertThrows(IOException.class, () -> resolver.resolveUri("doc.xml", ".xml"));
        assertTrue(ex.getMessage().contains("not an absolute path"));
    }

    @Test
    void resolveInline_needsNoRoot() throws IOException {
        LocalContentResolver unconfined = new LocalContentResolver(AllowedRoots.parse(null));
        File result = unconfined.resolveInline("x".getBytes(), ".txt");
        assertTrue(result.exists());
    }

    @Test
    void resolvePath_missingFile_throwsIOException() {
        assertThrows(IOException.class, () ->
                resolver.resolvePath(root.resolve("nonexistent/file.txt").toString()));
    }

    @Test
    void resolveUri_fileUri_returnsFile() throws IOException {
        Path file = root.resolve("doc.xml");
        Files.write(file, "<doc/>".getBytes());

        File result = resolver.resolveUri(file.toUri().toString(), ".xml");
        assertTrue(result.exists());
        assertEquals("<doc/>", new String(Files.readAllBytes(result.toPath())));
    }

    @Test
    void resolveUri_unsupportedScheme_throwsIOException() {
        IOException ex = assertThrows(IOException.class, () ->
                resolver.resolveUri("s3://bucket/key.txt", ".txt"));
        assertTrue(ex.getMessage().contains("Unsupported URI scheme"));
    }

    @Test
    void resolveUri_missingFileUri_throwsIOException() {
        assertThrows(IOException.class, () ->
                resolver.resolveUri(root.resolve("nonexistent.txt").toUri().toString(), ".txt"));
    }
}
