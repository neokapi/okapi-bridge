package com.gokapi.bridge.util;

import com.gokapi.bridge.model.AnnotationEntryDTO;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import net.sf.okapi.common.LocaleId;
import net.sf.okapi.common.annotation.*;
import net.sf.okapi.common.resource.ITextUnit;
import net.sf.okapi.common.resource.Segment;
import net.sf.okapi.common.resource.TextContainer;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Extracts Okapi annotations from ITextUnit and converts them to
 * AnnotationEntryDTOs with JSON-encoded payloads.
 *
 * Supported annotation types:
 * - NoteAnnotation → "note" entries (one per note)
 * - AltTranslationsAnnotation → "alt-translation" entries (one per alt-trans)
 * - GenericAnnotations → entries keyed by GenericAnnotationType constant
 */
public class AnnotationExtractor {

    private static final Gson GSON = new GsonBuilder().create();

    /**
     * Extract all annotations from a TextUnit and return them as a map
     * of annotation key → AnnotationEntryDTO.
     *
     * Returns null if no annotations are present.
     */
    public static Map<String, AnnotationEntryDTO> extractAnnotations(ITextUnit tu) {
        Map<String, AnnotationEntryDTO> result = new LinkedHashMap<>();

        extractNotes(tu, result);
        extractAltTranslations(tu, result);
        extractGenericAnnotations(tu, result);

        return result.isEmpty() ? null : result;
    }

