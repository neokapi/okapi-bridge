package neokapi.bridge;

import net.sf.okapi.common.Event;
import net.sf.okapi.common.EventType;
import net.sf.okapi.common.IResource;
import net.sf.okapi.common.LocaleId;
import net.sf.okapi.common.pipeline.BasePipelineStep;
import net.sf.okapi.common.pipeline.annotations.StepParameterMapping;
import net.sf.okapi.common.pipeline.annotations.StepParameterType;
import net.sf.okapi.common.resource.ITextUnit;
import net.sf.okapi.common.resource.Property;
import net.sf.okapi.common.resource.Segment;
import net.sf.okapi.common.resource.TextContainer;
import net.sf.okapi.common.resource.TextFragment;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Custom pseudo-translation step for the neokapi bridge.
 *
 * <p>Replicates the SCRIPT_EXT_LATIN character replacement from Okapi's
 * {@code TextModificationStep} but with two important differences:
 * <ol>
 *   <li>Only transforms <em>segments</em>, not ignorable TextParts. Per the
 *       XLIFF 2.0 spec (§4.2.2.7) ignorable content should not be translated.
 *       Upstream {@code TextModificationStep} iterates all TextParts
 *       indiscriminately — this is a long-standing bug.</li>
 *   <li>Preserves target {@link TextContainer} properties (approved/fuzzy flags)
 *       that upstream's {@code createTarget(COPY_ALL)} would discard. This
 *       replaces the functionality of {@code PropertyPreservingTextModificationStep}.</li>
 * </ol>
 *
 * <p>This step is used both in the reference pipeline ({@link PseudoCommand})
 * and defines the canonical pseudo-translation semantics for parity testing.
 */
public final class PseudoTranslationStep extends BasePipelineStep {

    // Latin extended character map (SCRIPT_EXT_LATIN = index 0)
    private static final String OLD_CHARS =
            "AaBbCcDdEeFfGgHhIiJjKkLlNnOoPpQqRrSsTtUuWwYyZz";
    private static final String NEW_CHARS =
            "\u00c0\u00e0\u00df\u0180\u0106\u0107\u010e\u010f\u0112\u0113\u0191\u0192\u011c\u011d\u0124\u0125"
                    + "\u0128\u0129\u0135\u0134\u0136\u0137\u0139\u013a\u0143\u0144\u014c\u014d\u01a4\u01a5\u01ea\u01eb\u0154\u0155"
                    + "\u015a\u015b\u0162\u0163\u0168\u0169\u0174\u0175\u0176\u0177\u0179\u017a";

    private LocaleId targetLocale;

    @StepParameterMapping(parameterType = StepParameterType.TARGET_LOCALE)
    public void setTargetLocale(LocaleId targetLocale) {
        this.targetLocale = targetLocale;
    }

    @Override
    public String getName() {
        return "Pseudo Translation";
    }

    @Override
    public String getDescription() {
        return "Applies SCRIPT_EXT_LATIN pseudo-translation to segments only (skips ignorables).";
    }

    @Override
    public Event handleEvent(Event event) {
        if (event.getEventType() != EventType.TEXT_UNIT) {
            return event;
        }

        ITextUnit tu = event.getTextUnit();
        if (!tu.isTranslatable()) {
            return event;
        }

        // Snapshot existing target properties before we replace the container.
        Map<String, Property> savedProps = snapshotTargetProperties(tu);

        // Create target from source (COPY_ALL copies segments + ignorables).
        tu.createTarget(targetLocale, false, IResource.COPY_ALL);
        if (tu.getTarget(targetLocale).isEmpty() || !tu.getTarget(targetLocale).hasText()) {
            tu.createTarget(targetLocale, true, IResource.COPY_ALL);
        }

        // Transform ONLY segments — ignorable TextParts stay as-is.
        for (Segment seg : tu.getTarget(targetLocale).getSegments()) {
            replaceWithExtendedChars(seg.getContent());
        }

        // Restore properties that were on the pre-existing target.
        TextContainer postTarget = tu.getTarget(targetLocale);
        if (postTarget != null && !savedProps.isEmpty()) {
            for (Property p : savedProps.values()) {
                if (postTarget.getProperty(p.getName()) == null) {
                    postTarget.setProperty(p);
                }
            }
        }

        return event;
    }

    /**
     * Replace Latin letters with their extended-character equivalents,
     * preserving inline code markers.
     */
    private static void replaceWithExtendedChars(TextFragment fragment) {
        StringBuilder sb = new StringBuilder(fragment.getCodedText());
        for (int i = 0; i < sb.length(); i++) {
            if (TextFragment.isMarker(sb.charAt(i))) {
                i++; // Skip code marker + index char
            } else {
                int n = OLD_CHARS.indexOf(sb.charAt(i));
                if (n > -1) {
                    sb.setCharAt(i, NEW_CHARS.charAt(n));
                }
            }
        }
        fragment.setCodedText(sb.toString());
    }

    private Map<String, Property> snapshotTargetProperties(ITextUnit tu) {
        if (targetLocale == null) {
            return java.util.Collections.emptyMap();
        }
        TextContainer existing = tu.getTarget(targetLocale);
        if (existing == null) {
            return java.util.Collections.emptyMap();
        }
        java.util.Set<String> names = existing.getPropertyNames();
        if (names.isEmpty()) {
            return java.util.Collections.emptyMap();
        }
        Map<String, Property> snapshot = new LinkedHashMap<>(names.size());
        for (String name : names) {
            Property p = existing.getProperty(name);
            if (p != null) {
                snapshot.put(name, p.clone());
            }
        }
        return snapshot;
    }
}
