package neokapi.bridge.io;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class LocalOutputWriterTest {

    @TempDir
    Path tempDir;

    private LocalOutputWriter writer;

    @BeforeEach
    void setUp() {
        writer = new LocalOutputWriter(AllowedRoots.of(tempDir));
    }

    @Test
    void writePath_outsideRoot_isRefusedAndCreatesNothing(@TempDir Path elsewhere) {
        Path outFile = elsewhere.resolve("sub/output.txt");
        IOException ex = assertThrows(IOException.class, () -> writer.writePath(outFile.toString(), "x".getBytes()));
        assertTrue(ex.getMessage().contains("outside the directories"));
        assertFalse(Files.exists(elsewhere.resolve("sub")));
    }

    @Test
    void resolvePath_beneathLinkedDirectory_isRefusedAndCreatesNothing(@TempDir Path elsewhere) throws IOException {
        Path link = Files.createSymbolicLink(tempDir.resolve("linked"), elsewhere);
        IOException ex = assertThrows(IOException.class,
                () -> writer.resolvePath(link.resolve("sub/output.txt").toString()));
        assertTrue(ex.getMessage().contains("outside the directories"));
        assertFalse(Files.exists(elsewhere.resolve("sub")));
    }

    @Test
    void resolvePath_createsParentDirectoriesInsideRoot() throws IOException {
        Path outFile = tempDir.resolve("a/b/output.txt");
        String result = writer.resolvePath(outFile.toString());
        assertEquals(tempDir.toRealPath().resolve("a/b/output.txt").toString(), result);
        assertTrue(Files.isDirectory(tempDir.resolve("a/b")));
    }

    @Test
    void writePath_writesContent() throws IOException {
        Path outFile = tempDir.resolve("output.html");
        byte[] content = "<html>translated</html>".getBytes();

        String result = writer.writePath(outFile.toString(), content);
        assertTrue(Files.exists(outFile));
        assertEquals("<html>translated</html>", new String(Files.readAllBytes(outFile)));
        assertNotNull(result);
    }

    @Test
    void writePath_createsParentDirectories() throws IOException {
        Path outFile = tempDir.resolve("sub/dir/output.txt");
        byte[] content = "content".getBytes();

        String result = writer.writePath(outFile.toString(), content);
        assertTrue(Files.exists(outFile));
        assertEquals("content", new String(Files.readAllBytes(outFile)));
    }

    @Test
    void writeUri_fileUri_writesContent() throws IOException {
        Path outFile = tempDir.resolve("output.xml");
        byte[] content = "<doc>translated</doc>".getBytes();

        String result = writer.writeUri(outFile.toUri().toString(), content);
        assertTrue(Files.exists(outFile));
        assertEquals("<doc>translated</doc>", new String(Files.readAllBytes(outFile)));
    }

    @Test
    void writeUri_unsupportedScheme_throwsIOException() {
        IOException ex = assertThrows(IOException.class, () ->
                writer.writeUri("s3://bucket/output.txt", "content".getBytes()));
        assertTrue(ex.getMessage().contains("Unsupported URI scheme"));
    }

    @Test
    void resolveUri_fileUri_returnsPath() throws IOException {
        Path outFile = tempDir.resolve("output.xml");
        String result = writer.resolveUri(outFile.toUri().toString());
        assertEquals(tempDir.toRealPath().resolve("output.xml").toString(), result);
    }

    @Test
    void resolveUri_createsParentDirectories() throws IOException {
        Path outFile = tempDir.resolve("sub/dir/output.txt");
        String result = writer.resolveUri(outFile.toUri().toString());
        assertTrue(Files.isDirectory(tempDir.resolve("sub/dir")));
    }

    @Test
    void resolveUri_unsupportedScheme_throwsIOException() {
        IOException ex = assertThrows(IOException.class, () ->
                writer.resolveUri("s3://bucket/output.txt"));
        assertTrue(ex.getMessage().contains("Unsupported URI scheme"));
    }
}
