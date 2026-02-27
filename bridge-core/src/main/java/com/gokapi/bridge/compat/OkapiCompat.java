package com.gokapi.bridge.compat;

import net.sf.okapi.common.annotation.GenericAnnotation;
import net.sf.okapi.common.annotation.GenericAnnotations;
import net.sf.okapi.common.resource.ITextUnit;

import java.util.List;

/**
 * Adapter interface for Okapi APIs that vary across versions.
 *
 * Implementations handle differences such as class renames
 * (Note vs XLIFFNote) and missing convenience methods
 * (GenericAnnotations.isEmpty, GenericAnnotation.getValue).
 */
public interface OkapiCompat {

    /**
     * Extract all notes from a TextUnit, checking unit-level,
     * source-level, and all target-level annotations.
     */
    List<NoteData> extractNotes(ITextUnit tu);

    /**
     * Check if a GenericAnnotations collection has no entries.
     * Provides backward-compatible isEmpty() for pre-1.47.0.
     */
    boolean isEmpty(GenericAnnotations ga);

    /**
     * Get a field value from a GenericAnnotation by name.
     * Provides backward-compatible getValue() for pre-1.46.0.
     */
    Object getValue(GenericAnnotation ann, String fieldName);
}
