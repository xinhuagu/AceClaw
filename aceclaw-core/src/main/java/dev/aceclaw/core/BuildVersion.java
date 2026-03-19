package dev.aceclaw.core;

import java.io.InputStream;
import java.util.Properties;

/**
 * Reads the build version from {@code version.properties} generated at build time.
 * Single source of truth: {@code gradle.properties} → build → classpath resource.
 */
public final class BuildVersion {

    private static final String FALLBACK = "dev";
    private static volatile String cachedVersion;

    private BuildVersion() {}

    /**
     * Returns the project version (e.g. "0.3.0-SNAPSHOT" or "0.3.0").
     * Falls back to "dev" if the resource is missing (IDE run without build).
     */
    public static String version() {
        String v = cachedVersion;
        if (v != null) return v;
        synchronized (BuildVersion.class) {
            if (cachedVersion != null) return cachedVersion;
            cachedVersion = loadVersion();
            return cachedVersion;
        }
    }

    /**
     * Returns a display string like "AceClaw v0.3.0-SNAPSHOT".
     */
    public static String displayVersion() {
        return "AceClaw v" + version();
    }

    private static String loadVersion() {
        try (InputStream is = BuildVersion.class.getClassLoader()
                .getResourceAsStream("version.properties")) {
            if (is == null) return FALLBACK;
            Properties props = new Properties();
            props.load(is);
            String v = props.getProperty("version", FALLBACK);
            return v.isBlank() ? FALLBACK : v;
        } catch (Exception e) {
            return FALLBACK;
        }
    }
}
