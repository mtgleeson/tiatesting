package org.tiatesting.core.agent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

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
}
