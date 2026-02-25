package com.gokapi.bridge.model;

import com.google.gson.annotations.SerializedName;

/**
 * Wire representation of an inline span (gokapi model.Span).
 */
public class SpanDTO {

    @SerializedName("span_type")
    private int spanType;

    @SerializedName("type")
    private String type;

    @SerializedName("id")
    private String id;

    @SerializedName("data")
    private String data;

    @SerializedName("outer_data")
    private String outerData;

    @SerializedName("deletable")
    private boolean deletable;

    @SerializedName("cloneable")
    private boolean cloneable;

    @SerializedName("original_id")
    private String originalId;

    @SerializedName("display_text")
    private String displayText;

    @SerializedName("flags")
    private int flags;

    @SerializedName("equiv_text")
    private String equivText;

    @SerializedName("can_reorder")
    private boolean canReorder;

    public int getSpanType() {
        return spanType;
    }

    public void setSpanType(int spanType) {
        this.spanType = spanType;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getData() {
        return data;
    }

    public void setData(String data) {
        this.data = data;
    }

    public String getOuterData() {
        return outerData;
    }

    public void setOuterData(String outerData) {
        this.outerData = outerData;
    }

    public boolean isDeletable() {
        return deletable;
    }

    public void setDeletable(boolean deletable) {
        this.deletable = deletable;
    }

    public boolean isCloneable() {
        return cloneable;
    }

    public void setCloneable(boolean cloneable) {
        this.cloneable = cloneable;
    }

    public String getOriginalId() {
        return originalId;
    }

    public void setOriginalId(String originalId) {
        this.originalId = originalId;
    }

    public String getDisplayText() {
        return displayText;
    }

    public void setDisplayText(String displayText) {
        this.displayText = displayText;
    }

    public int getFlags() {
        return flags;
    }

    public void setFlags(int flags) {
        this.flags = flags;
    }

    public String getEquivText() {
        return equivText;
    }

    public void setEquivText(String equivText) {
        this.equivText = equivText;
    }

    public boolean isCanReorder() {
        return canReorder;
    }

    public void setCanReorder(boolean canReorder) {
        this.canReorder = canReorder;
    }
}
