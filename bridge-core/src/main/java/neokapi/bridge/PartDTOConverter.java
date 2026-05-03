package neokapi.bridge;

import neokapi.bridge.model.*;
import neokapi.bridge.util.OkapiCodeConverter;

import net.sf.okapi.common.Event;
import net.sf.okapi.common.EventType;
import net.sf.okapi.common.LocaleId;
import net.sf.okapi.common.resource.*;

import java.util.*;

/**
 * Applies translations from PartDTOs back to Okapi Events during the write path.
 * Matches text units by ID and applies target translations.
 */
public class PartDTOConverter {

    private final Map<String, BlockDTO> blocksByID;
    private final LocaleId targetLocale;

    /**
     * Create a converter with a list of parts containing translations.
     *
     * @param parts        the translated parts from the Go side
     * @param targetLocale the target locale to apply
     */
    public PartDTOConverter(List<PartDTO> parts, LocaleId targetLocale) {
        this.targetLocale = targetLocale;
        this.blocksByID = new LinkedHashMap<>();

        for (PartDTO part : parts) {
            if (part.getPartType() == PartDTO.TYPE_BLOCK && part.getBlock() != null) {
                blocksByID.put(part.getBlock().getId(), part.getBlock());
            }
        }
    }

    /**
     * Apply translations to an Okapi event if it's a TEXT_UNIT with a matching block.
     * Returns the (possibly modified) event.
     */
    public Event applyTranslations(Event event) {
        if (event.getEventType() != EventType.TEXT_UNIT) {
            return event;
        }

        ITextUnit tu = event.getTextUnit();
        BlockDTO block = blocksByID.get(tu.getId());
        if (block == null || block.getTargets() == null) {
            return event;
        }

        // Find the target for our locale.
        TargetDTO matchingTarget = null;
        for (TargetDTO target : block.getTargets()) {
            if (target.getLocale() != null && target.getLocale().equals(targetLocale.toString())) {
                matchingTarget = target;
                break;
            }
        }

        if (matchingTarget == null || matchingTarget.getSegments() == null) {
            return event;
        }

        // Apply target segments. Pass the source TextFragment so the
        // converter can preserve code identity (originalId, type, outerData,
        // referenceFlag) when the structural shape matches — see
        // OkapiCodeConverter.toTextFragment for why writers depend on it.
        TextContainer sourceContainer = tu.getSource();
        TextContainer existingTarget = tu.getTarget(targetLocale);
        List<SegmentDTO> targetSegments = matchingTarget.getSegments();

        TextContainer targetContainer;
        if (targetSegments.size() == 1) {
            targetContainer = new TextContainer();
            TextFragment sourceFragment = sourceContainer != null
                    ? sourceContainer.getUnSegmentedContentCopy() : null;
            TextFragment tf = OkapiCodeConverter.toTextFragment(
                    targetSegments.get(0).getContent(), sourceFragment);
            targetContainer.setContent(tf);
        } else {
            // Multi-segment target: clone the source container so we
            // inherit its segments AND inter-segment text parts (the
            // whitespace between <mrk> elements in XLIFF, ICU plural
            // selectors, etc.) — then replace each segment's content +
            // id with the SegmentDTO data from Go. Mirrors
            // StreamingTranslationApplier.
            //
            // The previous shape — setContent(unSegmentedSourceCopy)
            // followed by getSegments().iterator() — collapsed the
            // target to a single segment, so multi-segment translations
            // only got the first DTO's content AND every segment id
            // got re-renumbered by Okapi's writer (segmentation2.xlf
            // surfaced this as <mrk mid="0"> where the reference had
            // mid="1"; RB-12-Test02 dropped the inter-segment space).
            targetContainer = sourceContainer != null
                    ? sourceContainer.clone() : new TextContainer();
            for (String name : new ArrayList<>(targetContainer.getPropertyNames())) {
                targetContainer.removeProperty(name);
            }
            Iterator<Segment> tgtSegs = targetContainer.getSegments().iterator();
            Iterator<Segment> srcSegs = sourceContainer != null
                    ? sourceContainer.getSegments().iterator() : Collections.<Segment>emptyIterator();
            int idx = 0;
            while (tgtSegs.hasNext() && idx < targetSegments.size()) {
                Segment tgtSeg = tgtSegs.next();
                Segment srcSeg = srcSegs.hasNext() ? srcSegs.next() : null;
                SegmentDTO segDTO = targetSegments.get(idx);
                TextFragment sourceFragment = srcSeg != null ? srcSeg.getContent() : null;
                TextFragment tf = OkapiCodeConverter.toTextFragment(segDTO.getContent(), sourceFragment);
                tgtSeg.setContent(tf);
                String segId = segDTO.getId();
                if (segId != null && !segId.isEmpty()) {
                    tgtSeg.id = segId;
                }
                idx++;
            }
        }

        // Carry over properties (e.g. PO "approved" -> #, fuzzy flag, TS
        // "approved" -> type="unfinished") from any existing target the filter
        // attached when reading the source. Mirrors StreamingTranslationApplier.
        if (existingTarget != null) {
            for (String name : existingTarget.getPropertyNames()) {
                Property p = existingTarget.getProperty(name);
                if (p != null) {
                    targetContainer.setProperty(p.clone());
                }
            }
        }
        tu.setTarget(targetLocale, targetContainer);

        return event;
    }
}
