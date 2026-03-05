package com.gokapi.bridge.tools;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Transforms parameter metadata into clean JSON Schema format and merges editor hints.
 * 
 * This class handles:
 * 1. Converting complex Okapi internal formats (like InlineCodeFinder) to clean JSON
 * 2. Merging manually-curated editor hints (groupings, widgets, presets)
 * 3. Adding x-extension properties for UI generation
 */
public class SchemaTransformer {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    /**
     * Transform a single parameter into JSON Schema property format.
     * 
     * @param paramName The parameter name
     * @param paramInfo The introspected parameter information
     * @return JSON Schema property object
     */
    public JsonObject transformParameter(String paramName, ParameterIntrospector.ParamInfo paramInfo) {
        JsonObject prop = new JsonObject();
        
        // Handle special Okapi formats
        if ("inlineCodeFinder".equals(paramInfo.okapiFormat)) {
            return transformCodeFinderRules();
        }
        
        if ("elementRules".equals(paramInfo.okapiFormat)) {
            return transformElementRules(paramInfo);
        }
        
        if ("attributeRules".equals(paramInfo.okapiFormat)) {
            return transformAttributeRules(paramInfo);
        }
        
        if ("simplifierRules".equals(paramName)) {
            return transformSimplifierRules();
        }
        
        // Skip subfilter parameter - handled via gokapi Layers
        if ("subfilter".equals(paramName)) {
            return null;
        }
        
        // Skip UI-only elements (separators, labels with UUID names)
        if ("separator".equals(paramInfo.widget) || "label".equals(paramInfo.widget)) {
            return null;
        }
        // Also skip parameters with UUID-like names (UI separators)
        if (paramName.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            return null;
        }

        // Basic type mapping
        prop.addProperty("type", paramInfo.type);
        
        // Add default value
        if (paramInfo.defaultValue != null) {
            addDefaultValue(prop, paramInfo.type, paramInfo.defaultValue);
        }
        
        // Add title (displayName) if available
        if (paramInfo.displayName != null && !paramInfo.displayName.isEmpty()) {
            prop.addProperty("title", paramInfo.displayName);
        }
        
        // Add description
        if (paramInfo.description != null && !paramInfo.description.isEmpty()) {
            prop.addProperty("description", paramInfo.description);
        }
        
        // Add enum constraint if applicable
        if (paramInfo.enumValues != null && !paramInfo.enumValues.isEmpty()) {
            JsonArray enumArray = new JsonArray();
            for (String val : paramInfo.enumValues) {
                enumArray.add(val);
            }
            prop.add("enum", enumArray);
            
            // Add enum labels as x-enumLabels if different from values
            if (paramInfo.enumLabels != null && paramInfo.enumLabels.length == paramInfo.enumValues.size()) {
                JsonArray labelsArray = new JsonArray();
                for (String label : paramInfo.enumLabels) {
                    labelsArray.add(label);
                }
                prop.add("x-enumLabels", labelsArray);
            }
        }
        
        // Add numeric constraints for integer types
        if ("integer".equals(paramInfo.type)) {
            if (paramInfo.minimum != null) {
                prop.addProperty("minimum", paramInfo.minimum);
            }
            if (paramInfo.maximum != null) {
                prop.addProperty("maximum", paramInfo.maximum);
            }
        }
        
        // Mark deprecated
        if (paramInfo.deprecated) {
            prop.addProperty("deprecated", true);
        }
        
        // Add widget hint from EditorDescription
        if (paramInfo.widget != null) {
            prop.addProperty("x-widget", paramInfo.widget);
        }
        
        // Add master/slave relationship
        if (paramInfo.masterParam != null) {
            JsonObject dependency = new JsonObject();
            dependency.addProperty("parameter", paramInfo.masterParam);
            dependency.addProperty("enabledWhenSelected", paramInfo.enabledOnMasterSelected);
            prop.add("x-enabledBy", dependency);
        }
        
        return prop;
    }

