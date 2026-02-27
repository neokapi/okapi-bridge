package com.gokapi.bridge.compat;

/**
 * Adapter for Okapi pre-1.42.0 where notes used the
 * XLIFFNote/XLIFFNoteAnnotation class names.
 */
class LegacyOkapiCompat extends AbstractOkapiCompat {

    @Override
    protected String noteAnnotationClassName() {
        return "net.sf.okapi.common.annotation.XLIFFNoteAnnotation";
    }

    @Override
    protected String noteClassName() {
        return "net.sf.okapi.common.annotation.XLIFFNote";
    }
}
