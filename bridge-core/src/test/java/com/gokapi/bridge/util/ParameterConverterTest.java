package com.gokapi.bridge.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ParameterConverter.
 */
class ParameterConverterTest {

    @Test
    void convertCodeFinderRules_shouldConvertToOkapiFormat() {
        JsonObject input = new JsonObject();
        JsonArray rules = new JsonArray();
        
        JsonObject rule1 = new JsonObject();
        rule1.addProperty("pattern", "<[^>]+>");
        rules.add(rule1);
        
        JsonObject rule2 = new JsonObject();
        rule2.addProperty("pattern", "\\{\\d+\\}");
        rules.add(rule2);
        
        input.add("rules", rules);
        input.addProperty("sample", "<b>text</b> {0}");
        input.addProperty("useAllRulesWhenTesting", true);
        
        String result = ParameterConverter.convertCodeFinderRules(input);
        
        assertNotNull(result);
        assertTrue(result.startsWith("#v1\n"), "Should start with version marker");
        assertTrue(result.contains("count.i=2"), "Should have correct rule count");
        assertTrue(result.contains("rule0=<[^>]+>"), "Should have first rule");
        assertTrue(result.contains("rule1=\\{\\d+\\}"), "Should have second rule");
        assertTrue(result.contains("sample=<b>text</b> {0}"), "Should have sample");
        assertTrue(result.contains("useAllRulesWhenTesting.b=true"), "Should have flag");
    }

    @Test
    void convertCodeFinderRules_withEmptyRules_shouldHandleGracefully() {
        JsonObject input = new JsonObject();
        input.add("rules", new JsonArray());
        
        String result = ParameterConverter.convertCodeFinderRules(input);
        
        assertNotNull(result);
        assertTrue(result.contains("count.i=0"));
    }

    @Test
    void convertCodeFinderRules_withNull_shouldReturnEmpty() {
        String result = ParameterConverter.convertCodeFinderRules(null);
        assertEquals("", result);
    }

    @Test
    void parseCodeFinderRules_shouldConvertFromOkapiFormat() {
        String okapiFormat = "#v1\n" +
            "count.i=2\n" +
            "rule0=<[^>]+>\n" +
            "rule1=\\{\\d+\\}\n" +
            "sample=test text\n" +
            "useAllRulesWhenTesting.b=true\n";
        
        JsonObject result = ParameterConverter.parseCodeFinderRules(okapiFormat);
        
        assertNotNull(result);
        assertTrue(result.has("rules"));
        
        JsonArray rules = result.getAsJsonArray("rules");
        assertEquals(2, rules.size());
        assertEquals("<[^>]+>", rules.get(0).getAsJsonObject().get("pattern").getAsString());
        assertEquals("\\{\\d+\\}", rules.get(1).getAsJsonObject().get("pattern").getAsString());
        
        assertEquals("test text", result.get("sample").getAsString());
        assertTrue(result.get("useAllRulesWhenTesting").getAsBoolean());
    }

    @Test
    void parseCodeFinderRules_withEmpty_shouldReturnNull() {
        assertNull(ParameterConverter.parseCodeFinderRules(null));
        assertNull(ParameterConverter.parseCodeFinderRules(""));
    }

    @Test
    void convertCodeFinderRules_roundTrip_shouldPreserveData() {
        // Create original
        JsonObject original = new JsonObject();
        JsonArray rules = new JsonArray();
        JsonObject rule = new JsonObject();
        rule.addProperty("pattern", "test-pattern-\\d+");
        rules.add(rule);
        original.add("rules", rules);
        original.addProperty("sample", "test-pattern-123");
        
        // Convert to Okapi format
        String okapiFormat = ParameterConverter.convertCodeFinderRules(original);
        
        // Parse back
        JsonObject parsed = ParameterConverter.parseCodeFinderRules(okapiFormat);
        
        // Verify
        assertNotNull(parsed);
        JsonArray parsedRules = parsed.getAsJsonArray("rules");
        assertEquals(1, parsedRules.size());
        assertEquals("test-pattern-\\d+", parsedRules.get(0).getAsJsonObject().get("pattern").getAsString());
        assertEquals("test-pattern-123", parsed.get("sample").getAsString());
    }

