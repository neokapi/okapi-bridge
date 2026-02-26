package com.gokapi.bridge.model;

import com.google.gson.annotations.SerializedName;
import java.util.Map;

/**
 * Wire representation of a segment (gokapi model.Segment).
 */
public class SegmentDTO {

    @SerializedName("id")
    private String id;

    @SerializedName("content")
    private FragmentDTO content;

    @SerializedName("properties")
    private Map<String, String> properties;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public FragmentDTO getContent() {
        return content;
    }

    public void setContent(FragmentDTO content) {
        this.content = content;
    }

    public Map<String, String> getProperties() {
        return properties;
    }

    public void setProperties(Map<String, String> properties) {
        this.properties = properties;
    }
}
