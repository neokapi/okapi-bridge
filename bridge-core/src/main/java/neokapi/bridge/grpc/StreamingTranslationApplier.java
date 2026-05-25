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

import java.lang.reflect.Method;
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

        // Go now echoes ignorable parts back alongside segments (so it can
        // see the unit's full structure on the read path). Drop them here:
        // the COPY_ALL clone below already source-copies ignorable TextParts,
        // and the apply loop maps DTOs to getSegments() positionally, so
        // leaving ignorable DTOs in the list would misalign that mapping.
        segments = SegmentDTO.translatable(segments);
        if (segments.isEmpty()) {
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
        if (sourceContainer != null && sourceContainer.contentIsOneSegment() && segments.size() == 1) {
            // True single-segment unit: create a fresh container.
            if (segments.get(0).getContent() == null) {
                // Empty DTO (segment had no runs) — skip target creation.
                return event;
            }
            targetContainer = new TextContainer();
            // Use getFirstContent() (the original segment's TextFragment) rather
            // than getUnSegmentedContentCopy() (which calls createJoinedContent
            // → TextFragment.insert with keepCodeIds=false → renumbers CLOSING
            // codes via balanceMarkers). EventConverter sends each segment's
            // pre-join codes to Go, so the inbound SpanDTO ids match what was
            // emitted; if the applier joins+renumbers here, the (id, tagType)
            // lookup in OkapiCodeConverter.findUnusedSourceCode misses and
            // falls through to buildFreshCode — which loses outerData and
            // causes TTXSkeletonWriter to emit the code's raw data verbatim
            // (the "literal </a>" bug on unsegmented TTX fixtures).
            // contentIsOneSegment() guarantees parts.size()==1 here, so
            // getFirstContent() returns the same logical content.
            TextFragment sourceFragment = sourceContainer.getFirstContent();
            TextFragment existingTargetFragment = existingTarget != null
                    && existingTarget.contentIsOneSegment()
                    ? existingTarget.getFirstContent()
                    : (existingTarget != null
                            ? existingTarget.getUnSegmentedContentCopy()
                            : null);
            TextFragment tf = OkapiCodeConverter.toTextFragment(
                    segments.get(0).getContent(), sourceFragment, existingTargetFragment);
            targetContainer.setContent(tf);
            // Align segment ID with source so writers (XLIFF2, etc.) can
            // match source↔target pairs. setContent() resets to id "0";
            // the real ID lives on the source's first segment.
            Segment srcSeg = sourceContainer.getFirstSegment();
            Segment tgtSeg = targetContainer.getFirstSegment();
            if (srcSeg != null && tgtSeg != null) {
                tgtSeg.id = srcSeg.id;
                copySegmentOriginalId(srcSeg, tgtSeg);
            }
        } else {
            // Multi-segment (or single DTO for multi-segment source):
            // Clone the existing target when available so we preserve its
            // structure (which segments have content, pre-existing ignorable
            // targets, etc.). Otherwise clone source to materialise targets
            // for all parts — matching PseudoTranslationStep's createTarget
            // with COPY_ALL semantics.
            if (existingTarget != null && existingTarget.hasText()) {
                targetContainer = existingTarget.clone();
            } else {
                targetContainer = sourceContainer != null
                        ? sourceContainer.clone() : new TextContainer();
            }
            // Drop source/existing-side properties — we'll carry over the
            // existing target's properties (approved/fuzzy/etc.) below.
            for (String name : new java.util.ArrayList<>(targetContainer.getPropertyNames())) {
                targetContainer.removeProperty(name);
            }

            // Apply DTOs positionally to target segments. The DTO order
            // matches the cloned container's segment order because Go
            // pseudo-translates the same segment set (existing target when
            // available, otherwise source) in extraction order.
            Iterator<Segment> tgtSegs = targetContainer.getSegments().iterator();
            Iterator<Segment> srcSegs = sourceContainer != null
                    ? sourceContainer.getSegments().iterator() : Collections.<Segment>emptyIterator();
            int idx = 0;
            while (tgtSegs.hasNext() && idx < segments.size()) {
                Segment tgtSeg = tgtSegs.next();
                Segment srcSeg = srcSegs.hasNext() ? srcSegs.next() : null;
                SegmentDTO segDTO = segments.get(idx);
                // Skip empty DTOs (empty-run segments) — the segment keeps
                // whatever content it inherited from the cloned container.
                if (segDTO.getContent() == null) {
                    String segId = segDTO.getId();
                    if (segId != null && !segId.isEmpty()) {
                        tgtSeg.id = segId;
                    }
                    idx++;
                    continue;
                }
                TextFragment sourceFragment = srcSeg != null ? srcSeg.getContent() : null;
                // Use the cloned segment's current content as the existing
                // target reference — preserves code identity for writers.
                TextFragment existingTargetFragment = tgtSeg.getContent();
                TextFragment tf = OkapiCodeConverter.toTextFragment(
                        segDTO.getContent(), sourceFragment, existingTargetFragment);
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

    // copySegmentOriginalId aligns the target segment's originalId with the
    // source's, used so writers (XLIFF2, etc.) can match source↔target pairs.
    // Segment-level originalId was added to Okapi after the older releases the
    // bridge still supports (≤1.41 have no Segment.getOriginalId/setOriginalId),
    // so reflect over it and no-op when absent — those versions have no segment
    // originalId to preserve. Newer versions behave exactly as a direct call.
    private static void copySegmentOriginalId(Segment src, Segment tgt) {
        try {
            Method get = Segment.class.getMethod("getOriginalId");
            Method set = Segment.class.getMethod("setOriginalId", String.class);
            set.invoke(tgt, get.invoke(src));
        } catch (NoSuchMethodException e) {
            // Older Okapi: Segment has no originalId — nothing to align.
        } catch (ReflectiveOperationException e) {
            // Defensive: never fail translation application over ID alignment.
        }
    }
}
