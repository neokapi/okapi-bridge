package com.gokapi.bridge.model;

import com.google.gson.annotations.SerializedName;
import java.util.ArrayList;
import java.util.List;

/**
 * Metadata about an Okapi filter, returned by the info and list_filters commands.
 */
public class FilterInfo {

    @SerializedName("filter_class")
    private String filterClass;

    @SerializedName("id")
    private String id;

    @SerializedName("name")
    private String name;

    @SerializedName("display_name")
    private String displayName;

    @SerializedName("mime_types")
    private List<String> mimeTypes;

    @SerializedName("extensions")
    private List<String> extensions;

    @SerializedName("configurations")
    private List<FilterConfigurationInfo> configurations;

    @SerializedName("capabilities")
    private List<String> capabilities;

    public FilterInfo() {
        this.configurations = new ArrayList<>();
    }

    public FilterInfo(String filterClass, String id, String name, String displayName,
                      List<String> mimeTypes, List<String> extensions) {
        this.filterClass = filterClass;
        this.id = id;
        this.name = name;
        this.displayName = displayName;
        this.mimeTypes = mimeTypes;
        this.extensions = extensions;
        this.configurations = new ArrayList<>();
    }

    public String getFilterClass() {
        return filterClass;
    }

    public void setFilterClass(String filterClass) {
        this.filterClass = filterClass;
    }

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

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public List<String> getMimeTypes() {
        return mimeTypes;
    }

    public void setMimeTypes(List<String> mimeTypes) {
        this.mimeTypes = mimeTypes;
    }

    public List<String> getExtensions() {
        return extensions;
    }

    public void setExtensions(List<String> extensions) {
        this.extensions = extensions;
    }

    public List<FilterConfigurationInfo> getConfigurations() {
        return configurations;
    }

    public void setConfigurations(List<FilterConfigurationInfo> configurations) {
        this.configurations = configurations;
    }

    public void addConfiguration(FilterConfigurationInfo config) {
        if (this.configurations == null) {
            this.configurations = new ArrayList<>();
        }
        this.configurations.add(config);
    }

    public List<String> getCapabilities() {
        return capabilities;
    }

    public void setCapabilities(List<String> capabilities) {
        this.capabilities = capabilities;
    }
}
