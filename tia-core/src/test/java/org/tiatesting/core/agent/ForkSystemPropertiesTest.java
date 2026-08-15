package org.tiatesting.core.agent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.tiatesting.core.persistence.DataStoreFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Round-trip and precedence tests for {@link ForkSystemProperties} - the fork properties file the
 * build plugin writes and the agent replays into the forked JVM's system properties.
 */
class ForkSystemPropertiesTest {

    // unique keys so the tests never collide with real Tia properties
    private static final String KEY_PLAIN = "tiaTestForkProp.plain";
    private static final String KEY_CSV = "tiaTestForkProp.csv";
    private static final String KEY_PRESET = "tiaTestForkProp.preset";

    @AfterEach
    void clearProps() {
        System.clearProperty(KEY_PLAIN);
        System.clearProperty(KEY_CSV);
        System.clearProperty(KEY_PRESET);
        System.clearProperty(DataStoreFactory.PROP_DB_DIALECT);
    }

    private File newTempFile() throws Exception {
        return Files.createTempFile("tia-fork-", ".properties").toFile();
    }

    @Test
    void writeThenApplyRoundTripsValuesIncludingCommas() throws Exception {
        // given
        Map<String, String> props = new LinkedHashMap<>();
        props.put(KEY_PLAIN, "main");
        // a CSV value would collide with the comma-delimited agent option parser if passed inline;
        // via the properties file it must survive intact
        props.put(KEY_CSV, "/a/target/classes,/b/target/classes");
        File file = newTempFile();

        // when
        ForkSystemProperties.write(props, file);
        ForkSystemProperties.applyToSystemProperties(file.getAbsolutePath());

        // then
        assertEquals("main", System.getProperty(KEY_PLAIN));
        assertEquals("/a/target/classes,/b/target/classes", System.getProperty(KEY_CSV));
    }

    @Test
    void applyDoesNotOverrideAnAlreadySetProperty() throws Exception {
        // given
        System.setProperty(KEY_PRESET, "from-command-line");
        Map<String, String> props = new LinkedHashMap<>();
        props.put(KEY_PRESET, "from-file");
        File file = newTempFile();
        ForkSystemProperties.write(props, file);

        // when
        ForkSystemProperties.applyToSystemProperties(file.getAbsolutePath());

        // then
        // an explicit -D / Surefire systemPropertyVariables value wins; the file is only a default
        assertEquals("from-command-line", System.getProperty(KEY_PRESET));
    }

    @Test
    void writeSkipsNullValuedEntries() throws Exception {
        // given
        Map<String, String> props = new LinkedHashMap<>();
        props.put(KEY_PLAIN, "set");
        props.put(KEY_CSV, null); // e.g. an unconfigured tiaDBUrl
        File file = newTempFile();

        // when
        ForkSystemProperties.write(props, file);
        Properties loaded = ForkSystemProperties.applyToSystemProperties(file.getAbsolutePath());

        // then
        assertFalse(loaded.containsKey(KEY_CSV), "null-valued entry should not be written");
        assertNull(System.getProperty(KEY_CSV));
        assertEquals("set", System.getProperty(KEY_PLAIN));
    }

    @Test
    void applyWithBlankPathIsANoOp() throws Exception {
        // given / when
        Properties result = ForkSystemProperties.applyToSystemProperties(null);

        // then
        assertEquals(0, result.size());
    }

    @Test
    void tiaDBDialectIsForwardedWhenSetAndOmittedWhenNull() throws Exception {
        // given - mirrors how the Maven/Gradle plugins bundle tiaDBUrl alongside tiaDBDialect
        // into the fork properties map: forwarded when configured, absent (falls back to
        // dialect inference) when not.
        Map<String, String> props = new LinkedHashMap<>();
        props.put(DataStoreFactory.PROP_DB_DIALECT, "postgres");
        File file = newTempFile();

        // when
        ForkSystemProperties.write(props, file);
        ForkSystemProperties.applyToSystemProperties(file.getAbsolutePath());

        // then
        assertEquals("postgres", System.getProperty(DataStoreFactory.PROP_DB_DIALECT));
    }

