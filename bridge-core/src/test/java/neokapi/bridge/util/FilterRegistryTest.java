package neokapi.bridge.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for FilterRegistry envelope support (Kind + apiVersion).
 */
class FilterRegistryTest {

    // ── toKind ───────────────────────────────────────────────────────────

    @Test
    void toKind_html() {
        assertEquals("OkfHtmlFilterConfig", FilterRegistry.toKind("okf_html"));
    }

    @Test
    void toKind_json() {
        assertEquals("OkfJsonFilterConfig", FilterRegistry.toKind("okf_json"));
    }

    @Test
    void toKind_openxml() {
        assertEquals("OkfOpenxmlFilterConfig", FilterRegistry.toKind("okf_openxml"));
    }

    @Test
    void toKind_xmlstream() {
        assertEquals("OkfXmlstreamFilterConfig", FilterRegistry.toKind("okf_xmlstream"));
    }

    @Test
    void toKind_plaintext() {
        assertEquals("OkfPlaintextFilterConfig", FilterRegistry.toKind("okf_plaintext"));
    }

    @Test
    void toKind_nullFilterId_returnsNull() {
        assertNull(FilterRegistry.toKind(null));
    }

    @Test
    void toKind_invalidPrefix_returnsNull() {
        assertNull(FilterRegistry.toKind("invalid_html"));
    }

    // ── toApiVersion ─────────────────────────────────────────────────────

    @Test
    void toApiVersion_v1() {
        assertEquals("v1", FilterRegistry.toApiVersion(1));
    }

    @Test
    void toApiVersion_v3() {
        assertEquals("v3", FilterRegistry.toApiVersion(3));
    }

    // ── resolveByKind ────────────────────────────────────────────────────

    @Test
    void resolveByKind_validHtml_returnsFilterClass() {
        // The filter registry discovers filters from classpath JARs.
        String filterClass = FilterRegistry.resolveByKind("OkfHtmlFilterConfig");
        // May be null if no HTML filter is on the classpath.
        if (filterClass != null) {
            assertTrue(filterClass.contains("html") || filterClass.contains("Html") ||
                    filterClass.contains("HTML"),
                    "Expected HTML filter class, got: " + filterClass);
        }
    }

    @Test
    void resolveByKind_null_returnsNull() {
        assertNull(FilterRegistry.resolveByKind(null));
    }

    @Test
    void resolveByKind_empty_returnsNull() {
        assertNull(FilterRegistry.resolveByKind(""));
    }

    @Test
    void resolveByKind_invalidFormat_noPrefix_throws() {
        assertThrows(IllegalArgumentException.class, () ->
                FilterRegistry.resolveByKind("HtmlFilterConfig"));
    }

    @Test
    void resolveByKind_invalidFormat_noSuffix_throws() {
        assertThrows(IllegalArgumentException.class, () ->
                FilterRegistry.resolveByKind("OkfHtml"));
    }

    @Test
    void resolveByKind_emptyFormat_throws() {
        assertThrows(IllegalArgumentException.class, () ->
                FilterRegistry.resolveByKind("OkfFilterConfig"));
    }

    @Test
    void resolveByKind_unknownFormat_returnsNull() {
        assertNull(FilterRegistry.resolveByKind("OkfNonexistentFilterConfig"));
    }

    // ── promoteFilterForInput ────────────────────────────────────────────

    @Test
    void promoteFilterForInput_odfWithOdt_promotesToOpenoffice(@TempDir Path tmp) throws Exception {
        File odt = writeZipStub(tmp.resolve("doc.odt").toFile());
        assertEquals("okf_openoffice",
                FilterRegistry.promoteFilterForInput("okf_odf", odt));
    }

    @Test
    void promoteFilterForInput_odfWithOds_promotesToOpenoffice(@TempDir Path tmp) throws Exception {
        File ods = writeZipStub(tmp.resolve("sheet.ods").toFile());
        assertEquals("okf_openoffice",
                FilterRegistry.promoteFilterForInput("okf_odf", ods));
    }

    @Test
    void promoteFilterForInput_odfFilterClassWithOdt_promotesToOpenoffice(@TempDir Path tmp) throws Exception {
        File odt = writeZipStub(tmp.resolve("doc.odt").toFile());
        assertEquals("okf_openoffice",
                FilterRegistry.promoteFilterForInput(
                        "net.sf.okapi.filters.openoffice.ODFFilter", odt));
    }

    @Test
    void promoteFilterForInput_odfWithFodt_keepsOdf(@TempDir Path tmp) throws Exception {
        // .fodt is a flat (non-zip) XML serialisation of ODF — ODFFilter
        // reads it directly, no promotion needed.
        File fodt = tmp.resolve("doc.fodt").toFile();
        try (FileOutputStream out = new FileOutputStream(fodt)) {
            out.write("<?xml version=\"1.0\"?><doc/>".getBytes());
        }
        assertEquals("okf_odf",
                FilterRegistry.promoteFilterForInput("okf_odf", fodt));
    }

    @Test
    void promoteFilterForInput_htmlWithHtml_keepsAsRequested(@TempDir Path tmp) throws Exception {
        File html = tmp.resolve("page.html").toFile();
        try (FileOutputStream out = new FileOutputStream(html)) {
            out.write("<html></html>".getBytes());
        }
        assertEquals("okf_html",
                FilterRegistry.promoteFilterForInput("okf_html", html));
    }

    @Test
    void promoteFilterForInput_nullInput_keepsAsRequested() {
        assertEquals("okf_odf",
                FilterRegistry.promoteFilterForInput("okf_odf", null));
    }

    @Test
    void promoteFilterForInput_nullFilter_returnsNull() {
        assertNull(FilterRegistry.promoteFilterForInput(null, new File("/tmp/x")));
    }

    @Test
    void promoteFilterForInput_zipMagicWithoutOoExt_promotes(@TempDir Path tmp) throws Exception {
        // PK\x03\x04 sniff catches OO containers with unusual names.
        File weird = writeZipStub(tmp.resolve("weird-name.bin").toFile());
        assertEquals("okf_openoffice",
                FilterRegistry.promoteFilterForInput("okf_odf", weird));
    }

    /** Write 4 bytes that look like a zip local-file-header magic. */
    private static File writeZipStub(File f) throws Exception {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(new byte[]{'P', 'K', 3, 4, 0, 0});
        }
        return f;
    }

    // ── roundtrip ────────────────────────────────────────────────────────

    @Test
    void toKind_roundtrip() {
        String kind = FilterRegistry.toKind("okf_properties");
        assertEquals("OkfPropertiesFilterConfig", kind);

        // Parse back: strip "Okf" prefix and "FilterConfig" suffix, lowercase
        String format = kind.substring(3, kind.length() - 12).toLowerCase();
        assertEquals("properties", format);
        assertEquals("okf_properties", "okf_" + format);
    }

    // ── read-only filters ────────────────────────────────────────────────

    @Test
    void isReadOnly_pdf_isReadOnly() {
        // Okapi's PDF filter is extraction-only (PDFBox text scraping, no
        // writer), so it must advertise "read" without "write".
        assertTrue(FilterRegistry.isReadOnly("okf_pdf"));
    }

    @Test
    void isReadOnly_html_isReadWrite() {
        assertFalse(FilterRegistry.isReadOnly("okf_html"));
    }
}
