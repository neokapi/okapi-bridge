package com.gokapi.bridge.tools;

import com.gokapi.bridge.model.FilterInfo;
import com.gokapi.bridge.util.FilterRegistry;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Generates base JSON Schema files for Okapi filters.
 * 
 * This tool introspects filter Parameters classes at build time using reflection,
 * then transforms the extracted parameter metadata into clean JSON Schema (draft-07).
 * 
 * Output is base schemas only - merging with overrides is done by bash/jq scripts.
 * 
 * Run via: mvn exec:java -Dexec.mainClass=com.gokapi.bridge.schema.SchemaGenerator
 */
public class SchemaGenerator {

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    private static final String SCHEMA_VERSION = "1.0.0";
    private static final String OUTPUT_DIR = "schemas";

    private final ParameterIntrospector introspector;
    private final SchemaTransformer transformer;
    private final JsonObject groupings;
    private final JsonObject commonDefs;

    public SchemaGenerator() {
        this.introspector = new ParameterIntrospector();
        this.transformer = new SchemaTransformer();
        this.groupings = loadClasspathJson("groupings.json");
        this.commonDefs = loadClasspathJson("common.defs.json");
    }

    private JsonObject loadClasspathJson(String resourceName) {
        InputStream is = getClass().getClassLoader().getResourceAsStream(resourceName);
        if (is == null) {
            System.err.println("Warning: " + resourceName + " not found on classpath, hierarchy disabled");
            return new JsonObject();
        }
        try (InputStreamReader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
            return GSON.fromJson(reader, JsonObject.class);
        } catch (IOException e) {
            System.err.println("Warning: Failed to load " + resourceName + ": " + e.getMessage());
            return new JsonObject();
        }
    }

    public static void main(String[] args) {
        SchemaGenerator generator = new SchemaGenerator();
        
        String outputDir = OUTPUT_DIR;
        if (args.length > 0) {
            outputDir = args[0];
        }

        try {
            generator.generateAll(outputDir);
        } catch (Exception e) {
            System.err.println("Schema generation failed: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    /**
     * Generate base schemas for all discovered filters.
     */
    public void generateAll(String outputDir) throws IOException {
        File dir = new File(outputDir);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("Failed to create output directory: " + outputDir);
        }

        List<FilterInfo> filters = FilterRegistry.listFilters();
        int successCount = 0;
        int failCount = 0;

        System.out.println("Generating base schemas for " + filters.size() + " filters...\n");

        for (FilterInfo info : filters) {
            try {
                JsonObject schema = generateBaseSchema(info);
                String filterId = "okf_" + info.getName();
                String filename = filterId + ".schema.json";
                File outputFile = new File(dir, filename);
                
                try (FileWriter writer = new FileWriter(outputFile, StandardCharsets.UTF_8)) {
                    GSON.toJson(schema, writer);
                }
                
                int paramCount = schema.getAsJsonObject("properties").size();
                System.out.println("✓ " + filterId + " (" + paramCount + " params)");
                successCount++;
            } catch (Exception e) {
                System.err.println("✗ " + info.getName() + " → " + e.getMessage());
                failCount++;
            }
        }

        System.out.println("\nGeneration complete: " + successCount + " schemas, " + failCount + " failures");
        generateMetaFile(dir, filters);
    }

    /**
     * Generate a base schema for a single filter (no override merging).
     */
    public JsonObject generateBaseSchema(FilterInfo info) {
        String filterClass = info.getFilterClass();
        String filterId = "okf_" + info.getName();

        JsonObject schema = new JsonObject();
        schema.addProperty("$schema", "http://json-schema.org/draft-07/schema#");
        schema.addProperty("$id", "https://gokapi.dev/schemas/filters/" + filterId + ".schema.json");
        schema.addProperty("$version", SCHEMA_VERSION);
        schema.addProperty("title", info.getDisplayName() + " Filter");
        schema.addProperty("description", "Configuration for the Okapi " + info.getDisplayName() + " Filter");
        schema.addProperty("type", "object");

        JsonObject filterMeta = new JsonObject();
        filterMeta.addProperty("id", filterId);
        filterMeta.addProperty("class", filterClass);
        filterMeta.add("extensions", GSON.toJsonTree(info.getExtensions()));
        filterMeta.add("mimeTypes", GSON.toJsonTree(info.getMimeTypes()));
        
        // Include filter configurations (presets/variants)
        if (info.getConfigurations() != null && !info.getConfigurations().isEmpty()) {
            filterMeta.add("configurations", GSON.toJsonTree(info.getConfigurations()));
        }
        
        // Include serialization format
        filterMeta.addProperty("serializationFormat", introspector.getSerializationFormat(filterClass));
        
        schema.add("x-filter", filterMeta);

        Map<String, ParameterIntrospector.ParamInfo> params = introspector.introspect(filterClass);

        JsonObject properties = new JsonObject();
        boolean needsYamlDefs = false;
        if (params != null) {
            for (Map.Entry<String, ParameterIntrospector.ParamInfo> entry : params.entrySet()) {
                String paramName = entry.getKey();
                ParameterIntrospector.ParamInfo paramInfo = entry.getValue();
                
                if ("elementRules".equals(paramInfo.okapiFormat) || "attributeRules".equals(paramInfo.okapiFormat)) {
                    needsYamlDefs = true;
                }
                
                JsonObject propSchema = transformer.transformParameter(paramName, paramInfo);
                if (propSchema != null) {
                    properties.add(paramName, propSchema);
                }
            }
        }
        schema.add("properties", properties);
        schema.addProperty("additionalProperties", false);
        
        // Add $defs for YAML-config filters that use element/attribute rules
        if (needsYamlDefs) {
            schema.add("$defs", transformer.generateYamlConfigDefs());
        }

        // Restructure flat properties into hierarchy using groupings
        schema = transformer.restructureIntoHierarchy(schema, filterId, groupings, commonDefs);

        return schema;
    }

    /**
     * Generate meta.json with filter metadata.
     */
    private void generateMetaFile(File dir, List<FilterInfo> filters) throws IOException {
        File metaFile = new File(dir, "meta.json");
        
        JsonObject meta = new JsonObject();
        meta.addProperty("schemaVersion", SCHEMA_VERSION);
        meta.addProperty("generatedAt", java.time.Instant.now().toString());
        meta.addProperty("filterCount", filters.size());

        JsonObject filtersObj = new JsonObject();
        for (FilterInfo info : filters) {
            String filterId = "okf_" + info.getName();
            JsonObject filterMeta = new JsonObject();
            filterMeta.addProperty("class", info.getFilterClass());
            filterMeta.addProperty("displayName", info.getDisplayName());
            filterMeta.add("extensions", GSON.toJsonTree(info.getExtensions()));
            filterMeta.add("mimeTypes", GSON.toJsonTree(info.getMimeTypes()));
            
            // Include filter configurations
            if (info.getConfigurations() != null && !info.getConfigurations().isEmpty()) {
                filterMeta.add("configurations", GSON.toJsonTree(info.getConfigurations()));
            }
            filtersObj.add(filterId, filterMeta);
        }
        meta.add("filters", filtersObj);

        try (FileWriter writer = new FileWriter(metaFile, StandardCharsets.UTF_8)) {
            GSON.toJson(meta, writer);
        }
        
        System.out.println("Generated meta.json");
    }
}
