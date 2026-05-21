package neokapi.bridge.util;

import net.sf.okapi.common.Event;
import net.sf.okapi.common.EventType;
import net.sf.okapi.common.IParameters;
import net.sf.okapi.common.LocaleId;
import net.sf.okapi.common.resource.ITextUnit;
import net.sf.okapi.common.resource.RawDocument;
import net.sf.okapi.filters.regex.RegexFilter;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link RegexRulesApplier} — rebuilding an Okapi
 * {@code RegexFilter} rule list from the {@code regexRulesJson} reserved
 * filter parameter (neokapi#616, Track 3 Stage B).
 *
 * <p>The applier closes the bridge transport gap: Okapi's rule-driven
 * RegexFilter has no flat-string parameter for its {@code rules}
 * {@code ArrayList<Rule>}, so the neokapi parity layer ships the rules as a
 * JSON array which this applier turns into real {@code Rule} objects. These
 * tests assert both the applier's effect on the filter parameters and the
 * resulting end-to-end extraction (open the filter on real input, collect the
 * extracted TextUnit source strings).
 *
 * <p>To converge with the native Go reader the rules use
 * {@code ruleType=1} (RULETYPE_CONTENT — the source group is emitted verbatim,
 * not treated as a delimited string), and the filter is configured exactly as
 * the neokapi bridge translator does: {@code regexOptions=0} (RE2-equivalent —
 * no global DOTALL/MULTILINE; inline {@code (?m)}/{@code (?s)} drive behaviour)
 * and empty start/end delimiters (so Okapi never runs its STRING-type delimiter
 * scan). STRING-type rules with an inner-capture source group extract nothing,
 * which is why CONTENT is the correct mapping.
 */
class RegexRulesApplierTest {

    private static final LocaleId EN = LocaleId.fromString("en");

    /**
     * Build a RegexFilter configured exactly as the neokapi bridge translator
     * does (regexOptions=0, empty delimiters), then apply the JSON rules.
     */
    private static RegexFilter filterWithRules(String rulesJson) {
        RegexFilter filter = new RegexFilter();
        IParameters params = filter.getParameters();
        applyTranslatorConvergence(params);
        int applied = RegexRulesApplier.apply(params, rulesJson);
        assertTrue(applied > 0, "expected at least one rule to be applied");
        return filter;
    }

    /** Mirror regexBridgeConfig's non-rule converging params on the filter. */
    private static void applyTranslatorConvergence(IParameters params) {
        try {
            params.getClass().getMethod("setRegexOptions", int.class).invoke(params, 0);
            params.getClass().getMethod("setStartString", String.class).invoke(params, "");
            params.getClass().getMethod("setEndString", String.class).invoke(params, "");
        } catch (Exception e) {
            fail("convergence reflection failed: " + e.getMessage());
        }
    }

    /** Open the filter on the given input and collect extracted source strings. */
    private static List<String> extract(RegexFilter filter, String input) {
        List<String> out = new ArrayList<>();
        RawDocument rd = new RawDocument(input, EN);
        try {
            filter.open(rd);
            while (filter.hasNext()) {
                Event ev = filter.next();
                if (ev.getEventType() == EventType.TEXT_UNIT) {
                    ITextUnit tu = ev.getTextUnit();
                    if (tu.isTranslatable()) {
                        out.add(tu.getSource().toString());
                    }
                }
            }
        } finally {
            filter.close();
        }
        return out;
    }

    // ── supports() gate ──────────────────────────────────────────────────────

    @Test
    void supports_regexParameters_returnsTrue() {
        assertTrue(RegexRulesApplier.supports(new RegexFilter().getParameters()));
    }

    @Test
    void supports_null_returnsFalse() {
        assertFalse(RegexRulesApplier.supports(null));
    }

    // ── Mac .strings: source + name groups (RULETYPE_CONTENT) ─────────────────

    @Test
    void apply_macStringsRule_extractsValueAsSource() {
        // sourceGroup=2 (value), nameGroup=1 (key) — the macStrings shape.
        String rules = "[{\"expr\":\"\\\"([^\\\"]*?)\\\"\\\\s*=\\\\s*\\\"((?:[^\\\"\\\\\\\\]|\\\\\\\\.)*)\\\"\\\\s*;\","
                + "\"ruleType\":1,\"sourceGroup\":2,\"nameGroup\":1,\"noteGroup\":-1}]";
        RegexFilter filter = filterWithRules(rules);

        List<String> src = extract(filter,
                "\"File\" = \"File\";\n\"Edit\" = \"Edit\";\n\"Help\" = \"Help\";\n");
        assertEquals(List.of("File", "Edit", "Help"), src);
    }

    @Test
    void apply_macStringsRule_setsNameFromIdGroup() {
        String rules = "[{\"expr\":\"\\\"([^\\\"]*?)\\\"\\\\s*=\\\\s*\\\"((?:[^\\\"\\\\\\\\]|\\\\\\\\.)*)\\\"\\\\s*;\","
                + "\"ruleType\":1,\"sourceGroup\":2,\"nameGroup\":1,\"noteGroup\":-1}]";
        RegexFilter filter = filterWithRules(rules);

        RawDocument rd = new RawDocument("\"key1\" = \"Hello World\";", EN);
        String name = null;
        try {
            filter.open(rd);
            while (filter.hasNext()) {
                Event ev = filter.next();
                if (ev.getEventType() == EventType.TEXT_UNIT) {
                    name = ev.getTextUnit().getName();
                    break;
                }
            }
        } finally {
            filter.close();
        }
        assertEquals("key1", name, "nameGroup=1 should populate the TextUnit name");
    }

    // ── Id + tab-separated text (sourceGroup not the highest group) ───────────

    @Test
    void apply_idAndTextRule_extractsSecondGroup() {
        String rules = "[{\"expr\":\"\\\\[(\\\\w+)\\\\]\\\\t(.+)\","
                + "\"ruleType\":1,\"sourceGroup\":2,\"nameGroup\":1,\"noteGroup\":-1}]";
        RegexFilter filter = filterWithRules(rules);

        List<String> src = extract(filter, "[ID1]\tFirst text\n[ID2]\tSecond text\n");
        assertEquals(List.of("First text", "Second text"), src);
    }

    // ── INI key=value with inline (?m) multiline flag ─────────────────────────

    @Test
    void apply_iniRule_inlineMultilineFlag_extractsKeyValues() {
        String rules = "[{\"expr\":\"(?m)^([^=\\\\[\\\\]#;\\\\s]+)\\\\s*=\\\\s*(.+)$\","
                + "\"ruleType\":1,\"sourceGroup\":2,\"nameGroup\":1,\"noteGroup\":-1}]";
        RegexFilter filter = filterWithRules(rules);

        List<String> src = extract(filter, "[Section1]\nkey1=Hello World\nkey2=Goodbye\n");
        assertEquals(List.of("Hello World", "Goodbye"), src);
    }

    // ── Note rule: still extracts the source block ────────────────────────────

    @Test
    void apply_noteRule_extractsSourceBlock() {
        // sourceGroup=3, nameGroup=2, noteGroup=1.
        String rules = "[{\"expr\":\"/\\\\*\\\\s*(.*?)\\\\s*\\\\*/\\\\s*\\\\n\\\\s*"
                + "\\\"([^\\\"]*?)\\\"\\\\s*=\\\\s*\\\"((?:[^\\\"\\\\\\\\]|\\\\\\\\.)*)\\\"\\\\s*;\","
                + "\"ruleType\":1,\"sourceGroup\":3,\"nameGroup\":2,\"noteGroup\":1}]";
        RegexFilter filter = filterWithRules(rules);

        List<String> src = extract(filter, "/* Menu item */\n\"File\" = \"File\";\n");
        assertEquals(List.of("File"), src);
    }

    // ── Multiple rules applied in document order ──────────────────────────────

    @Test
    void apply_multipleRules_preserveDocumentOrder() {
        String rules = "["
                + "{\"expr\":\"(?m)^(\\\\w+)=(.+)$\",\"ruleType\":1,\"sourceGroup\":2,\"nameGroup\":1,\"noteGroup\":-1},"
                + "{\"expr\":\"LABEL\\\\s+\\\"([^\\\"]+)\\\"\",\"ruleType\":1,\"sourceGroup\":1,\"nameGroup\":-1,\"noteGroup\":-1}"
                + "]";
        RegexFilter filter = filterWithRules(rules);

        List<String> src = extract(filter, "title=Hello\nLABEL \"World\"\ndesc=Goodbye\n");
        assertEquals(List.of("Hello", "World", "Goodbye"), src);
    }

    // ── Rules land on the params rule list, ruleType defaults to CONTENT ──────

    @Test
    void apply_appendsRulesToParameters_defaultRuleTypeContent() {
        RegexFilter filter = new RegexFilter();
        IParameters params = filter.getParameters();
        applyTranslatorConvergence(params);

        // No explicit ruleType → applier defaults to RULETYPE_CONTENT (1).
        int applied = RegexRulesApplier.apply(params,
                "[{\"expr\":\"(?m)^(\\\\w+)=(.+)$\",\"sourceGroup\":2}]");
        assertEquals(1, applied);
        String serialized = params.toString();
        assertTrue(serialized.contains("rule0"),
                "applied rule should appear in serialized parameters, got: " + serialized);
        assertTrue(serialized.contains("rule0.ruleType.i=1"),
                "default ruleType should serialize as CONTENT (1), got: " + serialized);
    }

    // ── Error handling ────────────────────────────────────────────────────────

    @Test
    void apply_emptyJson_appliesNothing() {
        RegexFilter filter = new RegexFilter();
        assertEquals(0, RegexRulesApplier.apply(filter.getParameters(), ""));
        assertEquals(0, RegexRulesApplier.apply(filter.getParameters(), null));
    }

    @Test
    void apply_notAnArray_throws() {
        RegexFilter filter = new RegexFilter();
        assertThrows(IllegalArgumentException.class,
                () -> RegexRulesApplier.apply(filter.getParameters(), "{\"expr\":\"x\"}"));
    }

    @Test
    void apply_invalidJson_throws() {
        RegexFilter filter = new RegexFilter();
        assertThrows(IllegalArgumentException.class,
                () -> RegexRulesApplier.apply(filter.getParameters(), "not json"));
    }

    @Test
    void apply_entryMissingExpr_throws() {
        RegexFilter filter = new RegexFilter();
        assertThrows(IllegalArgumentException.class,
                () -> RegexRulesApplier.apply(filter.getParameters(), "[{\"sourceGroup\":1}]"));
    }

    @Test
    void apply_unsupportedParams_throws() {
        // A non-regex IParameters object must be rejected by the supports() gate.
        IParameters plain = new net.sf.okapi.common.StringParameters();
        assertThrows(IllegalArgumentException.class,
                () -> RegexRulesApplier.apply(plain, "[{\"expr\":\"x\",\"sourceGroup\":1}]"));
    }
}