    /**
     * Transform InlineCodeFinder to clean object schema.
     * 
     * Okapi internal format: "#v1\ncount.i=2\nrule0=<[^>]+>\nrule1=\\{\\d+\\}\nsample=..."
     * Clean gokapi format: { "rules": [{ "pattern": "..." }], "sample": "...", "useAllRulesWhenTesting": true }
     */
    private JsonObject transformCodeFinderRules() {
        JsonObject prop = new JsonObject();
        prop.addProperty("type", "object");
        prop.addProperty("description", "Inline code detection configuration");
        
        JsonObject properties = new JsonObject();
        
        // rules array
        JsonObject rulesArray = new JsonObject();
        rulesArray.addProperty("type", "array");
        rulesArray.addProperty("description", "List of regex patterns to detect inline codes");
        
        JsonObject ruleItem = new JsonObject();
        ruleItem.addProperty("type", "object");
        
        JsonObject ruleProps = new JsonObject();
        JsonObject patternProp = new JsonObject();
        patternProp.addProperty("type", "string");
        patternProp.addProperty("description", "Regex pattern for inline code detection");
        ruleProps.add("pattern", patternProp);
        ruleItem.add("properties", ruleProps);
        
        JsonArray required = new JsonArray();
        required.add("pattern");
        ruleItem.add("required", required);
        
        rulesArray.add("items", ruleItem);
        properties.add("rules", rulesArray);
        
        // sample
        JsonObject sampleProp = new JsonObject();
        sampleProp.addProperty("type", "string");
        sampleProp.addProperty("description", "Sample text to test patterns against");
        properties.add("sample", sampleProp);
        
        // useAllRulesWhenTesting
        JsonObject useAllProp = new JsonObject();
        useAllProp.addProperty("type", "boolean");
        useAllProp.addProperty("default", true);
        useAllProp.addProperty("description", "Test all rules together or individually");
        properties.add("useAllRulesWhenTesting", useAllProp);
        
        prop.add("properties", properties);
        prop.addProperty("x-okapiFormat", "inlineCodeFinder");
        
        return prop;
    }

    /**
     * Transform SimplifierRules to clean schema.
     */
    private JsonObject transformSimplifierRules() {
        JsonObject prop = new JsonObject();
        prop.addProperty("type", "string");
        prop.addProperty("description", "Simplifier rules for code type normalization");
        prop.addProperty("x-widget", "simplifierRulesEditor");
        return prop;
    }

    /**
     * Transform element rules map to a schema with $ref to $defs/elementRule.
     */
    private JsonObject transformElementRules(ParameterIntrospector.ParamInfo paramInfo) {
        JsonObject prop = new JsonObject();
        prop.addProperty("type", "object");
        prop.addProperty("description", "Element extraction rules — maps element names to their rule configuration");
        prop.addProperty("x-widget", "elementRulesEditor");
        prop.add("additionalProperties", createRef("#/$defs/elementRule"));
        if (paramInfo.defaultValue != null) {
            prop.add("default", GSON.toJsonTree(paramInfo.defaultValue));
        }
        return prop;
    }

    /**
     * Transform attribute rules map to a schema with $ref to $defs/attributeRule.
     */
    private JsonObject transformAttributeRules(ParameterIntrospector.ParamInfo paramInfo) {
        JsonObject prop = new JsonObject();
        prop.addProperty("type", "object");
        prop.addProperty("description", "Global attribute extraction rules — maps attribute names to their rule configuration");
        prop.addProperty("x-widget", "attributeRulesEditor");
        prop.add("additionalProperties", createRef("#/$defs/attributeRule"));
        if (paramInfo.defaultValue != null) {
            prop.add("default", GSON.toJsonTree(paramInfo.defaultValue));
        }
        return prop;
    }

    private JsonObject createRef(String ref) {
        JsonObject obj = new JsonObject();
        obj.addProperty("$ref", ref);
        return obj;
    }

