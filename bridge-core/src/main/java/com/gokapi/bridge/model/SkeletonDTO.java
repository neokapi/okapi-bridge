package com.gokapi.bridge.model;

import java.util.List;

/**
 * Wire representation of a skeleton (gokapi model.Skeleton).
 * A skeleton preserves non-translatable document structure for reconstruction.
 */
public class SkeletonDTO {

    /** Strategy 0 = fragment-based (parts list is populated). */
    public static final int STRATEGY_FRAGMENT = 0;
    /** Strategy 1 = reparse-based (original content is re-parsed during write). */
    public static final int STRATEGY_REPARSE = 1;

    private int strategy;
    private List<SkeletonPartDTO> parts;
    private String sourceUri;

    public int getStrategy() { return strategy; }
    public void setStrategy(int strategy) { this.strategy = strategy; }

    public List<SkeletonPartDTO> getParts() { return parts; }
    public void setParts(List<SkeletonPartDTO> parts) { this.parts = parts; }

    public String getSourceUri() { return sourceUri; }
    public void setSourceUri(String sourceUri) { this.sourceUri = sourceUri; }
}