    @Test
    void tiaDBDialectIsOmittedWhenUnset() throws Exception {
        // given - an unconfigured tiaDBDialect must not appear in the written file, so the
        // fork falls back to dialect inference from tiaDBUrl rather than seeing a literal "null".
        Map<String, String> props = new LinkedHashMap<>();
        props.put(DataStoreFactory.PROP_DB_DIALECT, null);
        File file = newTempFile();

        // when
        ForkSystemProperties.write(props, file);
        Properties loaded = ForkSystemProperties.applyToSystemProperties(file.getAbsolutePath());

        // then
        assertFalse(loaded.containsKey(DataStoreFactory.PROP_DB_DIALECT));
        assertNull(System.getProperty(DataStoreFactory.PROP_DB_DIALECT));
    }

    /**
     * {@code read} must hand back the file's contents so a caller that only wants the values - such
     * as a build-JVM step that is not a fork - can use them without ever calling {@code
     * System.setProperty}.
     *
     * @throws Exception if the temp file cannot be created or written
     */
    @Test
    void readReturnsThePropertiesWrittenToTheFileWithoutTouchingSystemProperties() throws Exception {
        // given
        Map<String, String> props = new LinkedHashMap<>();
        props.put(KEY_PLAIN, "main");
        props.put(KEY_CSV, "/a/target/classes,/b/target/classes");
        File file = newTempFile();
        ForkSystemProperties.write(props, file);

        // when
        Properties read = ForkSystemProperties.read(file);

        // then
        assertEquals("main", read.getProperty(KEY_PLAIN));
        assertEquals("/a/target/classes,/b/target/classes", read.getProperty(KEY_CSV));
        assertNull(System.getProperty(KEY_PLAIN),
                "read must not publish anything as a system property - that is applyToSystemProperties's job");
        assertNull(System.getProperty(KEY_CSV));
    }

    /**
     * A {@code null} file is the caller-facing way to say "there is no fork properties file",
     * mirroring the {@code null}/blank path {@link ForkSystemProperties#applyToSystemProperties}
     * already treats as a no-op, so {@code read} must return an empty result rather than throwing.
     *
     * @throws Exception never; declared only because {@code read} is checked
     */
    @Test
    void readOfANullFileReturnsAnEmptyResult() throws Exception {
        // given / when
        Properties read = ForkSystemProperties.read(null);

        // then
        assertTrue(read.isEmpty(), "a null file must read as no properties at all, not an error");
    }

    /**
     * The two ways of reading the file must fail identically on a file that is not there: {@code
     * applyToSystemProperties} has always thrown from this case, and since {@code read} is now the
     * code that path delegates to, both must throw the same {@link IOException} rather than {@code
     * read} silently returning empty and hiding a misconfigured path from the new Maven-goal caller.
     *
     * @throws Exception never; both branches under test throw and are caught by {@code assertThrows}
     */
    @Test
    void readAndApplyBothThrowOnAMissingFile() throws Exception {
        // given - a path naming a file that was never written
        File missing = new File(newTempFile().getAbsolutePath() + "-does-not-exist");

        // when / then
        assertThrows(IOException.class, () -> ForkSystemProperties.read(missing),
                "read must throw on a missing file");
        assertThrows(IOException.class,
                () -> ForkSystemProperties.applyToSystemProperties(missing.getAbsolutePath()),
                "applyToSystemProperties must throw on a missing file exactly as read does, since it "
                        + "now delegates to read for the load");
    }

    /**
     * {@code applyToSystemProperties} must still round-trip through {@code read} rather than loading
     * the file its own way, so a value the file carries reaches the system properties exactly as it
     * did before the delegation - the regression this test exists to catch is a duplicated, silently
     * diverging load path.
     *
     * @throws Exception if the temp file cannot be created or written
     */
    @Test
    void applyToSystemPropertiesStillPublishesWhatReadWouldReturn() throws Exception {
        // given
        Map<String, String> props = new LinkedHashMap<>();
        props.put(KEY_PLAIN, "from-file");
        File file = newTempFile();
        ForkSystemProperties.write(props, file);

        // when
        Properties applied = ForkSystemProperties.applyToSystemProperties(file.getAbsolutePath());

        // then
        assertEquals(ForkSystemProperties.read(file), applied,
                "applyToSystemProperties must return exactly what read returns for the same file");
        assertEquals("from-file", System.getProperty(KEY_PLAIN));
    }
}