    /**
     * Generate $defs for element and attribute rule sub-schemas.
     * Called by SchemaGenerator for YAML-based filters.
     */
    public JsonObject generateYamlConfigDefs() {
        JsonObject defs = new JsonObject();
        defs.add("elementRule", generateElementRuleDef());
        defs.add("attributeRule", generateAttributeRuleDef());
        defs.add("conditionalAttributeValue", generateConditionalAttributeValueDef());
        return defs;
    }

    private JsonObject generateElementRuleDef() {
        JsonObject def = new JsonObject();
        def.addProperty("type", "object");
        def.addProperty("description", "Extraction rule for an HTML/XML element");

        JsonObject properties = new JsonObject();

        // ruleTypes - array of RULE_TYPE enum values
        JsonObject ruleTypes = new JsonObject();
        ruleTypes.addProperty("type", "array");
        ruleTypes.addProperty("description", "Extraction rule types for this element");
        JsonObject ruleTypeItems = new JsonObject();
        ruleTypeItems.addProperty("type", "string");
        JsonArray ruleTypeEnum = new JsonArray();
        for (String rt : new String[]{"INLINE", "INLINE_EXCLUDED", "INLINE_INCLUDED",
                "TEXTUNIT", "EXCLUDE", "INCLUDE", "GROUP", "ATTRIBUTES_ONLY",
                "PRESERVE_WHITESPACE", "SCRIPT", "SERVER"}) {
            ruleTypeEnum.add(rt);
        }
        ruleTypeItems.add("enum", ruleTypeEnum);
        ruleTypes.add("items", ruleTypeItems);
        properties.add("ruleTypes", ruleTypes);

        // elementType - optional element type hint
        JsonObject elementType = new JsonObject();
        elementType.addProperty("type", "string");
        elementType.addProperty("description", "Element type hint (e.g. bold, italic, link, image, paragraph)");
        properties.add("elementType", elementType);

        // translatableAttributes, writableLocalizableAttributes, readOnlyLocalizableAttributes
        for (String attrProp : new String[]{"translatableAttributes", "writableLocalizableAttributes", "readOnlyLocalizableAttributes"}) {
            JsonObject attr = new JsonObject();
            attr.addProperty("description", "Attributes to extract as " + attrProp.replace("Attributes", ""));
            // Can be a simple string array OR a conditional map
            JsonArray oneOf = new JsonArray();
            // Simple array: ["alt", "title"]
            JsonObject simpleArray = new JsonObject();
            simpleArray.addProperty("type", "array");
            JsonObject strItem = new JsonObject();
            strItem.addProperty("type", "string");
            simpleArray.add("items", strItem);
            oneOf.add(simpleArray);
            // Conditional map: { attrName: [[condAttr, operator, value]] }
            JsonObject condMap = new JsonObject();
            condMap.addProperty("type", "object");
            condMap.add("additionalProperties", createRef("#/$defs/conditionalAttributeValue"));
            oneOf.add(condMap);
            attr.add("oneOf", oneOf);
            properties.add(attrProp, attr);
        }

        // idAttributes - always a simple string array
        JsonObject idAttrs = new JsonObject();
        idAttrs.addProperty("type", "array");
        idAttrs.addProperty("description", "Attributes that contain segment IDs");
        JsonObject idStrItem = new JsonObject();
        idStrItem.addProperty("type", "string");
        idAttrs.add("items", idStrItem);
        properties.add("idAttributes", idAttrs);

        // conditions - element-level conditions
        JsonObject conditions = new JsonObject();
        conditions.addProperty("type", "array");
        conditions.addProperty("description", "Conditions for this rule: [attributeName, operator, value]");
        properties.add("conditions", conditions);

        def.add("properties", properties);
        // ruleTypes is required
        JsonArray required = new JsonArray();
        required.add("ruleTypes");
        def.add("required", required);
        def.addProperty("additionalProperties", false);
        return def;
    }

