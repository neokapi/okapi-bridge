package com.gokapi.bridge.model;

/**
 * Wire representation of an annotation entry (gokapi model.Annotation).
 * Each entry has a type string and a JSON-encoded payload.
 */
public class AnnotationEntryDTO {

    private String type;
    private byte[] data;

    public AnnotationEntryDTO() {}

    public AnnotationEntryDTO(String type, byte[] data) {
        this.type = type;
        this.data = data;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public byte[] getData() {
        return data;
    }

    public void setData(byte[] data) {
        this.data = data;
    }
}
