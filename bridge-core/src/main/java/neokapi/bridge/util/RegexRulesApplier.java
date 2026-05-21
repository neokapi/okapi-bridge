package neokapi.bridge.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.sf.okapi.common.IParameters;

import java.lang.reflect.Method;
import java.util.List;

/**
 * Applies a JSON-encoded list of regex extraction rules onto an Okapi
 * {@code RegexFilter} {@link IParameters} object.
 *
 * <h2>Why this exists</h2>
 * Okapi's {@code RegexFilter} is rule-driven: its translatable strings are
 * defined by a {@code rules} {@code ArrayList<Rule>} held on the filter's
 * {@code net.sf.okapi.filters.regex.Parameters}. That list serialises through
 * Okapi's {@code StringParameters} "preset" group format
 * ({@code ruleCount.i}, {@code rule0.expr}, {@code rule0.ruleType.i},
 * {@code rule0.groupSource.i}, …) — a fragile, ordered, nested encoding that
 * cannot ride through the bridge's flat gRPC {@code FilterParams}
 * {@code map<string,string>}. With no rules configured the filter emits zero
 * Blocks.
 *
 * <p>This applier closes that transport gap: the neokapi parity layer ships the
 * rules as a self-contained JSON array under the reserved {@code regexRulesJson}
 * filter parameter (mirroring the {@code fprmContent} reserved-key pattern, so
 * no proto change is needed). The bridge parses the JSON here and rebuilds real
 * {@code net.sf.okapi.filters.regex.Rule} objects, appends them to the filter's
 * rule list, and calls {@code compileRules()} so the filter is ready to extract.
 *
 * <h2>JSON contract (key: {@code regexRulesJson})</h2>
 * <pre>
 * [
 *   {
 *     "expr":        "&lt;Java/RE2 regex&gt;",  // Rule.setExpression
 *     "ruleType":    1,                         // Rule.setRuleType (RULETYPE_CONTENT, default)
 *     "sourceGroup": 2,                         // Rule.setSourceGroup
 *     "nameGroup":   1,                         // Rule.setNameGroup (-1 = none)
 *     "noteGroup":  -1                          // Rule.setNoteGroup (-1 = none)
 *   },
 *   …
 * ]
 * </pre>
 *
 * <p>Okapi rule-type constants (from {@code net.sf.okapi.filters.regex.Rule}):
 * {@code RULETYPE_STRING=0}, {@code RULETYPE_CONTENT=1}, {@code RULETYPE_COMMENT=2},
 * {@code RULETYPE_NOTRANS=3}, {@code RULETYPE_OPENGROUP=4},
 * {@code RULETYPE_CLOSEGROUP=5}. neokapi rules default to CONTENT: the source
 * group is emitted verbatim (inner-capture semantics), matching the native Go
 * reader. STRING would treat the source group as a delimited string and strip
 * start/end delimiters from it, so it requires the source group to capture the
 * surrounding quotes — which neokapi rules do not.
 *
 * <h2>Reflection</h2>
 * The bridge-core module does not depend on {@code okapi-filter-regex} at
 * compile time (the regex filter is only on the classpath in the shaded,
 * per-version build), so {@code Rule} is constructed and configured reflectively
 * — the same approach {@link ParameterApplier} uses for filter-specific setters.
 */
public final class RegexRulesApplier {

    /** Reserved gRPC FilterParams key carrying the JSON rule array. */
    public static final String RULES_PARAM = "regexRulesJson";

    private static final String RULE_CLASS = "net.sf.okapi.filters.regex.Rule";

    /**
     * Default rule type when an entry omits {@code ruleType}:
     * {@code RULETYPE_CONTENT} (= {@code net.sf.okapi.filters.regex.Rule
     * .RULETYPE_CONTENT}). CONTENT emits the rule's source group verbatim with
     * no delimiter scanning, matching neokapi's inner-capture semantics.
     * (RULETYPE_STRING=0 would require the source group to capture the
     * surrounding start/end delimiters, which neokapi rules do not.)
     */
    private static final int RULETYPE_CONTENT = 1;

    private RegexRulesApplier() {
    }

