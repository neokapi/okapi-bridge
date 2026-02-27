package com.gokapi.bridge.compat;

/**
 * Version-independent representation of an Okapi note annotation.
 * Holds the extracted values from either Note (1.42.0+) or XLIFFNote (pre-1.42.0).
 */
public class NoteData {

    private final String text;
    private final String from;
    private final Integer priority;
    private final String annotates;

    public NoteData(String text, String from, Integer priority, String annotates) {
        this.text = text;
        this.from = from;
        this.priority = priority;
        this.annotates = annotates;
    }

    public String getText() { return text; }
    public String getFrom() { return from; }
    public Integer getPriority() { return priority; }
    public String getAnnotates() { return annotates; }
}
