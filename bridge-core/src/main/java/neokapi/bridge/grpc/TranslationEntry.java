package neokapi.bridge.grpc;

import neokapi.bridge.model.SegmentDTO;

import java.util.Collections;
import java.util.List;

/**
 * Carries a block's target segments through the streaming write queue.
 * Used by {@link StreamingTranslationApplier} to apply translations on-demand
 * as the skeleton re-read encounters TEXT_UNIT events.
 *
 * <p>Carries SegmentDTO (id + content) rather than just FragmentDTO so the
 * write path can preserve segment IDs (XLIFF mid="…", etc.) that the Go
 * side computed.</p>
 */
final class TranslationEntry {

    /** Sentinel value signaling end-of-stream. */
    static final TranslationEntry END = new TranslationEntry("", Collections.emptyList());

    private final String blockId;
    private final List<SegmentDTO> segments;

    TranslationEntry(String blockId, List<SegmentDTO> segments) {
        this.blockId = blockId;
        this.segments = segments;
    }

    String blockId() {
        return blockId;
    }

    List<SegmentDTO> segments() {
        return segments;
    }
}
