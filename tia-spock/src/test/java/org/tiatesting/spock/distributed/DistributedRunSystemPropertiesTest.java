package org.tiatesting.spock.distributed;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tiatesting.core.distributed.DistributedRunConfig;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cover {@link DistributedRunSystemProperties}: the two ends of the Gradle plugin's handoff of a
 * distributed run to the forked test JVM. The plugin runs in the Gradle daemon where the {@code
 * tia { ... }} extension lives; the test JVM sees only system properties, so the same class both
 * emits the property set on the daemon side and reads it back on the test-JVM side, and these
 * tests hold the two halves to the same property names.
 *
 * <p>The read half is also where a Gradle distributed runner's preconditions are enforced, which
 * is why several of these tests are about failing rather than parsing: a runner pointed at an
 * embedded database sees no other runner's claims at all, and would claim group 0 alongside every
 * other runner in the pipeline.
 */
class DistributedRunSystemPropertiesTest {

    private static final String[] MANAGED_PROPERTIES = {
            "tiaDistributed", "tiaRunId", "tiaDistributedRunnerKey", "tiaEnabled", "tiaDBUrl",
            "tiaDBDialect", "tiaCheckLocalChanges"
    };

    private Map<String, String> savedProperties;

    /**
     * Save and clear every system property these tests set, so a test starts from a known state
     * regardless of what the surrounding JVM (or an earlier test) left behind.
     */
    @BeforeEach
    void setUp() {
        savedProperties = new LinkedHashMap<>();
        for (String key : MANAGED_PROPERTIES) {
            savedProperties.put(key, System.getProperty(key));
            System.clearProperty(key);
        }
    }

    /**
     * Restore the system properties saved in {@link #setUp()}, so these tests leave the JVM as
     * they found it for whatever runs next in the same fork.
     */
    @AfterEach
    void tearDown() {
        for (Map.Entry<String, String> entry : savedProperties.entrySet()) {
            if (entry.getValue() == null) {
                System.clearProperty(entry.getKey());
            } else {
                System.setProperty(entry.getKey(), entry.getValue());
            }
        }
    }

    /**
     * Set the system properties a Gradle distributed runner's test JVM would be started with: Tia
     * enabled, a shared database, and the run to claim from.
     *
     * @param runId the distributed run id to claim from
     * @param runnerKey the runner identity to claim under, or null to let the claim derive one
     */
    private static void givenDistributedRunnerProperties(final String runId, final String runnerKey) {
        System.setProperty("tiaEnabled", "true");
        System.setProperty("tiaDBUrl", "jdbc:h2:tcp://localhost:9092/tiadb");
        System.setProperty("tiaDistributed", "true");
        if (runId != null) {
            System.setProperty("tiaRunId", runId);
        }
        if (runnerKey != null) {
            System.setProperty("tiaDistributedRunnerKey", runnerKey);
        }
    }

    /**
     * Verify an ordinary Gradle build resolves no distributed configuration at all. This is the
     * guarantee that every non-distributed Spock run is untouched by this stage: the property is
     * absent, so the reader returns before it checks a single precondition and the extension takes
     * the selection branch it always took.
     */
    @Test
    void shouldResolveNoConfigWhenTheBuildIsNotDistributed() {
        // given
        System.setProperty("tiaEnabled", "true");

        // when
        DistributedRunConfig config = DistributedRunSystemProperties.runnerConfigFromSystemProperties();

        // then
        assertNull(config);
    }

    /**
     * Verify an explicitly disabled distributed run resolves no configuration either, so a
     * pipeline that turns distributed mode off by setting the flag to false rather than removing
     * it gets an ordinary build rather than a claim attempt.
     */
    @Test
    void shouldResolveNoConfigWhenDistributedIsExplicitlyFalse() {
        // given
        givenDistributedRunnerProperties("run-1", "runner-a");
        System.setProperty("tiaDistributed", "false");

        // when
        DistributedRunConfig config = DistributedRunSystemProperties.runnerConfigFromSystemProperties();

        // then
        assertNull(config);
    }

    /**
     * Verify a distributed runner's properties resolve to a runner-shaped configuration: the run
     * id and the runner identity, and nothing about how the run was split. The split is the
     * planner's decision and is already recorded in the plan being claimed from, so a runner that
     * carried a group count could only ever disagree with it.
     */
    @Test
    void shouldResolveTheRunIdAndRunnerKeyForADistributedRunner() {
        // given
        givenDistributedRunnerProperties("run-1", "runner-a");

        // when
        DistributedRunConfig config = DistributedRunSystemProperties.runnerConfigFromSystemProperties();

        // then
        assertNotNull(config);
        assertEquals("run-1", config.getRunId());
        assertEquals("runner-a", config.getRunnerKey());
        assertNull(config.getGroupCount());
        assertNull(config.getTargetRunTimeMs());
    }

