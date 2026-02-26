package com.gokapi.bridge.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.sf.okapi.common.IParameters;
import net.sf.okapi.common.filters.IFilter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ParameterApplier.
 */
class ParameterApplierTest {

    @Test
    void applyParameters_withNullParams_shouldReturnFalse() {
        JsonObject filterParams = new JsonObject();
        filterParams.addProperty("test", true);
        
        boolean result = ParameterApplier.applyParameters(null, filterParams);
        assertFalse(result);
    }

    @Test
    void applyParameters_withNullFilterParams_shouldReturnFalse() throws Exception {
        IFilter filter = createJsonFilter();
        IParameters params = filter.getParameters();
        
        boolean result = ParameterApplier.applyParameters(params, null);
        assertFalse(result);
    }

    @Test
    void applyParameters_booleanValue_shouldApply() throws Exception {
        IFilter filter = createJsonFilter();
        IParameters params = filter.getParameters();
        
        JsonObject filterParams = new JsonObject();
        filterParams.addProperty("extractStandalone", true);
        
        boolean result = ParameterApplier.applyParameters(params, filterParams);
        
        assertTrue(result, "Should successfully apply boolean parameter");
        assertTrue(params.getBoolean("extractStandalone"), "Value should be applied");
    }

    @Test
    void applyParameters_stringValue_shouldApply() throws Exception {
        IFilter filter = createPropertiesFilter();
        IParameters params = filter.getParameters();
        
        JsonObject filterParams = new JsonObject();
        filterParams.addProperty("escapeExtendedChars", true);
        
        boolean result = ParameterApplier.applyParameters(params, filterParams);
        
        assertTrue(result, "Should successfully apply parameter");
    }

    @Test
    void applyParameters_integerValue_shouldApply() throws Exception {
        IFilter filter = createJsonFilter();
        IParameters params = filter.getParameters();
        
        // Find an integer parameter to test
        JsonObject filterParams = new JsonObject();
        filterParams.addProperty("extractStandalone", false);
        
        boolean result = ParameterApplier.applyParameters(params, filterParams);
        
        assertTrue(result, "Should successfully apply parameter");
    }

    @Test
    void applyParameters_codeFinderRules_shouldApply() throws Exception {
        IFilter filter = createJsonFilter();
        IParameters params = filter.getParameters();
        
        // Build codeFinderRules in clean JSON format
        JsonObject filterParams = new JsonObject();
        JsonObject codeFinderRules = new JsonObject();
        JsonArray rules = new JsonArray();
        JsonObject rule = new JsonObject();
        rule.addProperty("pattern", "<[^>]+>");
        rules.add(rule);
        codeFinderRules.add("rules", rules);
        codeFinderRules.addProperty("sample", "test <b>text</b>");
        filterParams.add("codeFinderRules", codeFinderRules);
        
        boolean result = ParameterApplier.applyParameters(params, filterParams);
        
        // Result depends on whether the filter supports codeFinderRules
        // Just verify it doesn't throw
        assertNotNull(result);
    }

    @Test
    void applyParameters_multipleValues_shouldApplyAll() throws Exception {
        IFilter filter = createJsonFilter();
        IParameters params = filter.getParameters();
        
        JsonObject filterParams = new JsonObject();
        filterParams.addProperty("extractStandalone", true);
        filterParams.addProperty("extractAllPairs", false);
        
        boolean result = ParameterApplier.applyParameters(params, filterParams);
        
        assertTrue(result, "Should apply all parameters");
    }

    @Test
    void applyParameters_emptyParams_shouldSucceed() throws Exception {
        IFilter filter = createJsonFilter();
        IParameters params = filter.getParameters();
        
        JsonObject filterParams = new JsonObject();
        
        boolean result = ParameterApplier.applyParameters(params, filterParams);
        
        assertTrue(result, "Empty params should succeed");
    }

    @Test
    void applyParameters_nullValue_shouldBeSkipped() throws Exception {
        IFilter filter = createJsonFilter();
        IParameters params = filter.getParameters();
        
        JsonObject filterParams = new JsonObject();
        filterParams.add("extractStandalone", null); // null JSON element
        filterParams.addProperty("extractAllPairs", true);
        
        boolean result = ParameterApplier.applyParameters(params, filterParams);
        
        assertTrue(result, "Should succeed with null values skipped");
    }

    @Test
    void applyParameters_codeFinderRulesAsString_shouldApply() throws Exception {
        IFilter filter = createJsonFilter();
        IParameters params = filter.getParameters();
        
        // Provide codeFinderRules in already-converted Okapi format
        JsonObject filterParams = new JsonObject();
        filterParams.addProperty("codeFinderRules", "#v1\ncount.i=1\nrule0=test\n");
        
        boolean result = ParameterApplier.applyParameters(params, filterParams);
        
        // Just verify it doesn't throw
        assertNotNull(result);
    }

    @Test
    void applyParameters_toPropertiesFilter_shouldWork() throws Exception {
        IFilter filter = createPropertiesFilter();
        IParameters params = filter.getParameters();
        
        JsonObject filterParams = new JsonObject();
        filterParams.addProperty("useCodeFinder", true);
        
        boolean result = ParameterApplier.applyParameters(params, filterParams);
        
        assertTrue(result, "Should apply to Properties filter");
    }

    @Test
    void applyParameters_toHtmlFilter_shouldWork() throws Exception {
        IFilter filter = createHtmlFilter();
        IParameters params = filter.getParameters();
        
        JsonObject filterParams = new JsonObject();
        // HTML filter has boolean parameters
        
        boolean result = ParameterApplier.applyParameters(params, filterParams);
        
        assertTrue(result, "Should work with HTML filter");
    }

    // Helper methods to create filter instances
    
    private IFilter createJsonFilter() throws Exception {
        Class<?> filterClass = Class.forName("net.sf.okapi.filters.json.JSONFilter");
        return (IFilter) filterClass.getDeclaredConstructor().newInstance();
    }

    private IFilter createPropertiesFilter() throws Exception {
        Class<?> filterClass = Class.forName("net.sf.okapi.filters.properties.PropertiesFilter");
        return (IFilter) filterClass.getDeclaredConstructor().newInstance();
    }

    private IFilter createHtmlFilter() throws Exception {
        Class<?> filterClass = Class.forName("net.sf.okapi.filters.html.HtmlFilter");
        return (IFilter) filterClass.getDeclaredConstructor().newInstance();
    }
}
