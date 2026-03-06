package com.gokapi.bridge.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class LocalContentResolverTest {

    private final LocalContentResolver resolver = new LocalContentResolver();

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
    void resolvePath_existingFile_returnsFile(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("test.html");
        Files.write(file, "<html>test</html>".getBytes());

        File result = resolver.resolvePath(file.toString());
        assertEquals(file.toFile().getAbsolutePath(), result.getAbsolutePath());
    }

    @Test
    void resolvePath_missingFile_throwsIOException() {
        assertThrows(IOException.class, () ->
                resolver.resolvePath("/nonexistent/path/file.txt"));
    }

    @Test
    void resolveUri_fileUri_returnsFile(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("doc.xml");
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
                resolver.resolveUri("file:///nonexistent/path.txt", ".txt"));
    }
}
