package org.tiatesting.core.distributed;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cover {@link DistributedForkProperties}: the two ends of the handoff that carries a claimed
 * distributed run from the build JVM, which made the claim, into the forked test JVM, which
 * completes the group and stands for election to seal.
 *
 * <p>Both ends are held to the same property names here on purpose. If the writer and the reader
 * ever disagreed about one name the fork would resolve no context, silently take the single-host
 * path, and seal a build whose other runners were still running - which is exactly the
 * under-selection the whole distributed persist exists to avoid, and nothing else in the build
 * would fail to say so.
 */
class DistributedForkPropertiesTest {

    private static final String[] MANAGED_PROPERTIES = {
            DistributedForkProperties.PROP_DISTRIBUTED, DistributedForkProperties.PROP_RUN_ID,
            DistributedForkProperties.PROP_RUNNER_KEY, DistributedForkProperties.PROP_GROUP_NUMBER
    };

    private Map<String, String> savedProperties;

    /**
     * Save and clear every system property these tests read or write, so each test starts from a
     * known state whatever the surrounding JVM or an earlier test left behind.
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
     * Publish a map of properties as system properties, standing in for what the Tia agent's
     * {@code premain} does with the fork properties file in the real forked test JVM.
     *
     * @param properties the property name to value pairs to publish
     */
    private static void publish(final Map<String, String> properties) {
        for (Map.Entry<String, String> entry : properties.entrySet()) {
            System.setProperty(entry.getKey(), entry.getValue());
        }
    }

    /**
     * Verify a fork of an ordinary build resolves no context at all. That null is what keeps a
     * non-distributed run on the single-host persist it has always taken, so it is the guarantee
     * that wiring the distributed path in changes nothing for everyone else.
     */
    @Test
    void shouldResolveNoContextWhenTheForkIsNotDistributed() {
        // given - the property an ordinary build's fork never sees

        // when
        DistributedRunnerContext context = DistributedForkProperties.contextFromSystemProperties();

        // then
        assertNull(context, "an ordinary build's fork must resolve no distributed context");
    }

    /**
     * Verify a distributed fork resolves a context carrying the three values the build JVM
     * forwarded. The fork can reconstruct none of them: the runner key may have been derived by
     * the claim, and the group number is only known to whoever won it.
     */
    @Test
    void shouldResolveTheForwardedRunIdRunnerKeyAndGroupNumber() {
        // given
        System.setProperty(DistributedForkProperties.PROP_DISTRIBUTED, "true");
        System.setProperty(DistributedForkProperties.PROP_RUN_ID, "run-1");
        System.setProperty(DistributedForkProperties.PROP_RUNNER_KEY, "runner-a");
        System.setProperty(DistributedForkProperties.PROP_GROUP_NUMBER, "2");

        // when
        DistributedRunnerContext context = DistributedForkProperties.contextFromSystemProperties();

        // then
        assertNotNull(context, "a distributed fork must resolve a context");
        assertTrue(context.isClaimed(), "a fork given a group number holds that group");
        assertEquals("run-1", context.getRunId());
        assertEquals("runner-a", context.getRunnerKey());
        assertEquals(Integer.valueOf(2), context.getGroupNumber());
    }

    /**
     * Verify a surplus runner's fork - one whose build JVM claimed no group because every group
     * was taken - resolves a context holding no group rather than no context at all. A null here
     * would put it on the single-host path, where it would seal a build whose other runners are
     * still running.
     */
    @Test
    void shouldResolveAGrouplessContextForASurplusRunnerRatherThanNone() {
        // given - the group number property is absent, exactly as the build JVM leaves it
        System.setProperty(DistributedForkProperties.PROP_DISTRIBUTED, "true");
        System.setProperty(DistributedForkProperties.PROP_RUN_ID, "run-1");
        System.setProperty(DistributedForkProperties.PROP_RUNNER_KEY, "runner-c");

        // when
        DistributedRunnerContext context = DistributedForkProperties.contextFromSystemProperties();

        // then
        assertNotNull(context, "a surplus runner is still a distributed runner and must not take "
                + "the single-host path");
        assertFalse(context.isClaimed(), "a surplus runner holds no group");
        assertEquals("run-1", context.getRunId());
        assertEquals("runner-c", context.getRunnerKey());
    }

