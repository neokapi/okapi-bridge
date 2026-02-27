package com.gokapi.bridge.compat;

import net.sf.okapi.common.LocaleId;
import net.sf.okapi.common.annotation.GenericAnnotation;
import net.sf.okapi.common.annotation.GenericAnnotations;
import net.sf.okapi.common.annotation.IAnnotation;
import net.sf.okapi.common.resource.ITextUnit;
import net.sf.okapi.common.resource.TextContainer;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Base adapter that handles Okapi API differences via reflection.
 *
 * Subclasses provide the qualified class names for the Note annotation
 * classes, which differ between Okapi versions (Note vs XLIFFNote).
 * The isEmpty() and getValue() methods use universal backward-compatible
 * implementations that work across all supported versions.
 */
abstract class AbstractOkapiCompat implements OkapiCompat {

    private volatile Class<? extends IAnnotation> noteAnnotationClass;
    private volatile Method getNoteText;
    private volatile Method getFrom;
    private volatile Method getPriority;
    private volatile Method getAnnotates;
    private volatile Method priorityValue;
    private volatile Method annotatesValue;

    protected abstract String noteAnnotationClassName();
    protected abstract String noteClassName();

    @Override
    public List<NoteData> extractNotes(ITextUnit tu) {
        Class<? extends IAnnotation> annClass = resolveNoteAnnotationClass();
        if (annClass == null) {
            return Collections.emptyList();
        }

        List<NoteData> collected = new ArrayList<>();

        // Unit-level notes
        collectNotes(tu.getAnnotation(annClass), collected);

        // Source-level notes
        TextContainer source = tu.getSource();
        if (source != null) {
            collectNotes(source.getAnnotation(annClass), collected);
        }

        // Target-level notes (all locales)
        for (LocaleId locale : tu.getTargetLocales()) {
            TextContainer target = tu.getTarget(locale);
            if (target != null) {
                collectNotes(target.getAnnotation(annClass), collected);
            }
        }

        return collected;
    }

    @Override
    public boolean isEmpty(GenericAnnotations ga) {
        return ga.size() == 0;
    }

    @Override
    public Object getValue(GenericAnnotation ann, String fieldName) {
        // Try each type-specific getter in order. These exist in all Okapi versions.
        String s = ann.getString(fieldName);
        if (s != null) return s;

        Double d = ann.getDouble(fieldName);
        if (d != null) return d;

        Integer i = ann.getInteger(fieldName);
        if (i != null) return i;

        Boolean b = ann.getBoolean(fieldName);
        if (b != null) return b;

        return null;
    }

    @SuppressWarnings("unchecked")
    private Class<? extends IAnnotation> resolveNoteAnnotationClass() {
        if (noteAnnotationClass == null) {
            synchronized (this) {
                if (noteAnnotationClass == null) {
                    try {
                        noteAnnotationClass = (Class<? extends IAnnotation>)
                                Class.forName(noteAnnotationClassName());
                    } catch (ClassNotFoundException e) {
                        return null;
                    }
                }
            }
        }
        return noteAnnotationClass;
    }

    private void collectNotes(IAnnotation annotation, List<NoteData> dest) {
        if (annotation == null) return;

        // Both NoteAnnotation and XLIFFNoteAnnotation implement Iterable
        for (Object note : (Iterable<?>) annotation) {
            dest.add(toNoteData(note));
        }
    }

    private NoteData toNoteData(Object note) {
        try {
            resolveMethods(note.getClass());

            String text = (String) getNoteText.invoke(note);
            String from = (String) getFrom.invoke(note);

            Integer priorityVal = null;
            Object priority = getPriority.invoke(note);
            if (priority != null) {
                if (priorityValue == null) {
                    priorityValue = priority.getClass().getMethod("value");
                }
                priorityVal = (Integer) priorityValue.invoke(priority);
            }

            String annotatesVal = null;
            Object annotatesObj = getAnnotates.invoke(note);
            if (annotatesObj != null) {
                if (annotatesValue == null) {
                    annotatesValue = annotatesObj.getClass().getMethod("value");
                }
                annotatesVal = (String) annotatesValue.invoke(annotatesObj);
            }

            return new NoteData(text, from, priorityVal, annotatesVal);
        } catch (Exception e) {
            throw new RuntimeException("Failed to extract note data via reflection", e);
        }
    }

    private void resolveMethods(Class<?> noteClass) throws NoSuchMethodException {
        if (getNoteText == null) {
            synchronized (this) {
                if (getNoteText == null) {
                    getNoteText = noteClass.getMethod("getNoteText");
                    getFrom = noteClass.getMethod("getFrom");
                    getPriority = noteClass.getMethod("getPriority");
                    getAnnotates = noteClass.getMethod("getAnnotates");
                }
            }
        }
    }
}
