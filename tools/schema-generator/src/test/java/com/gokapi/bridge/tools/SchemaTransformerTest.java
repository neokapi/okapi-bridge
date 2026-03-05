package com.gokapi.bridge.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;


import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for SchemaTransformer.
 */
class SchemaTransformerTest {

    private SchemaTransformer transformer;

    @BeforeEach
    void setUp() {
        transformer = new SchemaTransformer();
    }

    @Test
    void transformParameter_booleanParam_shouldHaveCorrectSchema() {
        ParameterIntrospector.ParamInfo boolParam = new ParameterIntrospector.ParamInfo("extractAll", "boolean");
        boolParam.defaultValue = true;
        boolParam.description = "Extract all pairs";
        
        JsonObject propSchema = transformer.transformParameter("extractAll", boolParam);
        
        assertNotNull(propSchema);
        assertEquals("boolean", propSchema.get("type").getAsString());
        assertTrue(propSchema.get("default").getAsBoolean());
        assertEquals("Extract all pairs", propSchema.get("description").getAsString());
    }

    @Test
    void transformParameter_stringParam_shouldHaveCorrectSchema() {
        ParameterIntrospector.ParamInfo strParam = new ParameterIntrospector.ParamInfo("keyPattern", "string");
        strParam.defaultValue = ".*";
        
        JsonObject propSchema = transformer.transformParameter("keyPattern", strParam);
        
        assertNotNull(propSchema);
        assertEquals("string", propSchema.get("type").getAsString());
        assertEquals(".*", propSchema.get("default").getAsString());
    }

    @Test
    void transformParameter_integerParam_shouldHaveCorrectSchema() {
        ParameterIntrospector.ParamInfo intParam = new ParameterIntrospector.ParamInfo("maxDepth", "integer");
        intParam.defaultValue = 10;
        
        JsonObject propSchema = transformer.transformParameter("maxDepth", intParam);
        
        assertNotNull(propSchema);
        assertEquals("integer", propSchema.get("type").getAsString());
        assertEquals(10, propSchema.get("default").getAsInt());
    }

    @Test
    void transformParameter_codeFinderRulesParam_shouldHaveObjectSchema() {
        ParameterIntrospector.ParamInfo cfParam = new ParameterIntrospector.ParamInfo("codeFinderRules", "object");
        cfParam.okapiFormat = "inlineCodeFinder";
        
        JsonObject propSchema = transformer.transformParameter("codeFinderRules", cfParam);
        
        assertNotNull(propSchema);
        assertEquals("object", propSchema.get("type").getAsString());
        assertEquals("inlineCodeFinder", propSchema.get("x-okapiFormat").getAsString());
        
        // Should have nested properties for rules structure
        assertTrue(propSchema.has("properties"), "Should have nested properties");
        JsonObject nestedProps = propSchema.getAsJsonObject("properties");
        assertTrue(nestedProps.has("rules"), "Should have rules property");
        assertTrue(nestedProps.has("sample"), "Should have sample property");
        assertTrue(nestedProps.has("useAllRulesWhenTesting"), "Should have useAllRulesWhenTesting");
    }

    @Test
    void transformParameter_deprecatedParam_shouldBeMarked() {
        ParameterIntrospector.ParamInfo deprecatedParam = new ParameterIntrospector.ParamInfo("oldOption", "boolean");
        deprecatedParam.deprecated = true;
        
        JsonObject propSchema = transformer.transformParameter("oldOption", deprecatedParam);
        
        assertNotNull(propSchema);
        assertTrue(propSchema.get("deprecated").getAsBoolean());
    }

    @Test
    void transformParameter_enumParam_shouldHaveEnumConstraint() {
        ParameterIntrospector.ParamInfo enumParam = new ParameterIntrospector.ParamInfo("mode", "string");
        enumParam.enumValues = java.util.Arrays.asList("auto", "manual", "hybrid");
        
        JsonObject propSchema = transformer.transformParameter("mode", enumParam);
        
        assertNotNull(propSchema);
        assertTrue(propSchema.has("enum"));
        JsonArray enumValues = propSchema.getAsJsonArray("enum");
        assertEquals(3, enumValues.size());
        assertEquals("auto", enumValues.get(0).getAsString());
    }

    @Test
    void transformParameter_subfilterParam_shouldReturnNull() {
        ParameterIntrospector.ParamInfo subfilterParam = new ParameterIntrospector.ParamInfo("subfilter", "string");
        
        JsonObject propSchema = transformer.transformParameter("subfilter", subfilterParam);
        
        // subfilter is handled by gokapi Layers, so should be excluded
        assertNull(propSchema);
    }

    @Test
    void transformParameter_simplifierRules_shouldHaveWidget() {
        ParameterIntrospector.ParamInfo simpParam = new ParameterIntrospector.ParamInfo("simplifierRules", "string");
        
        JsonObject propSchema = transformer.transformParameter("simplifierRules", simpParam);
        
        assertNotNull(propSchema);
        assertEquals("simplifierRulesEditor", propSchema.get("x-widget").getAsString());
    }

}