    private static void extractNotes(ITextUnit tu, Map<String, AnnotationEntryDTO> result) {
        // Okapi stores NoteAnnotation at different levels depending on the annotates attribute:
        // - annotates="general" or no attribute → TextUnit level
        // - annotates="source" → source TextContainer level
        // - annotates="target" → target TextContainer level
        // We collect from all levels.
        List<Note> collected = new ArrayList<>();

        NoteAnnotation noteAnnotation = tu.getAnnotation(NoteAnnotation.class);
        if (noteAnnotation != null) {
            for (Note n : noteAnnotation) {
                collected.add(n);
            }
        }

        TextContainer source = tu.getSource();
        if (source != null) {
            noteAnnotation = source.getAnnotation(NoteAnnotation.class);
            if (noteAnnotation != null) {
                for (Note n : noteAnnotation) {
                    collected.add(n);
                }
            }
        }

        for (LocaleId locale : tu.getTargetLocales()) {
            TextContainer target = tu.getTarget(locale);
            if (target == null) continue;
            noteAnnotation = target.getAnnotation(NoteAnnotation.class);
            if (noteAnnotation != null) {
                for (Note n : noteAnnotation) {
                    collected.add(n);
                }
            }
        }

        if (collected.isEmpty()) {
            return;
        }

        int idx = 0;
        for (Note note : collected) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("text", note.getNoteText());
            if (note.getFrom() != null && !note.getFrom().isEmpty()) {
                payload.put("from", note.getFrom());
            }
            if (note.getPriority() != null) {
                payload.put("priority", note.getPriority().value());
            }
            if (note.getAnnotates() != null) {
                payload.put("annotates", note.getAnnotates().value());
            }

            String key = idx == 0 ? "note" : "note-" + idx;
            result.put(key, new AnnotationEntryDTO("note",
                    GSON.toJson(payload).getBytes(StandardCharsets.UTF_8)));
            idx++;
        }
    }

    private static void extractAltTranslations(ITextUnit tu, Map<String, AnnotationEntryDTO> result) {
        // Okapi stores AltTranslationsAnnotation in multiple locations depending on the filter:
        // 1. On the TextUnit itself
        // 2. On the source TextContainer
        // 3. On the target TextContainer (XLIFF 1.2 uses this)
        // 4. On individual Segments within TextContainers
        // We collect from all locations.
        List<AltTranslation> collected = new ArrayList<>();

        // Check TextUnit level.
        AltTranslationsAnnotation ata = tu.getAnnotation(AltTranslationsAnnotation.class);
        if (ata != null) {
            for (AltTranslation at : ata) {
                collected.add(at);
            }
        }

        // Check source TextContainer and its segments.
        TextContainer source = tu.getSource();
        if (source != null) {
            ata = source.getAnnotation(AltTranslationsAnnotation.class);
            if (ata != null) {
                for (AltTranslation at : ata) {
                    collected.add(at);
                }
            }
            for (Segment seg : source.getSegments()) {
                ata = seg.getAnnotation(AltTranslationsAnnotation.class);
                if (ata != null) {
                    for (AltTranslation at : ata) {
                        collected.add(at);
                    }
                }
            }
        }

        // Check all target TextContainers and their segments.
        for (LocaleId locale : tu.getTargetLocales()) {
            TextContainer target = tu.getTarget(locale);
            if (target == null) continue;
            ata = target.getAnnotation(AltTranslationsAnnotation.class);
            if (ata != null) {
                for (AltTranslation at : ata) {
                    collected.add(at);
                }
            }
            for (Segment seg : target.getSegments()) {
                ata = seg.getAnnotation(AltTranslationsAnnotation.class);
                if (ata != null) {
                    for (AltTranslation at : ata) {
                        collected.add(at);
                    }
                }
            }
        }

        if (collected.isEmpty()) {
            return;
        }

        int idx = 0;
        for (AltTranslation at : collected) {
            Map<String, Object> payload = new LinkedHashMap<>();

            if (at.getSource() != null) {
                payload.put("source", at.getSource().toString());
            }
            if (at.getTarget() != null) {
                payload.put("target", at.getTarget().toString());
            }
            if (at.getTargetLocale() != null) {
                payload.put("locale", at.getTargetLocale().toString());
            }
            if (at.getOrigin() != null) {
                payload.put("origin", at.getOrigin());
            }
            payload.put("combined_score", at.getCombinedScore());
            if (at.getFuzzyScore() > 0) {
                payload.put("fuzzy_score", at.getFuzzyScore());
            }
            if (at.getQualityScore() > 0) {
                payload.put("quality_score", at.getQualityScore());
            }
            if (at.getEngine() != null && !at.getEngine().isEmpty()) {
                payload.put("engine", at.getEngine());
            }
            if (at.getType() != null) {
                payload.put("match_type", at.getType().name());
            }
            if (at.getFromOriginal()) {
                payload.put("from_original", true);
            }
            if (at.getALttransType() != null && !at.getALttransType().isEmpty()) {
                payload.put("alt_trans_type", at.getALttransType());
            }
            XLIFFTool tool = at.getTool();
            if (tool != null) {
                payload.put("tool_id", tool.getId());
                if (tool.getName() != null) {
                    payload.put("tool_name", tool.getName());
                }
            }

            String key = idx == 0 ? "alt-translation" : "alt-translation-" + idx;
            result.put(key, new AnnotationEntryDTO("alt-translation",
                    GSON.toJson(payload).getBytes(StandardCharsets.UTF_8)));
            idx++;
        }
    }

    private static void extractGenericAnnotations(ITextUnit tu, Map<String, AnnotationEntryDTO> result) {
        GenericAnnotations ga = tu.getAnnotation(GenericAnnotations.class);
        if (ga == null || ga.isEmpty()) {
            return;
        }

        for (GenericAnnotation ann : ga) {
            String annType = ann.getType();
            if (annType == null || annType.isEmpty()) {
                continue;
            }

            Map<String, Object> payload = new LinkedHashMap<>();
            for (String fieldName : ann.getNames()) {
                Object value = ann.getValue(fieldName);
                if (value != null) {
                    payload.put(fieldName, value);
                }
            }

            if (payload.isEmpty()) {
                continue;
            }

            // Use the annotation type as the key; append index for duplicates.
            String key = annType;
            if (result.containsKey(key)) {
                int idx = 1;
                while (result.containsKey(key + "-" + idx)) {
                    idx++;
                }
                key = key + "-" + idx;
            }

            result.put(key, new AnnotationEntryDTO(annType,
                    GSON.toJson(payload).getBytes(StandardCharsets.UTF_8)));
        }
    }
}
