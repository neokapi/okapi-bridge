package com.gokapi.bridge.compat;

/**
 * Factory that detects the Okapi version at runtime and returns
 * the appropriate {@link OkapiCompat} implementation.
 *
 * Detection is done by probing for classes that were renamed
 * between Okapi versions. The result is cached as a singleton.
 */
public final class OkapiCompatFactory {

    private static volatile OkapiCompat instance;

    private OkapiCompatFactory() {}

    public static OkapiCompat get() {
        if (instance == null) {
            synchronized (OkapiCompatFactory.class) {
                if (instance == null) {
                    instance = detect();
                }
            }
        }
        return instance;
    }

    private static OkapiCompat detect() {
        // Note class was introduced in Okapi 1.42.0, replacing XLIFFNote.
        try {
            Class.forName("net.sf.okapi.common.annotation.Note");
            return new ModernOkapiCompat();
        } catch (ClassNotFoundException e) {
            return new LegacyOkapiCompat();
        }
    }
}
