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
     * The version of the Tia plugin, which the plugin also adds Tia's test-framework module at.
     *
     * @return the plugin version
     * @throws IllegalStateException if the generated resource is missing or has no version
     */
    public static String get() {
        return read("version");
    }

    /**
     * The SLF4J API version Tia builds against, which the plugin adds to the test runtime classpath
     * as a preferred (soft) version.
     *
     * @return the SLF4J API version
     * @throws IllegalStateException if the generated resource is missing or has no SLF4J version
     */
    public static String slf4j() {
        return read("slf4jVersion");
    }

    /**
     * Read one entry of the generated version resource.
     *
     * @param key the entry to read
     * @return its trimmed value
     * @throws IllegalStateException if the resource is missing or has no value for the key
     */
    private static String read(final String key) {
        try (InputStream in = TiaVersion.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Tia version resource " + RESOURCE + " is missing.");
            }
            Properties properties = new Properties();
            properties.load(in);
            String value = properties.getProperty(key);
            if (value == null || value.trim().isEmpty()) {
                throw new IllegalStateException("Tia version resource " + RESOURCE + " has no " + key + ".");
            }
            return value.trim();
        } catch (IOException e) {
            throw new UncheckedIOException("Tia could not read " + RESOURCE, e);
        }
    }
}
