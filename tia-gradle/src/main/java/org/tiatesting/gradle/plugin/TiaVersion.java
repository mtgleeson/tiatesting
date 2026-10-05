package org.tiatesting.gradle.plugin;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Properties;

/**
 * The version of this plugin, read from a resource the build generates. Used to add the matching
 * version of a Tia framework module to a project's test runtime classpath.
 */
public final class TiaVersion {

    /** Classpath resource holding {@code version=<plugin version>}. */
    static final String RESOURCE = "/org/tiatesting/gradle/plugin/tia-version.properties";

    /**
     * Static utility; not instantiable.
     */
    private TiaVersion() {
    }

    /**
     * @return the plugin version
     * @throws IllegalStateException if the generated resource is missing or has no version
     */
    public static String get() {
        try (InputStream in = TiaVersion.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Tia version resource " + RESOURCE + " is missing.");
            }
            Properties properties = new Properties();
            properties.load(in);
            String version = properties.getProperty("version");
            if (version == null || version.trim().isEmpty()) {
                throw new IllegalStateException("Tia version resource " + RESOURCE + " has no version.");
            }
            return version.trim();
        } catch (IOException e) {
            throw new UncheckedIOException("Tia could not read " + RESOURCE, e);
        }
    }
}