    /**
     * Returns {@code true} when {@code params} is a RegexFilter
     * {@code Parameters} object that exposes the rule-list API this applier
     * drives ({@code getRules()} + {@code compileRules()}).
     */
    public static boolean supports(IParameters params) {
        if (params == null) {
            return false;
        }
        return hasMethod(params.getClass(), "getRules")
                && hasMethod(params.getClass(), "compileRules");
    }

    /**
     * Parse {@code rulesJson} (a JSON array per the contract above) and append a
     * {@code Rule} per entry to the RegexFilter parameters' rule list, then
     * compile the rules so the filter is ready to extract.
     *
     * @param params  the RegexFilter's {@link IParameters} (must {@link #supports})
     * @param rulesJson the JSON array string from the {@code regexRulesJson} param
     * @return number of rules applied
     * @throws IllegalArgumentException if params is unsupported or JSON is malformed
     * @throws RuntimeException wrapping any reflective failure building Rule objects
     */
    @SuppressWarnings("unchecked")
    public static int apply(IParameters params, String rulesJson) {
        if (!supports(params)) {
            throw new IllegalArgumentException(
                    "params does not expose the RegexFilter rule-list API (getRules/compileRules): "
                            + (params == null ? "null" : params.getClass().getName()));
        }
        if (rulesJson == null || rulesJson.isEmpty()) {
            return 0;
        }

        JsonElement root;
        try {
            root = JsonParser.parseString(rulesJson);
        } catch (Exception e) {
            throw new IllegalArgumentException("regexRulesJson is not valid JSON: " + e.getMessage(), e);
        }
        if (!root.isJsonArray()) {
            throw new IllegalArgumentException("regexRulesJson must be a JSON array, got: " + root);
        }
        JsonArray arr = root.getAsJsonArray();

        try {
            Class<?> ruleClass = Class.forName(RULE_CLASS, true, params.getClass().getClassLoader());

            Method getRules = params.getClass().getMethod("getRules");
            List<Object> rules = (List<Object>) getRules.invoke(params);
            if (rules == null) {
                throw new IllegalStateException("RegexFilter Parameters.getRules() returned null");
            }

            Method setExpression = ruleClass.getMethod("setExpression", String.class);
            Method setRuleType = ruleClass.getMethod("setRuleType", int.class);
            Method setSourceGroup = ruleClass.getMethod("setSourceGroup", int.class);
            Method setNameGroup = ruleClass.getMethod("setNameGroup", int.class);
            Method setNoteGroup = ruleClass.getMethod("setNoteGroup", int.class);

            int applied = 0;
            for (JsonElement el : arr) {
                if (!el.isJsonObject()) {
                    throw new IllegalArgumentException("regexRulesJson entry is not an object: " + el);
                }
                JsonObject obj = el.getAsJsonObject();

                String expr = stringField(obj, "expr");
                if (expr == null || expr.isEmpty()) {
                    throw new IllegalArgumentException("regexRulesJson entry missing required \"expr\": " + obj);
                }

                Object rule = ruleClass.getDeclaredConstructor().newInstance();
                setExpression.invoke(rule, expr);
                setRuleType.invoke(rule, intField(obj, "ruleType", RULETYPE_CONTENT));
                setSourceGroup.invoke(rule, intField(obj, "sourceGroup", -1));
                setNameGroup.invoke(rule, intField(obj, "nameGroup", -1));     // Okapi sentinel: -1 = none
                setNoteGroup.invoke(rule, intField(obj, "noteGroup", -1));

                rules.add(rule);
                applied++;
            }

            params.getClass().getMethod("compileRules").invoke(params);
            return applied;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to build RegexFilter rules from regexRulesJson: " + e.getMessage(), e);
        }
    }

    private static String stringField(JsonObject obj, String key) {
        JsonElement el = obj.get(key);
        return (el != null && el.isJsonPrimitive()) ? el.getAsString() : null;
    }

    private static int intField(JsonObject obj, String key, int dflt) {
        JsonElement el = obj.get(key);
        if (el == null || !el.isJsonPrimitive()) {
            return dflt;
        }
        return el.getAsInt();
    }

    private static boolean hasMethod(Class<?> cls, String name, Class<?>... paramTypes) {
        try {
            cls.getMethod(name, paramTypes);
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }
}
