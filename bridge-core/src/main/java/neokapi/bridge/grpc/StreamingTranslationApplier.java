package neokapi.bridge.grpc;

import neokapi.bridge.model.FragmentDTO;
import neokapi.bridge.util.OkapiCodeConverter;
import net.sf.okapi.common.Event;
import net.sf.okapi.common.EventType;
import net.sf.okapi.common.LocaleId;
import net.sf.okapi.common.resource.ITextUnit;
import net.sf.okapi.common.resource.Segment;
import net.sf.okapi.common.resource.TextContainer;
import net.sf.okapi.common.resource.TextFragment;

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
    private final Map<String, List<FragmentDTO>> lookahead = new LinkedHashMap<>();
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
        List<FragmentDTO> fragments = findFragments(tu.getId());
        if (fragments == null || fragments.isEmpty()) {
            return event;
        }

        // Apply fragments to text unit (same logic as PartDTOConverter).
        TextContainer targetContainer = new TextContainer();
        tu.setTarget(targetLocale, targetContainer);

        if (fragments.size() == 1) {
            TextFragment tf = OkapiCodeConverter.toTextFragment(fragments.get(0));
            targetContainer.setContent(tf);
        } else {
            TextContainer source = tu.getSource();
            targetContainer.setContent(source.getUnSegmentedContentCopy());

            Iterator<Segment> segs = targetContainer.getSegments().iterator();
            int idx = 0;
            while (segs.hasNext() && idx < fragments.size()) {
                Segment seg = segs.next();
                TextFragment tf = OkapiCodeConverter.toTextFragment(fragments.get(idx));
                seg.setContent(tf);
                idx++;
            }
        }

        return event;
    }

    /**
     * Find the target fragments for the given block ID.
     * First checks the lookahead map, then pulls from the queue until found
     * or end-of-stream is reached.
     */
    private List<FragmentDTO> findFragments(String blockId) {
        // Check lookahead first (for out-of-order arrivals).
        List<FragmentDTO> cached = lookahead.remove(blockId);
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
                return entry.fragments();
            }
            lookahead.put(entry.blockId(), entry.fragments());
        }
    }
}
