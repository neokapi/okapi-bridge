package com.gokapi.bridge.util;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ParameterFlattener.
 */
class ParameterFlattenerTest {

    @Test
    void flatten_flatInput_shouldPassThrough() {
        JsonObject schema = JsonParser.parseString("{"
                + "\"properties\": {"
                + "  \"extractAllPairs\": { \"type\": \"boolean\" },"
                + "  \"useCodeFinder\": { \"type\": \"boolean\" }"
                + "}"
                + "}").getAsJsonObject();

        ParameterFlattener flattener = new ParameterFlattener(schema);

        JsonObject input = new JsonObject();
        input.addProperty("extractAllPairs", true);
        input.addProperty("useCodeFinder", false);

        JsonObject result = flattener.flatten(input);

        assertTrue(result.get("extractAllPairs").getAsBoolean());
        assertFalse(result.get("useCodeFinder").getAsBoolean());
    }

    @Test
    void flatten_hierarchicalInput_shouldFlatten() {
        JsonObject schema = JsonParser.parseString("{"
                + "\"properties\": {"
                + "  \"extraction\": {"
                + "    \"type\": \"object\","
                + "    \"properties\": {"
                + "      \"extractAll\": {"
                + "        \"type\": \"boolean\","
                + "        \"x-flattenPath\": \"extractAllPairs\""
                + "      },"
                + "      \"excludeKeys\": {"
                + "        \"type\": \"string\","
                + "        \"x-flattenPath\": \"exceptions\""
                + "      }"
                + "    }"
                + "  },"
                + "  \"output\": {"
                + "    \"type\": \"object\","
                + "    \"properties\": {"
                + "      \"escapeSlashes\": {"
                + "        \"type\": \"boolean\","
                + "        \"x-flattenPath\": \"escapeForwardSlashes\""
                + "      }"
                + "    }"
                + "  }"
                + "}"
                + "}").getAsJsonObject();

        ParameterFlattener flattener = new ParameterFlattener(schema);

        // Build hierarchical input
        JsonObject input = new JsonObject();
        JsonObject extraction = new JsonObject();
        extraction.addProperty("extractAll", true);
        extraction.addProperty("excludeKeys", "id,type");
        input.add("extraction", extraction);
        JsonObject output = new JsonObject();
        output.addProperty("escapeSlashes", false);
        input.add("output", output);

        JsonObject result = flattener.flatten(input);

        assertTrue(result.get("extractAllPairs").getAsBoolean());
        assertEquals("id,type", result.get("exceptions").getAsString());
        assertFalse(result.get("escapeForwardSlashes").getAsBoolean());
        // Organizational groups should not appear in output
        assertFalse(result.has("extraction"));
        assertFalse(result.has("output"));
    }

    @Test
    void flatten_mixedInput_shouldHandleBoth() {
        JsonObject schema = JsonParser.parseString("{"
                + "\"properties\": {"
                + "  \"extraction\": {"
                + "    \"type\": \"object\","
                + "    \"properties\": {"
                + "      \"extractAll\": {"
                + "        \"type\": \"boolean\","
                + "        \"x-flattenPath\": \"extractAllPairs\""
                + "      }"
                + "    }"
                + "  },"
                + "  \"useCodeFinder\": { \"type\": \"boolean\" }"
                + "}"
                + "}").getAsJsonObject();

        ParameterFlattener flattener = new ParameterFlattener(schema);

        JsonObject input = new JsonObject();
        JsonObject extraction = new JsonObject();
        extraction.addProperty("extractAll", false);
        input.add("extraction", extraction);
        input.addProperty("useCodeFinder", true);

        JsonObject result = flattener.flatten(input);

        assertFalse(result.get("extractAllPairs").getAsBoolean());
        assertTrue(result.get("useCodeFinder").getAsBoolean());
    }

    @Test
    void flatten_domainObjectPreserved_shouldNotFlattenCodeFinderRules() {
        // codeFinderRules is a domain object (not an organizational group)
        // It should pass through as-is, not be flattened
        JsonObject schema = JsonParser.parseString("{"
                + "\"properties\": {"
                + "  \"codeFinderRules\": {"
                + "    \"type\": \"object\","
                + "    \"x-okapiFormat\": \"inlineCodeFinder\","
                + "    \"properties\": {"
                + "      \"rules\": { \"type\": \"array\" },"
                + "      \"sample\": { \"type\": \"string\" }"
                + "    }"
                + "  }"
                + "}"
                + "}").getAsJsonObject();

        ParameterFlattener flattener = new ParameterFlattener(schema);

        JsonObject input = new JsonObject();
        JsonObject codeFinderRules = new JsonObject();
        codeFinderRules.addProperty("sample", "test");
        input.add("codeFinderRules", codeFinderRules);

        JsonObject result = flattener.flatten(input);

        // codeFinderRules should be preserved as an object (not flattened)
        assertTrue(result.has("codeFinderRules"));
        assertTrue(result.get("codeFinderRules").isJsonObject());
        assertEquals("test", result.getAsJsonObject("codeFinderRules").get("sample").getAsString());
    }

