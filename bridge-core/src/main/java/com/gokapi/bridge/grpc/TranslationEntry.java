package com.gokapi.bridge.grpc;

import com.gokapi.bridge.model.FragmentDTO;

import java.util.Collections;
import java.util.List;

/**
 * Carries a block's target fragments through the streaming write queue.
 * Used by {@link StreamingTranslationApplier} to apply translations on-demand
 * as the skeleton re-read encounters TEXT_UNIT events.
 */
final class TranslationEntry {

    /** Sentinel value signaling end-of-stream. */
    static final TranslationEntry END = new TranslationEntry("", Collections.emptyList());

    private final String blockId;
    private final List<FragmentDTO> fragments;

    TranslationEntry(String blockId, List<FragmentDTO> fragments) {
        this.blockId = blockId;
        this.fragments = fragments;
    }

    String blockId() {
        return blockId;
    }

    List<FragmentDTO> fragments() {
        return fragments;
    }
}
