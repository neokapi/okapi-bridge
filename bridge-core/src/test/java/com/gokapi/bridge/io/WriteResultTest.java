package com.gokapi.bridge.io;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WriteResultTest {

    @Test
    void ofBytes_returnsInlineResult() {
        byte[] data = "output".getBytes();
        WriteResult result = WriteResult.ofBytes(data);

        assertFalse(result.isReferenced());
        assertArrayEquals(data, result.getBytes());
        assertNull(result.getOutputPath());
    }

    @Test
    void ofPath_returnsReferencedResult() {
        WriteResult result = WriteResult.ofPath("/tmp/output.html");

        assertTrue(result.isReferenced());
        assertEquals("/tmp/output.html", result.getOutputPath());
        assertNull(result.getBytes());
    }
}