    @Test
    void flatten_withRef_shouldResolveFlattenPaths() {
        JsonObject schema = JsonParser.parseString("{"
                + "\"properties\": {"
                + "  \"inlineCodes\": {"
                + "    \"$ref\": \"#/$defs/inlineCodes\""
                + "  }"
                + "},"
                + "\"$defs\": {"
                + "  \"inlineCodes\": {"
                + "    \"type\": \"object\","
                + "    \"properties\": {"
                + "      \"enabled\": {"
                + "        \"type\": \"boolean\","
                + "        \"x-flattenPath\": \"useCodeFinder\""
                + "      },"
                + "      \"mergeAdjacent\": {"
                + "        \"type\": \"boolean\","
                + "        \"x-flattenPath\": \"mergeAdjacentCodes\""
                + "      }"
                + "    }"
                + "  }"
                + "}"
                + "}").getAsJsonObject();

        // Use the ref-aware builder
        Map<String, String> map = ParameterFlattener.buildFlattenMapWithRefs(schema);

        assertEquals("useCodeFinder", map.get("inlineCodes.enabled"));
        assertEquals("mergeAdjacentCodes", map.get("inlineCodes.mergeAdjacent"));
    }

    @Test
    void flatten_emptyInput_shouldReturnEmpty() {
        JsonObject schema = JsonParser.parseString("{"
                + "\"properties\": {"
                + "  \"extractAll\": { \"type\": \"boolean\", \"x-flattenPath\": \"extractAllPairs\" }"
                + "}"
                + "}").getAsJsonObject();

        ParameterFlattener flattener = new ParameterFlattener(schema);

        JsonObject input = new JsonObject();
        JsonObject result = flattener.flatten(input);

        assertEquals(0, result.size());
    }

    @Test
    void flatten_emptySchema_shouldPassThrough() {
        JsonObject schema = new JsonObject();

        ParameterFlattener flattener = new ParameterFlattener(schema);

        JsonObject input = new JsonObject();
        input.addProperty("someParam", "value");

        JsonObject result = flattener.flatten(input);

        assertEquals("value", result.get("someParam").getAsString());
    }

    @Test
    void getFlattenMap_shouldReturnCopy() {
        JsonObject schema = JsonParser.parseString("{"
                + "\"properties\": {"
                + "  \"extraction\": {"
                + "    \"type\": \"object\","
                + "    \"properties\": {"
                + "      \"extractAll\": {"
                + "        \"type\": \"boolean\","
                + "        \"x-flattenPath\": \"extractAllPairs\""
                + "      }"
                + "    }"
                + "  }"
                + "}"
                + "}").getAsJsonObject();

        ParameterFlattener flattener = new ParameterFlattener(schema);
        Map<String, String> map = flattener.getFlattenMap();

        assertEquals("extractAllPairs", map.get("extraction.extractAll"));
        // Modifying the copy should not affect the flattener
        map.clear();
        assertEquals("extractAllPairs", flattener.getFlattenMap().get("extraction.extractAll"));
    }

    @Test
    void flatten_deeplyNestedGroups_shouldFlatten() {
        // Though unlikely, test 3-level nesting
        JsonObject schema = JsonParser.parseString("{"
                + "\"properties\": {"
                + "  \"level1\": {"
                + "    \"type\": \"object\","
                + "    \"properties\": {"
                + "      \"level2\": {"
                + "        \"type\": \"object\","
                + "        \"properties\": {"
                + "          \"param\": {"
                + "            \"type\": \"string\","
                + "            \"x-flattenPath\": \"deepParam\""
                + "          }"
                + "        }"
                + "      }"
                + "    }"
                + "  }"
                + "}"
                + "}").getAsJsonObject();

        ParameterFlattener flattener = new ParameterFlattener(schema);

        JsonObject input = new JsonObject();
        JsonObject level1 = new JsonObject();
        JsonObject level2 = new JsonObject();
        level2.addProperty("param", "hello");
        level1.add("level2", level2);
        input.add("level1", level1);

        JsonObject result = flattener.flatten(input);

        assertEquals("hello", result.get("deepParam").getAsString());
    }
}
