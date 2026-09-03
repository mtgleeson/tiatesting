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
 * A build-JVM step that needs the file's contents without becoming a fork - one that must not
 * pollute its own process's system properties with configuration meant for a test JVM - calls
 * {@link #read(File)} directly instead. See the "How Tia exchanges data with the test runner
 * (Gradle vs Maven)" chapter in {@code WIKI.md} for the fork-boundary handoff this file crosses.
 */
public final class ForkSystemProperties {

    /**
     * Property name, in the fork properties file, for whether this run updates the mapping DB.
     * The single source of truth for this name: {@code AbstractTiaAgentMojo} writes it and
     * {@code AbstractTiaDistCompleteMojo} reads it, and both must agree on the literal or the
     * read side silently sees {@code false} regardless of what the write side wrote.
     */
    public static final String PROP_UPDATE_DB_MAPPING = "tiaUpdateDBMapping";

    /**
     * Property name, in the fork properties file, for whether this run logs a history row. See
     * {@link #PROP_UPDATE_DB_MAPPING} for why this name is owned here rather than duplicated as a
     * literal on the write and read sides.
     */
    public static final String PROP_UPDATE_DB_TEST_RUN_HISTORY = "tiaUpdateDBTestRunHistory";

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
     * Read the fork properties file's contents without touching this JVM's system properties.
     *
     * <p>This is the one place either public entry point actually reads the file - {@link
     * #applyToSystemProperties(String)} delegates here rather than loading it a second way, so the
     * two callers can never disagree about what counts as a missing or unreadable file. A caller
     * that only wants the values (a build-JVM step that must not leak test-fork configuration into
     * its own process's system properties) calls this directly; a caller that wants them live as
     * system properties calls {@link #applyToSystemProperties(String)} instead.
     *
     * @param file the fork properties file to read; {@code null} returns an empty result rather
     *             than reading anything, mirroring {@link #applyToSystemProperties(String)}'s
     *             {@code null}-path no-op. This is not fully symmetric with that method's blank-path
     *             handling though: {@link #applyToSystemProperties(String)} treats an empty string
     *             as a no-op before it ever constructs a {@code File}, whereas {@code
     *             read(new File(""))} reaches the {@code try}-with-resources below and throws, since
     *             only a literal {@code null} short-circuits here. A caller that constructs its own
     *             {@code File} from a possibly-blank path must apply that same blank check itself.
     * @return the properties read from the file (empty when {@code file} is {@code null})
     * @throws IOException if {@code file} is non-null but does not exist or cannot be read
     */
    public static Properties read(final File file) throws IOException {
        Properties props = new Properties();
        if (file == null) {
            return props;
        }
        try (Reader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            props.load(reader);
        }
        return props;
    }

    /**
     * Load the fork properties file and publish each entry as a system property, but only when that
     * property is not already set. The "set only if absent" rule means an explicit {@code -D} on
     * the command line, or a value Surefire applies from {@code systemPropertyVariables}, still
     * wins - so the auto-forwarded file is a default, not an override.
     *
     * @param filePath path to the fork properties file, or {@code null}/empty to do nothing
     * @return the properties read from the file (empty when {@code filePath} is blank)
     * @throws IOException if {@code filePath} is non-blank but the file does not exist or cannot be
     *                      read
     */
    public static Properties applyToSystemProperties(final String filePath) throws IOException {
        if (filePath == null || filePath.isEmpty()) {
            return new Properties();
        }
        Properties props = read(new File(filePath));
        for (String name : props.stringPropertyNames()) {
            if (System.getProperty(name) == null) {
                System.setProperty(name, props.getProperty(name));
            }
        }
        return props;
    }
}
