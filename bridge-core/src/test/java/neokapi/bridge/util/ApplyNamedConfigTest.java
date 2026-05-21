package neokapi.bridge.util;

import net.sf.okapi.common.filters.IFilter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link FilterRegistry#applyNamedConfig} — loading a named built-in
 * Okapi filter configuration onto a live filter (neokapi#613).
 *
 * <p>The compound XML filters expose their built-in presets via
 * {@code getConfigurations()}. We assert that loading a specific preset sets
 * parameters whose serialized form contains a rule distinctive to that preset's
 * {@code .fprm} / {@code .yml} resource, and that an unknown configId errors
 * (so a typo surfaces rather than silently using the filter's default config).
 *
 * <p>Config ids verified against Okapi 1.48.0:
 * {@code okf_xml-resx} / {@code okf_xml-docbook} on
 * {@code net.sf.okapi.filters.xml.XMLFilter} (its module) and
 * {@code okf_xmlstream-dita} on
 * {@code net.sf.okapi.filters.xmlstream.XmlStreamFilter}.
 */
class ApplyNamedConfigTest {

    private static final String XML_FILTER = "net.sf.okapi.filters.xml.XMLFilter";
    private static final String XMLSTREAM_FILTER = "net.sf.okapi.filters.xmlstream.XmlStreamFilter";

    private static IFilter newFilter(String className) {
        IFilter f = FilterRegistry.createFilter(className);
        assertNotNull(f, "filter " + className + " must be on the classpath for this test");
        return f;
    }

    // ── Known configs load distinctive parameters ───────────────────────────

    @Test
    void applyNamedConfig_resxOnXmlFilter_loadsResxItsRules() {
        IFilter filter = newFilter(XML_FILTER);

        FilterRegistry.applyNamedConfig(filter, "okf_xml-resx");

        String params = filter.getParameters().toString();
        assertNotNull(params);
        // The RESX preset is ITS rules XML. Assert distinctive RESX selectors.
        assertTrue(params.contains("its:rules"),
                "RESX config should load ITS rules, got: " + params);
        assertTrue(params.contains("$this.Text"),
                "RESX config should contain the $this.Text translate rule, got: " + params);
    }

    @Test
    void applyNamedConfig_docbookOnXmlFilter_loadsDocbookRules() {
        IFilter filter = newFilter(XML_FILTER);

        FilterRegistry.applyNamedConfig(filter, "okf_xml-docbook");

        String params = filter.getParameters().toString();
        assertNotNull(params);
        assertTrue(params.contains("its:rules"),
                "DocBook config should load ITS rules, got: " + params);
        // DocBook rules reference docbook-specific elements (e.g. <para>).
        assertTrue(params.toLowerCase().contains("para"),
                "DocBook config should reference docbook elements, got: " + params);
    }

    @Test
    void applyNamedConfig_ditaOnXmlStreamFilter_loadsDitaYaml() {
        IFilter filter = newFilter(XMLSTREAM_FILTER);

        FilterRegistry.applyNamedConfig(filter, "okf_xmlstream-dita");

        String params = filter.getParameters().toString();
        assertNotNull(params);
        // The DITA preset is YAML markup rules. Assert distinctive DITA elements.
        assertTrue(params.contains("topicref"),
                "DITA config should reference the topicref element, got: " + params);
        assertTrue(params.contains("navtitle"),
                "DITA config should reference the navtitle attribute, got: " + params);
    }

    @Test
    void applyNamedConfig_differsFromDefault() {
        // The named config must actually change the filter's parameters away
        // from its bare default — otherwise running a preset would be a no-op.
        IFilter defaultFilter = newFilter(XMLSTREAM_FILTER);
        String defaultParams = defaultFilter.getParameters().toString();

        IFilter ditaFilter = newFilter(XMLSTREAM_FILTER);
        FilterRegistry.applyNamedConfig(ditaFilter, "okf_xmlstream-dita");
        String ditaParams = ditaFilter.getParameters().toString();

        assertNotEquals(defaultParams, ditaParams,
                "applying okf_xmlstream-dita should change parameters from the default config");
    }

    // ── Base config (no parameters resource) is a tolerated no-op ────────────

    @Test
    void applyNamedConfig_baseConfig_noParametersResource_isNoOp() {
        IFilter filter = newFilter(XML_FILTER);
        // okf_xml is the base config with a null parametersLocation — applying
        // it must not throw (the filter's own defaults already match it).
        assertDoesNotThrow(() -> FilterRegistry.applyNamedConfig(filter, "okf_xml"));
    }

    // ── Unknown / invalid config ids surface as errors ───────────────────────

    @Test
    void applyNamedConfig_unknownConfigId_throws() {
        IFilter filter = newFilter(XML_FILTER);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> FilterRegistry.applyNamedConfig(filter, "okf_xml-doesnotexist"));
        assertTrue(ex.getMessage().contains("okf_xml-doesnotexist"),
                "error message should name the bad config id, got: " + ex.getMessage());
        // The message should also list the available configs to aid debugging.
        assertTrue(ex.getMessage().contains("okf_xml-resx"),
                "error message should list available configs, got: " + ex.getMessage());
    }

    @Test
    void applyNamedConfig_nullConfigId_throws() {
        IFilter filter = newFilter(XML_FILTER);
        assertThrows(IllegalArgumentException.class,
                () -> FilterRegistry.applyNamedConfig(filter, null));
    }

    @Test
    void applyNamedConfig_emptyConfigId_throws() {
        IFilter filter = newFilter(XML_FILTER);
        assertThrows(IllegalArgumentException.class,
                () -> FilterRegistry.applyNamedConfig(filter, ""));
    }

    @Test
    void applyNamedConfig_nullFilter_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> FilterRegistry.applyNamedConfig(null, "okf_xml-resx"));
    }
}
