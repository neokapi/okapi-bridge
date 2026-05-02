package neokapi.bridge;

import net.sf.okapi.common.Event;
import net.sf.okapi.common.LocaleId;
import net.sf.okapi.common.pipeline.annotations.StepParameterMapping;
import net.sf.okapi.common.pipeline.annotations.StepParameterType;
import net.sf.okapi.common.resource.ITextUnit;
import net.sf.okapi.common.resource.Property;
import net.sf.okapi.common.resource.TextContainer;
import net.sf.okapi.steps.textmodification.TextModificationStep;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * TextModificationStep that preserves target {@link TextContainer} properties
 * across the pseudo-translate transform.
 *
 * <p>Upstream {@code TextModificationStep} calls
 * {@code tu.createTarget(locale, true /*overwrite*&#47;, COPY_ALL)} when the
 * existing target is empty (or absent), which replaces the target container
 * and discards any properties the filter attached on read. For PO and TS
 * filters, that property bag carries the {@code approved} value backing
 * {@code #, fuzzy} flags and {@code type="unfinished"} attributes; without
 * it, {@code GenericSkeletonWriter} emits {@code -ERR:PROP-NOT-FOUND-}
 * placeholders in those slots.
 *
 * <p>This subclass snapshots the target's properties before delegating to
 * {@code super.handleTextUnit(...)} and re-applies them to whatever target
 * exists afterwards. Properties already set on the post-pseudo target win,
 * to avoid clobbering values the step itself updated.
 */
public final class PropertyPreservingTextModificationStep extends TextModificationStep {

    private LocaleId targetLocale;

    @Override
    @StepParameterMapping(parameterType = StepParameterType.TARGET_LOCALE)
    public void setTargetLocale(LocaleId targetLocale) {
        super.setTargetLocale(targetLocale);
        this.targetLocale = targetLocale;
    }

    @Override
    protected Event handleTextUnit(Event event) {
        ITextUnit tu = event.getTextUnit();
        Map<String, Property> savedProps = snapshotProperties(tu);
        Event out = super.handleTextUnit(event);
        if (!savedProps.isEmpty()) {
            TextContainer postTarget = tu.getTarget(targetLocale);
            if (postTarget != null) {
                for (Property p : savedProps.values()) {
                    if (postTarget.getProperty(p.getName()) == null) {
                        postTarget.setProperty(p);
                    }
                }
            }
        }
        return out;
    }

    private Map<String, Property> snapshotProperties(ITextUnit tu) {
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
