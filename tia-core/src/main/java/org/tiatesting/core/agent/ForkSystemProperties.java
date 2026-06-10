package org.tiatesting.core.agent;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.Properties;

/**
 * Shared read/write of the Tia "fork properties" file - the set of system properties the build
 * plugin needs the forked test JVM to see (database connection, project dirs, update flags, ...).
 *
 * <p>These values are written to a small file rather than passed inline on the command line or in
 * the agent option string. Two of them - {@code tiaClassFilesDirs} (a comma-separated list) and
 * {@code testClassesDir} (a long absolute path) - are variable-length and would risk the OS
 * command-line limit (notably Windows {@code CreateProcess}, ~32 KB) and, for the CSV, collide with
 * the comma-delimited agent option parser in {@link AgentOptions}. A file sidesteps both: the
 * command line carries only the short path, and {@link Properties} handles commas natively. This
 * mirrors how the agent already passes the ignore/selected-test lists and library jars by file.
 *
 * <p>The build plugin calls {@link #write(Map, File)}; the agent's {@code premain} calls
 * {@link #applyToSystemProperties(String)} so the values are live before any test listener runs.
 */
public final class ForkSystemProperties {

    private ForkSystemProperties() {
    }

    /**
     * Write the given properties to {@code file} in {@link Properties} format, creating parent
     * directories as needed. Entries with a {@code null} value are skipped (an unset optional value
     * such as {@code tiaDBUrl} simply does not appear, so the fork falls back to its default).
     *
     * @param properties the property name to value pairs to persist
     * @param file       the destination file
     * @throws IOException if the file cannot be written
     */
    public static void write(final Map<String, String> properties, final File file) throws IOException {
        Properties props = new Properties();
        for (Map.Entry<String, String> entry : properties.entrySet()) {
            if (entry.getValue() != null) {
                props.setProperty(entry.getKey(), entry.getValue());
            }
        }
        File parent = file.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        try (Writer writer = Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8)) {
            props.store(writer, "Tia forked-JVM system properties");
        }
    }

    /**
     * Load the fork properties file and publish each entry as a system property, but only when that
     * property is not already set. The "set only if absent" rule means an explicit {@code -D} on
     * the command line, or a value Surefire applies from {@code systemPropertyVariables}, still
     * wins - so the auto-forwarded file is a default, not an override.
     *
     * @param filePath path to the fork properties file, or {@code null}/empty to do nothing
     * @return the properties read from the file (empty when {@code filePath} is blank)
     * @throws IOException if the file exists but cannot be read
     */
    public static Properties applyToSystemProperties(final String filePath) throws IOException {
        Properties props = new Properties();
        if (filePath == null || filePath.isEmpty()) {
            return props;
        }
        try (Reader reader = Files.newBufferedReader(new File(filePath).toPath(), StandardCharsets.UTF_8)) {
            props.load(reader);
        }
        for (String name : props.stringPropertyNames()) {
            if (System.getProperty(name) == null) {
                System.setProperty(name, props.getProperty(name));
            }
        }
        return props;
    }
}