    private JsonObject generateAttributeRuleDef() {
        JsonObject def = new JsonObject();
        def.addProperty("type", "object");
        def.addProperty("description", "Global extraction rule for an HTML/XML attribute");

        JsonObject properties = new JsonObject();

        // ruleTypes
        JsonObject ruleTypes = new JsonObject();
        ruleTypes.addProperty("type", "array");
        ruleTypes.addProperty("description", "Attribute rule types");
        JsonObject ruleTypeItems = new JsonObject();
        ruleTypeItems.addProperty("type", "string");
        JsonArray ruleTypeEnum = new JsonArray();
        for (String rt : new String[]{"ATTRIBUTE_TRANS", "ATTRIBUTE_WRITABLE",
                "ATTRIBUTE_READONLY", "ATTRIBUTE_ID"}) {
            ruleTypeEnum.add(rt);
        }
        ruleTypeItems.add("enum", ruleTypeEnum);
        ruleTypes.add("items", ruleTypeItems);
        properties.add("ruleTypes", ruleTypes);

        // allElementsExcept
        JsonObject allExcept = new JsonObject();
        allExcept.addProperty("type", "array");
        allExcept.addProperty("description", "Apply to all elements except these");
        JsonObject strItem = new JsonObject();
        strItem.addProperty("type", "string");
        allExcept.add("items", strItem);
        properties.add("allElementsExcept", allExcept);

        // onlyTheseElements
        JsonObject onlyThese = new JsonObject();
        onlyThese.addProperty("type", "array");
        onlyThese.addProperty("description", "Apply only to these elements");
        JsonObject strItem2 = new JsonObject();
        strItem2.addProperty("type", "string");
        onlyThese.add("items", strItem2);
        properties.add("onlyTheseElements", onlyThese);

        def.add("properties", properties);
        JsonArray required = new JsonArray();
        required.add("ruleTypes");
        def.add("required", required);
        def.addProperty("additionalProperties", false);
        return def;
    }

    private JsonObject generateConditionalAttributeValueDef() {
        JsonObject def = new JsonObject();
        def.addProperty("description", "Conditional attribute value — null (unconditional) or array of condition triples [attrName, operator, value/values]");
        // Can be null (unconditional) or an array of conditions
        JsonArray oneOf = new JsonArray();
        JsonObject nullType = new JsonObject();
        nullType.addProperty("type", "null");
        oneOf.add(nullType);
        JsonObject condArray = new JsonObject();
        condArray.addProperty("type", "array");
        condArray.addProperty("description", "Condition triples: [attributeName, EQUALS|NOT_EQUALS|MATCHES, value(s)]");
        oneOf.add(condArray);
        def.add("oneOf", oneOf);
        return def;
    }

    /**
     * Add a default value to a property schema.
     */
    private void addDefaultValue(JsonObject prop, String type, Object value) {
        switch (type) {
            case "boolean":
                if (value instanceof Boolean) {
                    prop.addProperty("default", (Boolean) value);
                }
                break;
            case "integer":
                if (value instanceof Number) {
                    prop.addProperty("default", ((Number) value).intValue());
                }
                break;
            case "number":
                if (value instanceof Number) {
                    prop.addProperty("default", ((Number) value).doubleValue());
                }
                break;
            case "string":
                if (value instanceof String) {
                    prop.addProperty("default", (String) value);
                }
                break;
            case "array":
                if (value instanceof JsonArray) {
                    prop.add("default", (JsonArray) value);
                }
                break;
            case "object":
                if (value instanceof JsonObject) {
                    prop.add("default", (JsonObject) value);
                }
                break;
        }
    }

