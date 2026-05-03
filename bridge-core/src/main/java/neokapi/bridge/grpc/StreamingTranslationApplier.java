package neokapi.bridge.grpc;

import neokapi.bridge.model.SegmentDTO;
import neokapi.bridge.util.OkapiCodeConverter;
import net.sf.okapi.common.Event;
import net.sf.okapi.common.EventType;
import net.sf.okapi.common.LocaleId;
import net.sf.okapi.common.resource.ITextUnit;
import net.sf.okapi.common.resource.Property;
import net.sf.okapi.common.resource.Segment;
import net.sf.okapi.common.resource.TextContainer;
import net.sf.okapi.common.resource.TextFragment;
import net.sf.okapi.common.resource.TextPart;

import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Applies translations from a streaming queue to Okapi Events during the write path.
 * Replaces {@link neokapi.bridge.PartDTOConverter} for the streaming write path,
 * pulling translations on-demand from a {@link BlockingQueue} instead of requiring
 * a pre-built in-memory index of all parts.
 *
 * <p>Maintains a small lookahead map for out-of-order arrivals. In practice the
 * lookahead stays nearly empty because Go sends parts in extraction order, which
 * matches the skeleton re-read order.</p>
 */
public class StreamingTranslationApplier {

    private static final long DEFAULT_POLL_TIMEOUT_SECONDS = 120;

    private final BlockingQueue<TranslationEntry> queue;
    private final LocaleId targetLocale;
    private final long pollTimeoutSeconds;
    private final Map<String, List<SegmentDTO>> lookahead = new LinkedHashMap<>();
    private boolean endOfStream = false;

    public StreamingTranslationApplier(BlockingQueue<TranslationEntry> queue,
                                       LocaleId targetLocale, long pollTimeoutSeconds) {
        this.queue = queue;
        this.targetLocale = targetLocale;
        this.pollTimeoutSeconds = pollTimeoutSeconds;
    }

    public StreamingTranslationApplier(BlockingQueue<TranslationEntry> queue, LocaleId targetLocale) {
        this(queue, targetLocale, DEFAULT_POLL_TIMEOUT_SECONDS);
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
        List<SegmentDTO> segments = findSegments(tu.getId());
        if (segments == null || segments.isEmpty()) {
            return event;
        }

        // Apply segments to text unit. Pass the source TextFragment so the
        // converter can preserve code identity (originalId, type, outerData,
        // referenceFlag) when the structural shape matches — writers that
        // re-emit codes via the source's Codes list (HtmlSkeletonWriter,
        // OpenXML, IDML, MIF, …) need that metadata to find the code.
        TextContainer sourceContainer = tu.getSource();
        TextContainer existingTarget = tu.getTarget(targetLocale);

        TextContainer targetContainer;
        if (segments.size() == 1) {
            targetContainer = new TextContainer();
            TextFragment sourceFragment = sourceContainer != null
                    ? sourceContainer.getUnSegmentedContentCopy() : null;
            TextFragment tf = OkapiCodeConverter.toTextFragment(segments.get(0).getContent(), sourceFragment);
            targetContainer.setContent(tf);
        } else {
            // Multi-segment target: clone the source container so we
            // inherit its segments AND inter-segment text parts (the
            // whitespace between <mrk> elements in XLIFF, ICU plural
            // selectors, etc.) — then replace each segment's content +
            // id with the SegmentDTO data from Go.
            //
            // The previous shape — setContent(unSegmentedSourceCopy)
            // followed by getSegments().iterator() — collapsed the
            // target to a single segment, so multi-segment translations
            // only got the first DTO's content AND every segment id got
            // re-renumbered by Okapi's writer (segmentation2.xlf
            // surfaced this as <mrk mid="0"> where the reference had
            // mid="1"; RB-12-Test02 dropped the inter-segment space).
            targetContainer = sourceContainer != null
                    ? sourceContainer.clone() : new TextContainer();
            // Drop source-side properties — we'll carry over the existing
            // target's properties (approved/fuzzy/etc.) below.
            for (String name : new java.util.ArrayList<>(targetContainer.getPropertyNames())) {
                targetContainer.removeProperty(name);
            }
            Iterator<Segment> tgtSegs = targetContainer.getSegments().iterator();
            Iterator<Segment> srcSegs = sourceContainer != null
                    ? sourceContainer.getSegments().iterator() : Collections.<Segment>emptyIterator();
            int idx = 0;
            while (tgtSegs.hasNext() && idx < segments.size()) {
                Segment tgtSeg = tgtSegs.next();
                Segment srcSeg = srcSegs.hasNext() ? srcSegs.next() : null;
                SegmentDTO segDTO = segments.get(idx);
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
        // attached when reading the source. Without this, GenericSkeletonWriter
        // emits -ERR:PROP-NOT-FOUND- for skeleton placeholders that reference
        // these target properties.
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

    /**
     * Find the target segments for the given block ID.
     * First checks the lookahead map, then pulls from the queue until found
     * or end-of-stream is reached.
     */
    private List<SegmentDTO> findSegments(String blockId) {
        // Check lookahead first (for out-of-order arrivals).
        List<SegmentDTO> cached = lookahead.remove(blockId);
        if (cached != null) {
            return cached;
        }
        if (endOfStream) {
            return null;
        }

        // Pull from queue until we find matching block or hit end-of-stream.
        while (true) {
            TranslationEntry entry;
            try {
                entry = queue.poll(pollTimeoutSeconds, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                endOfStream = true;
                return null;
            }
            if (entry == null) {
                // Poll timed out — this indicates a bug or deadlock, not normal end-of-stream.
                // Fail loudly rather than silently dropping translations.
                throw new RuntimeException(
                        "Timed out waiting for translation entry for block: " + blockId);
            }
            if (entry == TranslationEntry.END) {
                endOfStream = true;
                return null;
            }
            if (entry.blockId().equals(blockId)) {
                return entry.segments();
            }
            lookahead.put(entry.blockId(), entry.segments());
        }
    }
}