    /**
     * Verify a distributed fork missing its run id fails, naming the property. Resolving it to no
     * context would be worse than failing: the fork would persist as a single host and seal a
     * build the other runners are still contributing to.
     */
    @Test
    void shouldFailWhenADistributedForkHasNoRunId() {
        // given
        System.setProperty(DistributedForkProperties.PROP_DISTRIBUTED, "true");
        System.setProperty(DistributedForkProperties.PROP_RUNNER_KEY, "runner-a");
        System.setProperty(DistributedForkProperties.PROP_GROUP_NUMBER, "0");

        // when
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                DistributedForkProperties::contextFromSystemProperties);

        // then
        assertTrue(thrown.getMessage().contains("tiaRunId"), thrown.getMessage());
    }

    /**
     * Verify a distributed fork missing its runner key fails, naming the property. The completion
     * write is guarded on the key the claim was recorded under, so a fork without it could never
     * complete its group and the run would never seal.
     */
    @Test
    void shouldFailWhenADistributedForkHasNoRunnerKey() {
        // given
        System.setProperty(DistributedForkProperties.PROP_DISTRIBUTED, "true");
        System.setProperty(DistributedForkProperties.PROP_RUN_ID, "run-1");
        System.setProperty(DistributedForkProperties.PROP_GROUP_NUMBER, "0");

        // when
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                DistributedForkProperties::contextFromSystemProperties);

        // then
        assertTrue(thrown.getMessage().contains("runner key"), thrown.getMessage());
    }

    /**
     * Verify an unparseable group number fails, naming the property and the value. Falling back to
     * "no group" would have the runner silently persist nothing and never complete its group, so
     * the build would hang unsealed with nothing recording why.
     */
    @Test
    void shouldFailWhenTheForwardedGroupNumberIsNotANumber() {
        // given
        System.setProperty(DistributedForkProperties.PROP_DISTRIBUTED, "true");
        System.setProperty(DistributedForkProperties.PROP_RUN_ID, "run-1");
        System.setProperty(DistributedForkProperties.PROP_RUNNER_KEY, "runner-a");
        System.setProperty(DistributedForkProperties.PROP_GROUP_NUMBER, "not-a-number");

        // when
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                DistributedForkProperties::contextFromSystemProperties);

        // then
        assertTrue(thrown.getMessage().contains("tiaDistributedGroupNumber"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("not-a-number"), thrown.getMessage());
    }

    /**
     * Verify the writer emits every value a claimed runner's fork needs, and that the fork reads
     * back exactly what was claimed. This round trip is the property the two halves exist for: a
     * name that drifted between them would leave the fork on the single-host path.
     */
    @Test
    void shouldRoundTripAClaimedRunnersValuesThroughSystemProperties() {
        // given
        Map<String, String> properties = DistributedForkProperties.forkProperties("run-1",
                "runner-a", Integer.valueOf(3));

        // when
        publish(properties);
        DistributedRunnerContext context = DistributedForkProperties.contextFromSystemProperties();

        // then
        assertEquals("true", properties.get(DistributedForkProperties.PROP_DISTRIBUTED));
        assertNotNull(context);
        assertEquals("run-1", context.getRunId());
        assertEquals("runner-a", context.getRunnerKey());
        assertEquals(Integer.valueOf(3), context.getGroupNumber());
    }

    /**
     * Verify a surplus runner's fork properties carry no group number, and that the fork reads
     * back a groupless context. A group number it did not claim would have the fork complete
     * another runner's group and release the barrier early.
     */
    @Test
    void shouldRoundTripASurplusRunnerWithNoGroupNumber() {
        // given
        Map<String, String> properties = DistributedForkProperties.forkProperties("run-1",
                "runner-c", null);

        // when
        publish(properties);
        DistributedRunnerContext context = DistributedForkProperties.contextFromSystemProperties();

        // then
        assertFalse(properties.containsKey(DistributedForkProperties.PROP_GROUP_NUMBER),
                properties.toString());
        assertNotNull(context);
        assertFalse(context.isClaimed());
        assertEquals("runner-c", context.getRunnerKey());
    }
}
