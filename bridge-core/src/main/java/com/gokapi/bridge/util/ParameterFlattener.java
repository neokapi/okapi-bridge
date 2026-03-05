package com.gokapi.bridge.util;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.HashMap;
import java.util.Map;

/**
 * Schema-aware flattener that transforms hierarchical filter configs
 * (with clean property names) back to flat Okapi parameter maps.
 *
 * Uses x-flattenPath annotations from the JSON Schema to map
 * nested paths like "extraction.extractAll" to Okapi names like "extractAllPairs".
 *
 * Backwards-compatible: flat input (no nested objects) passes through unchanged.
 */
public class ParameterFlattener {

    private final Map<String, String> flattenMap;

    /**
     * Build a flattener from a JSON Schema that contains x-flattenPath annotations.
     *
     * @param schema the composite JSON Schema for a filter
     */
    public ParameterFlattener(JsonObject schema) {
        this.flattenMap = buildFlattenMapWithRefs(schema);
    }

    /**
     * Flatten hierarchical config to flat Okapi params.
     * If the input is already flat, keys pass through using flattenMap fallback.
     *
     * @param hierarchical the config object (may be hierarchical or flat)
     * @return flat JsonObject with Okapi parameter names as keys
     */
    public JsonObject flatten(JsonObject hierarchical) {
        JsonObject flat = new JsonObject();
        flattenRecursive(hierarchical, "", flat);
        return flat;
    }

    /**
     * Returns the internal flatten map (for testing/debugging).
     */
    public Map<String, String> getFlattenMap() {
        return new HashMap<>(flattenMap);
    }

    private void flattenRecursive(JsonObject obj, String prefix, JsonObject flat) {
        for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
            String path = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            JsonElement value = entry.getValue();

            if (value.isJsonObject() && isOrganizationalGroup(path)) {
                flattenRecursive(value.getAsJsonObject(), path, flat);
            } else {
                String okapiName = flattenMap.getOrDefault(path, entry.getKey());
                flat.add(okapiName, value);
            }
        }
    }

    private boolean isOrganizationalGroup(String path) {
        for (String key : flattenMap.keySet()) {
            if (key.startsWith(path + ".")) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, String> buildFlattenMap(JsonObject schema) {
        Map<String, String> map = new HashMap<>();
        JsonObject props = schema.getAsJsonObject("properties");
        if (props != null) {
            walkProperties(props, "", map);
        }
        // Also walk $defs for referenced definitions (e.g., inlineCodes, whitespace)
        JsonObject defs = schema.getAsJsonObject("$defs");
        if (defs != null) {
            walkDefs(defs, map);
        }
        return map;
    }

    private static void walkProperties(JsonObject props, String prefix, Map<String, String> map) {
        for (Map.Entry<String, JsonElement> entry : props.entrySet()) {
            String path = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            if (!entry.getValue().isJsonObject()) {
                continue;
            }
            JsonObject prop = entry.getValue().getAsJsonObject();

            if (prop.has("x-flattenPath")) {
                map.put(path, prop.get("x-flattenPath").getAsString());
            }
            if (prop.has("properties")) {
                walkProperties(prop.getAsJsonObject("properties"), path, map);
            }
        }
    }

    /**
     * Walk $defs to find x-flattenPath on properties of shared definitions.
     * When a top-level property uses $ref to a def (e.g., "inlineCodes": {"$ref": "#/$defs/inlineCodes"}),
     * the flatten paths within that def need to be mapped using the referencing property name as prefix.
     *
     * We handle this by also scanning the top-level properties for $ref, resolving the def,
     * and prefixing the def's paths with the referencing property name.
     */
    private static void walkDefs(JsonObject defs, Map<String, String> map) {
        // Defs are walked when referenced from properties via $ref.
        // The actual path resolution happens in resolveRefs during buildFlattenMap.
        // Here we just make defs available for lookup.
    }

    /**
     * Build flatten map with $ref resolution.
     * This is called from the main buildFlattenMap method.
     */
    static Map<String, String> buildFlattenMapWithRefs(JsonObject schema) {
        Map<String, String> map = new HashMap<>();
        JsonObject props = schema.getAsJsonObject("properties");
        JsonObject defs = schema.getAsJsonObject("$defs");

        if (props != null) {
            walkPropertiesWithRefs(props, "", defs, map);
        }
        return map;
    }

    private static void walkPropertiesWithRefs(JsonObject props, String prefix,
                                                JsonObject defs, Map<String, String> map) {
        for (Map.Entry<String, JsonElement> entry : props.entrySet()) {
            String path = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            if (!entry.getValue().isJsonObject()) {
                continue;
            }
            JsonObject prop = entry.getValue().getAsJsonObject();

            if (prop.has("x-flattenPath")) {
                map.put(path, prop.get("x-flattenPath").getAsString());
            }

            // Resolve $ref to inline the referenced definition's properties
            if (prop.has("$ref") && defs != null) {
                String ref = prop.get("$ref").getAsString();
                // Parse "#/$defs/inlineCodes" -> "inlineCodes"
                if (ref.startsWith("#/$defs/")) {
                    String defName = ref.substring("#/$defs/".length());
                    JsonObject def = defs.getAsJsonObject(defName);
                    if (def != null && def.has("properties")) {
                        walkPropertiesWithRefs(def.getAsJsonObject("properties"), path, defs, map);
                    }
                }
            }

            if (prop.has("properties")) {
                walkPropertiesWithRefs(prop.getAsJsonObject("properties"), path, defs, map);
            }
        }
    }
}
