package com.gokapi.bridge.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class LocalOutputWriterTest {

    private final LocalOutputWriter writer = new LocalOutputWriter();

    @Test
    void writePath_writesContent(@TempDir Path tempDir) throws IOException {
        Path outFile = tempDir.resolve("output.html");
        byte[] content = "<html>translated</html>".getBytes();

        String result = writer.writePath(outFile.toString(), content);
        assertTrue(Files.exists(outFile));
        assertEquals("<html>translated</html>", new String(Files.readAllBytes(outFile)));
        assertNotNull(result);
    }

    @Test
    void writePath_createsParentDirectories(@TempDir Path tempDir) throws IOException {
        Path outFile = tempDir.resolve("sub/dir/output.txt");
        byte[] content = "content".getBytes();

        String result = writer.writePath(outFile.toString(), content);
        assertTrue(Files.exists(outFile));
        assertEquals("content", new String(Files.readAllBytes(outFile)));
    }

    @Test
    void writeUri_fileUri_writesContent(@TempDir Path tempDir) throws IOException {
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
    void resolveUri_fileUri_returnsPath(@TempDir Path tempDir) throws IOException {
        Path outFile = tempDir.resolve("output.xml");
        String result = writer.resolveUri(outFile.toUri().toString());
        assertEquals(outFile.toFile().getAbsolutePath(), result);
    }

    @Test
    void resolveUri_createsParentDirectories(@TempDir Path tempDir) throws IOException {
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