    /**
     * Restructure a flat schema into a hierarchical one using groupings.
     *
     * Groups flat properties into nested objects based on the groupings definition.
     * Common groups (inlineCodes, whitespace) are matched when the filter has at least
     * one of the listed params, emitted as $ref, and their flat params removed.
     * Ungrouped properties remain at root level.
     *
     * @param flatSchema  The flat schema produced by generateBaseSchema
     * @param filterId    Filter ID (e.g. "okf_json")
     * @param groupings   The full groupings.json object
     * @param commonDefs  The common.defs.json object
     * @return The restructured schema (may be the same object if no groupings defined)
     */
    public JsonObject restructureIntoHierarchy(JsonObject flatSchema, String filterId,
                                                JsonObject groupings, JsonObject commonDefs) {
        // No groupings for this filter — return unchanged
        JsonObject filterGrouping = groupings.has(filterId)
                ? groupings.getAsJsonObject(filterId) : null;
        if (filterGrouping == null || filterGrouping.size() == 0) {
            return flatSchema;
        }

        JsonObject flatProperties = flatSchema.has("properties")
                ? flatSchema.getAsJsonObject("properties") : new JsonObject();
        JsonObject newProperties = new JsonObject();
        Set<String> claimedParams = new HashSet<>();

        // Process _common groups first
        JsonObject commonGroups = groupings.has("_common")
                ? groupings.getAsJsonObject("_common") : null;
        JsonObject defs = flatSchema.has("$defs")
                ? flatSchema.getAsJsonObject("$defs").deepCopy() : new JsonObject();
        JsonObject commonDefsSection = commonDefs.has("$defs")
                ? commonDefs.getAsJsonObject("$defs") : new JsonObject();

        if (commonGroups != null) {
            for (Map.Entry<String, JsonElement> entry : commonGroups.entrySet()) {
                String groupName = entry.getKey();
                JsonObject groupDef = entry.getValue().getAsJsonObject();
                JsonArray params = groupDef.getAsJsonArray("params");

                // Check if the filter has at least one of the listed params
                boolean hasAny = false;
                for (JsonElement param : params) {
                    if (flatProperties.has(param.getAsString())) {
                        hasAny = true;
                        break;
                    }
                }
                if (!hasAny) continue;

                // Add $ref at root level
                JsonObject ref = new JsonObject();
                ref.addProperty("$ref", "#/$defs/" + groupName);
                newProperties.add(groupName, ref);

                // Copy the definition from common.defs.json into $defs
                if (commonDefsSection.has(groupName)) {
                    defs.add(groupName, commonDefsSection.get(groupName).deepCopy());
                }
                // Also copy sub-definitions referenced by the common def (e.g. codeFinderRules, simplifierRules)
                if (commonDefsSection.has(groupName) && commonDefsSection.get(groupName).isJsonObject()) {
                    copyReferencedDefs(commonDefsSection.getAsJsonObject(groupName),
                            commonDefsSection, defs);
                }

                // Claim the flat params
                for (JsonElement param : params) {
                    claimedParams.add(param.getAsString());
                }
            }
        }

        // Process filter-specific groups
        for (Map.Entry<String, JsonElement> entry : filterGrouping.entrySet()) {
            String groupName = entry.getKey();
            JsonObject groupDef = entry.getValue().getAsJsonObject();
            String groupDescription = groupDef.has("description")
                    ? groupDef.get("description").getAsString() : null;
            JsonObject groupProps = groupDef.has("properties")
                    ? groupDef.getAsJsonObject("properties") : new JsonObject();

            JsonObject groupSchema = new JsonObject();
            groupSchema.addProperty("type", "object");
            if (groupDescription != null) {
                groupSchema.addProperty("description", groupDescription);
            }

            JsonObject groupProperties = new JsonObject();
            for (Map.Entry<String, JsonElement> propEntry : groupProps.entrySet()) {
                String cleanName = propEntry.getKey();
                JsonObject propDef = propEntry.getValue().getAsJsonObject();
                String flattenPath = propDef.has("flattenPath")
                        ? propDef.get("flattenPath").getAsString() : cleanName;

                // Find the original property in the flat schema
                if (flatProperties.has(flattenPath)) {
                    JsonObject originalProp = flatProperties.getAsJsonObject(flattenPath).deepCopy();
                    originalProp.addProperty("x-flattenPath", flattenPath);
                    // Override description if provided in grouping
                    if (propDef.has("description")) {
                        originalProp.addProperty("description", propDef.get("description").getAsString());
                    }
                    groupProperties.add(cleanName, originalProp);
                    claimedParams.add(flattenPath);
                }
            }

            if (groupProperties.size() > 0) {
                groupSchema.add("properties", groupProperties);
                newProperties.add(groupName, groupSchema);
            }
        }

        // Add unclaimed properties at root level
        for (Map.Entry<String, JsonElement> entry : flatProperties.entrySet()) {
            if (!claimedParams.contains(entry.getKey())) {
                newProperties.add(entry.getKey(), entry.getValue());
            }
        }

        // Update the schema
        flatSchema.add("properties", newProperties);
        if (defs.size() > 0) {
            flatSchema.add("$defs", defs);
        }
        // Remove x-groups (hierarchy replaces it)
        flatSchema.remove("x-groups");

        return flatSchema;
    }

