package com.gokapi.bridge.model;

/**
 * Wire representation of a skeleton part — either literal text or a resource reference.
 */
public class SkeletonPartDTO {

    private String text;
    private String resourceId;
    private String property;
    private String locale;

    public SkeletonPartDTO() {}

    /** Create a text skeleton part. */
    public static SkeletonPartDTO text(String text) {
        SkeletonPartDTO p = new SkeletonPartDTO();
        p.text = text;
        return p;
    }

    /** Create a resource reference skeleton part. */
    public static SkeletonPartDTO ref(String resourceId, String property, String locale) {
        SkeletonPartDTO p = new SkeletonPartDTO();
        p.resourceId = resourceId;
        p.property = property;
        p.locale = locale;
        return p;
    }

    public boolean isReference() {
        return resourceId != null && !resourceId.isEmpty();
    }

    public String getText() { return text; }
    public void setText(String text) { this.text = text; }

    public String getResourceId() { return resourceId; }
    public void setResourceId(String resourceId) { this.resourceId = resourceId; }

    public String getProperty() { return property; }
    public void setProperty(String property) { this.property = property; }

    public String getLocale() { return locale; }
    public void setLocale(String locale) { this.locale = locale; }
}