    @Test
    void convertParameterValue_booleanValue_shouldReturnBoolean() {
        JsonObject json = JsonParser.parseString("{\"flag\": true}").getAsJsonObject();
        Object result = ParameterConverter.convertParameterValue("flag", json.get("flag"), null);
        
        assertTrue(result instanceof Boolean);
        assertTrue((Boolean) result);
    }

    @Test
    void convertParameterValue_intValue_shouldReturnInteger() {
        JsonObject json = JsonParser.parseString("{\"count\": 42}").getAsJsonObject();
        Object result = ParameterConverter.convertParameterValue("count", json.get("count"), null);
        
        assertTrue(result instanceof Integer);
        assertEquals(42, result);
    }

    @Test
    void convertParameterValue_stringValue_shouldReturnString() {
        JsonObject json = JsonParser.parseString("{\"name\": \"test\"}").getAsJsonObject();
        Object result = ParameterConverter.convertParameterValue("name", json.get("name"), null);
        
        assertTrue(result instanceof String);
        assertEquals("test", result);
    }

    @Test
    void convertParameterValue_codeFinderRules_shouldConvert() {
        String jsonStr = "{\"codeFinderRules\": {\"rules\": [{\"pattern\": \"test\"}]}}";
        JsonObject json = JsonParser.parseString(jsonStr).getAsJsonObject();
        
        Object result = ParameterConverter.convertParameterValue(
            "codeFinderRules", 
            json.get("codeFinderRules"), 
            null
        );
        
        assertTrue(result instanceof String);
        assertTrue(((String) result).contains("#v1"));
        assertTrue(((String) result).contains("rule0=test"));
    }

    @Test
    void convertParameterValue_withOkapiFormatHint_shouldConvert() {
        String jsonStr = "{\"inlineCode\": {\"rules\": [{\"pattern\": \"<.*?>\"}]}}";
        JsonObject json = JsonParser.parseString(jsonStr).getAsJsonObject();
        
        Object result = ParameterConverter.convertParameterValue(
            "inlineCode", 
            json.get("inlineCode"), 
            "inlineCodeFinder"
        );
        
        assertTrue(result instanceof String);
        assertTrue(((String) result).contains("#v1"));
    }

    @Test
    void convertParameterValue_nullValue_shouldReturnNull() {
        assertNull(ParameterConverter.convertParameterValue("test", null, null));
    }

    @Test
    void convertAllParameters_shouldConvertAllValues() {
        String jsonStr = "{\"extractAll\": true, \"maxDepth\": 5, \"pattern\": \".*\"}";
        JsonObject params = JsonParser.parseString(jsonStr).getAsJsonObject();
        
        JsonObject result = ParameterConverter.convertAllParameters(params, null);
        
        assertNotNull(result);
        assertTrue(result.get("extractAll").getAsBoolean());
        assertEquals(5, result.get("maxDepth").getAsInt());
        assertEquals(".*", result.get("pattern").getAsString());
    }

    @Test
    void convertAllParameters_withSchema_shouldUseFormatHints() {
        String paramsStr = "{\"codeRules\": {\"rules\": [{\"pattern\": \"test\"}]}}";
        JsonObject params = JsonParser.parseString(paramsStr).getAsJsonObject();
        
        // Schema with format hint
        String schemaStr = "{\"properties\": {\"codeRules\": {\"x-okapiFormat\": \"inlineCodeFinder\"}}}";
        JsonObject schema = JsonParser.parseString(schemaStr).getAsJsonObject();
        
        JsonObject result = ParameterConverter.convertAllParameters(params, schema);
        
        assertNotNull(result);
        assertTrue(result.has("codeRules"));
        assertTrue(result.get("codeRules").getAsString().contains("#v1"));
    }

    @Test
    void convertAllParameters_withNull_shouldReturnNull() {
        assertNull(ParameterConverter.convertAllParameters(null, null));
    }
}
