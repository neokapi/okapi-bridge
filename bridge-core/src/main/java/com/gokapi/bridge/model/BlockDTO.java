package com.gokapi.bridge.model;

import com.google.gson.annotations.SerializedName;
import java.util.List;
import java.util.Map;

/**
 * Wire representation of a translatable block (gokapi model.Block).
 */
public class BlockDTO {

    @SerializedName("id")
    private String id;

    @SerializedName("name")
    private String name;

    @SerializedName("type")
    private String type;

    @SerializedName("mime_type")
    private String mimeType;

    @SerializedName("translatable")
    private boolean translatable;

    @SerializedName("source")
    private List<SegmentDTO> source;

    @SerializedName("targets")
    private List<TargetDTO> targets;

    @SerializedName("properties")
    private Map<String, String> properties;

    @SerializedName("annotations")
    private Map<String, AnnotationEntryDTO> annotations;

    @SerializedName("display_hint")
    private DisplayHintDTO displayHint;

    @SerializedName("skeleton")
    private SkeletonDTO skeleton;

    @SerializedName("preserve_whitespace")
    private boolean preserveWhitespace;

    @SerializedName("is_referent")
    private boolean isReferent;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getMimeType() {
        return mimeType;
    }

    public void setMimeType(String mimeType) {
        this.mimeType = mimeType;
    }

    public boolean isTranslatable() {
        return translatable;
    }

    public void setTranslatable(boolean translatable) {
        this.translatable = translatable;
    }

    public List<SegmentDTO> getSource() {
        return source;
    }

    public void setSource(List<SegmentDTO> source) {
        this.source = source;
    }

    public List<TargetDTO> getTargets() {
        return targets;
    }

    public void setTargets(List<TargetDTO> targets) {
        this.targets = targets;
    }

    public Map<String, String> getProperties() {
        return properties;
    }

    public void setProperties(Map<String, String> properties) {
        this.properties = properties;
    }

    public Map<String, AnnotationEntryDTO> getAnnotations() {
        return annotations;
    }

    public void setAnnotations(Map<String, AnnotationEntryDTO> annotations) {
        this.annotations = annotations;
    }

    public DisplayHintDTO getDisplayHint() {
        return displayHint;
    }

    public void setDisplayHint(DisplayHintDTO displayHint) {
        this.displayHint = displayHint;
    }

    public SkeletonDTO getSkeleton() {
        return skeleton;
    }

    public void setSkeleton(SkeletonDTO skeleton) {
        this.skeleton = skeleton;
    }

    public boolean isPreserveWhitespace() {
        return preserveWhitespace;
    }

    public void setPreserveWhitespace(boolean preserveWhitespace) {
        this.preserveWhitespace = preserveWhitespace;
    }

    public boolean isReferent() {
        return isReferent;
    }

    public void setReferent(boolean referent) {
        isReferent = referent;
    }
}
