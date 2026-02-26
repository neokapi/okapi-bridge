package com.gokapi.bridge.model;

import com.google.gson.annotations.SerializedName;
import java.util.Map;

/**
 * Metadata about a filter configuration (a specific preset/variant of a filter).
 * Filters can have multiple configurations with different default parameters.
 * 
 * For compound filters (like TableFilter), each configuration may be handled by
 * a different sibling filter. The `filterClass` field indicates which filter
 * actually processes the configuration, and `schemaRef` points to its schema.
 */
public class FilterConfigurationInfo {

    @SerializedName("configId")
    private String configId;

    @SerializedName("name")
    private String name;

    @SerializedName("description")
    private String description;

    @SerializedName("mimeType")
    private String mimeType;

    @SerializedName("extensions")
    private String extensions;

    @SerializedName("parametersLocation")
    private String parametersLocation;

    @SerializedName("parametersRaw")
    private String parametersRaw;

    @SerializedName("parameters")
    private Map<String, Object> parameters;

    @SerializedName("isDefault")
    private boolean isDefault;
    
    /**
     * For compound filters, the actual filter class that handles this configuration.
     * For regular filters, this matches the parent filter class.
     */
    @SerializedName("filterClass")
    private String filterClass;
    
    /**
     * Reference to the schema file for the filter that handles this configuration.
     * E.g., "okf_commaseparatedvalues.schema.json" for a CSV configuration in TableFilter.
     */
    @SerializedName("schemaRef")
    private String schemaRef;

    public FilterConfigurationInfo() {
    }

    public FilterConfigurationInfo(String configId, String name, String description,
                                   String mimeType, String extensions, 
                                   String parametersLocation, boolean isDefault) {
        this.configId = configId;
        this.name = name;
        this.description = description;
        this.mimeType = mimeType;
        this.extensions = extensions;
        this.parametersLocation = parametersLocation;
        this.isDefault = isDefault;
    }

    public String getConfigId() {
        return configId;
    }

    public void setConfigId(String configId) {
        this.configId = configId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getMimeType() {
        return mimeType;
    }

    public void setMimeType(String mimeType) {
        this.mimeType = mimeType;
    }

    public String getExtensions() {
        return extensions;
    }

    public void setExtensions(String extensions) {
        this.extensions = extensions;
    }

    public String getParametersLocation() {
        return parametersLocation;
    }

    public void setParametersLocation(String parametersLocation) {
        this.parametersLocation = parametersLocation;
    }

    public String getParametersRaw() {
        return parametersRaw;
    }

    public void setParametersRaw(String parametersRaw) {
        this.parametersRaw = parametersRaw;
    }

    public Map<String, Object> getParameters() {
        return parameters;
    }

    public void setParameters(Map<String, Object> parameters) {
        this.parameters = parameters;
    }

    public boolean isDefault() {
        return isDefault;
    }

    public void setDefault(boolean isDefault) {
        this.isDefault = isDefault;
    }
    
    public String getFilterClass() {
        return filterClass;
    }
    
    public void setFilterClass(String filterClass) {
        this.filterClass = filterClass;
    }
    
    public String getSchemaRef() {
        return schemaRef;
    }
    
    public void setSchemaRef(String schemaRef) {
        this.schemaRef = schemaRef;
    }
}
