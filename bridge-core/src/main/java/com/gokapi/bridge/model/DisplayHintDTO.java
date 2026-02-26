package com.gokapi.bridge.model;

/**
 * Wire representation of display hints for a block (gokapi model.DisplayHint).
 * Provides UI rendering guidance.
 */
public class DisplayHintDTO {

    private int maxLength;
    private String contentType;
    private String context;
    private String preview;

    public int getMaxLength() { return maxLength; }
    public void setMaxLength(int maxLength) { this.maxLength = maxLength; }

    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }

    public String getContext() { return context; }
    public void setContext(String context) { this.context = context; }

    public String getPreview() { return preview; }
    public void setPreview(String preview) { this.preview = preview; }
}