    /**
     * Verify a runner with no configured identity still resolves a configuration, leaving the key
     * null so the claim protocol derives one. A Gradle runner claims inside the same JVM that runs
     * the tests, so a derived key is stable for as long as it is needed.
     */
    @Test
    void shouldResolveAConfigWithNoRunnerKeyWhenNoneIsConfigured() {
        // given
        givenDistributedRunnerProperties("run-1", null);

        // when
        DistributedRunConfig config = DistributedRunSystemProperties.runnerConfigFromSystemProperties();

        // then
        assertNotNull(config);
        assertNull(config.getRunnerKey());
    }

    /**
     * Verify a distributed runner pointed at an embedded database fails rather than claiming. Each
     * runner would get its own private copy of the database, so every runner would claim group 0
     * and run the same fraction of the suite while the build reported success.
     */
    @Test
    void shouldFailWhenTheDatabaseIsNotShared() {
        // given
        givenDistributedRunnerProperties("run-1", "runner-a");
        System.clearProperty("tiaDBUrl");

        // when
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                DistributedRunSystemProperties::runnerConfigFromSystemProperties);

        // then
        assertTrue(thrown.getMessage().contains("tiaDBUrl"), thrown.getMessage());
    }

    /**
     * Verify a distributed runner that checks local changes fails rather than claiming. Every
     * runner must diff the same committed baseline; uncommitted edits would have two runners
     * compute different line numbers for the same commit.
     */
    @Test
    void shouldFailWhenLocalChangesCheckingIsEnabled() {
        // given
        givenDistributedRunnerProperties("run-1", "runner-a");
        System.setProperty("tiaCheckLocalChanges", "true");

        // when
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                DistributedRunSystemProperties::runnerConfigFromSystemProperties);

        // then
        assertTrue(thrown.getMessage().contains("tiaCheckLocalChanges"), thrown.getMessage());
    }

    /**
     * Verify a distributed runner with no run id fails naming the property to set. Without it
     * there is no plan to claim from, and a runner that carried on would run the whole suite on
     * every host in the pipeline.
     */
    @Test
    void shouldFailWhenTheRunIdIsMissing() {
        // given
        givenDistributedRunnerProperties(null, "runner-a");

        // when
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                DistributedRunSystemProperties::runnerConfigFromSystemProperties);

        // then
        assertTrue(thrown.getMessage().contains("tiaRunId"), thrown.getMessage());
    }

    /**
     * Verify the daemon-side half emits exactly the properties the test-JVM half reads, so the
     * Gradle plugin and the Spock extension cannot drift on a property name: what is formatted
     * here must resolve to the same run id and runner key when read back.
     */
    @Test
    void shouldForwardThePropertiesItsOwnReaderResolves() {
        // given
        Map<String, String> forwarded = DistributedRunSystemProperties.forwardedProperties(
                Boolean.TRUE, "run-1", "runner-a");

        // when
        System.setProperty("tiaEnabled", "true");
        System.setProperty("tiaDBUrl", "jdbc:h2:tcp://localhost:9092/tiadb");
        for (Map.Entry<String, String> entry : forwarded.entrySet()) {
            System.setProperty(entry.getKey(), entry.getValue());
        }
        DistributedRunConfig config = DistributedRunSystemProperties.runnerConfigFromSystemProperties();

        // then
        assertNotNull(config);
        assertEquals("run-1", config.getRunId());
        assertEquals("runner-a", config.getRunnerKey());
    }

    /**
     * Verify a non-distributed build forwards nothing at all, so an ordinary Gradle build's test
     * JVM is started with exactly the system properties it was started with before distributed
     * runs existed.
     */
    @Test
    void shouldForwardNothingForANonDistributedBuild() {
        // given
        Boolean distributed = Boolean.FALSE;

        // when
        Map<String, String> forwarded = DistributedRunSystemProperties.forwardedProperties(
                distributed, "run-1", "runner-a");

        // then
        assertTrue(forwarded.isEmpty(), forwarded.toString());
        assertTrue(DistributedRunSystemProperties.forwardedProperties(null, "run-1", "runner-a").isEmpty());
    }

    /**
     * Verify an unset runner key is left off the forwarded set rather than forwarded as the string
     * "null". A test JVM given the literal text would claim under it and every runner in the
     * pipeline would share one identity, so the second claim would look like the first runner's
     * job retrying.
     */
    @Test
    void shouldOmitAnUnsetRunnerKeyFromTheForwardedProperties() {
        // given
        Boolean distributed = Boolean.TRUE;

        // when
        Map<String, String> forwarded = DistributedRunSystemProperties.forwardedProperties(
                distributed, "run-1", null);

        // then
        assertEquals("true", forwarded.get("tiaDistributed"));
        assertEquals("run-1", forwarded.get("tiaRunId"));
        assertFalse(forwarded.containsKey("tiaDistributedRunnerKey"), forwarded.toString());
    }
}
