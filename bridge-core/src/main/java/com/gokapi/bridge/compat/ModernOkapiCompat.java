package com.gokapi.bridge.compat;

/**
 * Adapter for Okapi 1.42.0+ where Note/NoteAnnotation classes
 * replaced the older XLIFFNote/XLIFFNoteAnnotation names.
 */
class ModernOkapiCompat extends AbstractOkapiCompat {

    @Override
    protected String noteAnnotationClassName() {
        return "net.sf.okapi.common.annotation.NoteAnnotation";
    }

    @Override
    protected String noteClassName() {
        return "net.sf.okapi.common.annotation.Note";
    }
}
