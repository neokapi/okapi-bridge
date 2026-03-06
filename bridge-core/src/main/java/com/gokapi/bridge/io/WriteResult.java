package com.gokapi.bridge.io;

/**
 * Result of a write (roundtrip) operation.
 * Contains either inline output bytes or a path/URI where output was written.
 */
public class WriteResult {

    private final byte[] bytes;
    private final String outputPath;

    private WriteResult(byte[] bytes, String outputPath) {
        this.bytes = bytes;
        this.outputPath = outputPath;
    }

    /** Create a result with inline output bytes. */
    public static WriteResult ofBytes(byte[] bytes) {
        return new WriteResult(bytes, null);
    }

    /** Create a result with an output path/URI. */
    public static WriteResult ofPath(String outputPath) {
        return new WriteResult(null, outputPath);
    }

    /** Returns the inline output bytes, or null if output was written to a path. */
    public byte[] getBytes() {
        return bytes;
    }

    /** Returns the output path/URI, or null if output is inline. */
    public String getOutputPath() {
        return outputPath;
    }

    /** Returns true if output was written to a referenced path/URI. */
    public boolean isReferenced() {
        return outputPath != null;
    }
}