    /**
     * Recursively copy $ref-referenced definitions from source defs into target defs.
     */
    private void copyReferencedDefs(JsonObject schema, JsonObject sourceDefs, JsonObject targetDefs) {
        if (schema.has("properties")) {
            JsonObject props = schema.getAsJsonObject("properties");
            for (Map.Entry<String, JsonElement> entry : props.entrySet()) {
                if (!entry.getValue().isJsonObject()) continue;
                JsonObject prop = entry.getValue().getAsJsonObject();
                if (prop.has("$ref")) {
                    String ref = prop.get("$ref").getAsString();
                    // Parse #/$defs/name
                    if (ref.startsWith("#/$defs/")) {
                        String defName = ref.substring("#/$defs/".length());
                        if (sourceDefs.has(defName) && !targetDefs.has(defName)) {
                            targetDefs.add(defName, sourceDefs.get(defName).deepCopy());
                        }
                    }
                }
            }
        }
    }

    /**
     * Merge editor hints into the schema.
     * 
     * Editor hints provide:
     * - Parameter groupings for UI
     * - Widget type specifications
     * - Presets for common configurations
     * - Enhanced descriptions
     */
    public void mergeEditorHints(JsonObject schema, JsonObject hints) {
        // Merge groups into x-groups extension
        if (hints.has("groups")) {
            schema.add("x-groups", hints.get("groups"));
        }
        
        // Merge field-specific hints into properties
        if (hints.has("fields") && schema.has("properties")) {
            JsonObject fields = hints.getAsJsonObject("fields");
            JsonObject properties = schema.getAsJsonObject("properties");
            
            for (Map.Entry<String, JsonElement> entry : fields.entrySet()) {
                String fieldName = entry.getKey();
                JsonObject fieldHints = entry.getValue().getAsJsonObject();
                
                if (properties.has(fieldName)) {
                    JsonObject prop = properties.getAsJsonObject(fieldName);
                    mergeFieldHints(prop, fieldHints);
                }
            }
        }
    }

    /**
     * Merge hints for a single field.
     */
    private void mergeFieldHints(JsonObject prop, JsonObject hints) {
        // Widget type
        if (hints.has("widget")) {
            prop.addProperty("x-widget", hints.get("widget").getAsString());
        }
        
        // Placeholder text
        if (hints.has("placeholder")) {
            prop.addProperty("x-placeholder", hints.get("placeholder").getAsString());
        }
        
        // Presets
        if (hints.has("presets")) {
            prop.add("x-presets", hints.get("presets"));
        }
        
        // Enhanced description (override if provided)
        if (hints.has("description")) {
            prop.addProperty("description", hints.get("description").getAsString());
        }
        
        // Display order
        if (hints.has("order")) {
            prop.addProperty("x-order", hints.get("order").getAsInt());
        }
        
        // Conditional visibility
        if (hints.has("showIf")) {
            prop.add("x-showIf", hints.get("showIf"));
        }
    }
}
