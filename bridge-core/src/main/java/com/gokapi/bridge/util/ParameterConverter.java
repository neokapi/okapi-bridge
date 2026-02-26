package com.gokapi.bridge.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Converts clean gokapi JSON parameter format to Okapi's internal format at runtime.
 * 
 * This class handles the transformation of user-friendly JSON structures into
 * the formats expected by Okapi filter Parameters classes.
 * 
 * Key conversions:
 * - codeFinderRules: { rules: [...], sample: "..." } → "#v1\ncount.i=N\nrule0=...\nsample=..."
 * - simplifierRules: structured object → Okapi SimplifierRules format
 */
public class ParameterConverter {

    /**
     * Convert codeFinderRules from clean JSON format to Okapi internal string format.
     * 
     * Input (clean JSON):
     * {
     *   "rules": [
     *     { "pattern": "<[^>]+>" },
     *     { "pattern": "\\{\\d+\\}" }
     *   ],
     *   "sample": "<b>text</b> {0}",
     *   "useAllRulesWhenTesting": true
     * }
     * 
     * Output (Okapi format):
     * #v1
     * count.i=2
     * rule0=<[^>]+>
     * rule1=\{\d+\}
     * sample=<b>text</b> {0}
     * useAllRulesWhenTesting.b=true
     * 
     * @param codeFinderRules JSON object with rules array
     * @return Okapi internal string format
     */
    public static String convertCodeFinderRules(JsonObject codeFinderRules) {
        if (codeFinderRules == null) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("#v1\n");

        // Convert rules array
        JsonArray rules = codeFinderRules.getAsJsonArray("rules");
        if (rules != null) {
            sb.append("count.i=").append(rules.size()).append("\n");
            for (int i = 0; i < rules.size(); i++) {
                JsonElement rule = rules.get(i);
                if (rule.isJsonObject()) {
                    JsonObject ruleObj = rule.getAsJsonObject();
                    if (ruleObj.has("pattern")) {
                        sb.append("rule").append(i).append("=")
                          .append(ruleObj.get("pattern").getAsString()).append("\n");
                    }
                } else if (rule.isJsonPrimitive()) {
                    // Support simple string array format too
                    sb.append("rule").append(i).append("=")
                      .append(rule.getAsString()).append("\n");
                }
            }
        } else {
            sb.append("count.i=0\n");
        }

        // Convert sample
        if (codeFinderRules.has("sample")) {
            sb.append("sample=").append(codeFinderRules.get("sample").getAsString()).append("\n");
        }

        // Convert useAllRulesWhenTesting
        if (codeFinderRules.has("useAllRulesWhenTesting")) {
            sb.append("useAllRulesWhenTesting.b=")
              .append(codeFinderRules.get("useAllRulesWhenTesting").getAsBoolean()).append("\n");
        }

        return sb.toString();
    }

    /**
     * Convert codeFinderRules from Okapi internal string format to clean JSON.
     * This is the reverse of convertCodeFinderRules().
     * 
     * @param okapiFormat Okapi internal string format
     * @return Clean JSON object
     */
    public static JsonObject parseCodeFinderRules(String okapiFormat) {
        if (okapiFormat == null || okapiFormat.isEmpty()) {
            return null;
        }

        JsonObject result = new JsonObject();
        JsonArray rules = new JsonArray();
        
        String[] lines = okapiFormat.split("\n");
        for (String line : lines) {
            line = line.trim();
            if (line.startsWith("#v1") || line.isEmpty()) {
                continue;
            }
            
            int eqIdx = line.indexOf('=');
            if (eqIdx < 0) continue;
            
            String key = line.substring(0, eqIdx);
            String value = line.substring(eqIdx + 1);
            
            if (key.startsWith("rule") && !key.equals("rules")) {
                JsonObject rule = new JsonObject();
                rule.addProperty("pattern", value);
                rules.add(rule);
            } else if (key.equals("sample")) {
                result.addProperty("sample", value);
            } else if (key.equals("useAllRulesWhenTesting.b")) {
                result.addProperty("useAllRulesWhenTesting", Boolean.parseBoolean(value));
            }
        }
        
        result.add("rules", rules);
        return result;
    }

    /**
     * Convert a parameter value based on its type and format.
     * 
     * @param paramName Parameter name
     * @param value Parameter value (from JSON)
     * @param okapiFormat Optional format hint (e.g., "inlineCodeFinder")
     * @return Converted value suitable for Okapi Parameters
     */
    public static Object convertParameterValue(String paramName, JsonElement value, String okapiFormat) {
        if (value == null || value.isJsonNull()) {
            return null;
        }

        // Handle special formats
        if ("inlineCodeFinder".equals(okapiFormat) && value.isJsonObject()) {
            return convertCodeFinderRules(value.getAsJsonObject());
        }
        
        // Handle codeFinderRules by name
        if ("codeFinderRules".equals(paramName) && value.isJsonObject()) {
            return convertCodeFinderRules(value.getAsJsonObject());
        }

        // Handle primitive types
        if (value.isJsonPrimitive()) {
            if (value.getAsJsonPrimitive().isBoolean()) {
                return value.getAsBoolean();
            } else if (value.getAsJsonPrimitive().isNumber()) {
                // Determine if int or double
                double d = value.getAsDouble();
                if (d == Math.floor(d) && !Double.isInfinite(d)) {
                    return value.getAsInt();
                }
                return d;
            } else {
                return value.getAsString();
            }
        }

        // For objects and arrays, return as-is (will be handled by specific converters)
        return value;
    }

    /**
     * Convert all filter parameters from clean JSON to Okapi format.
     * 
     * @param filterParams JSON object containing filter parameters
     * @param schema Optional schema with format hints
     * @return Converted parameters suitable for applying to IParameters
     */
    public static JsonObject convertAllParameters(JsonObject filterParams, JsonObject schema) {
        if (filterParams == null) {
            return null;
        }

        JsonObject result = new JsonObject();
        
        // Get schema properties for format hints
        JsonObject properties = null;
        if (schema != null && schema.has("properties")) {
            properties = schema.getAsJsonObject("properties");
        }

        for (String key : filterParams.keySet()) {
            JsonElement value = filterParams.get(key);
            
            // Check for format hint in schema
            String okapiFormat = null;
            if (properties != null && properties.has(key)) {
                JsonObject propSchema = properties.getAsJsonObject(key);
                if (propSchema.has("x-okapiFormat")) {
                    okapiFormat = propSchema.get("x-okapiFormat").getAsString();
                }
            }
            
            Object converted = convertParameterValue(key, value, okapiFormat);
            
            // Add to result based on type
            if (converted instanceof String) {
                result.addProperty(key, (String) converted);
            } else if (converted instanceof Boolean) {
                result.addProperty(key, (Boolean) converted);
            } else if (converted instanceof Number) {
                result.addProperty(key, (Number) converted);
            } else if (converted instanceof JsonElement) {
                result.add(key, (JsonElement) converted);
            }
        }

        return result;
    }
}
