package org.tiatesting.core.distributed;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Carries a claimed distributed run across the build-JVM-to-forked-test-JVM boundary. Maven claims
 * a group in the build JVM, before surefire forks; the forked JVM is what actually runs the suites,
 * completes the group and stands for election to seal the build. Neither of the two values the
 * claim produced can be reconstructed over there - the runner key may have been derived by the
 * claim, and the group number is only known to whoever won it - so both halves live here, in one
 * class and against the same constants, so the property the build plugin writes and the property
 * the test listener reads cannot drift apart. {@code DistributedRunSystemProperties} in
 * {@code tia-spock} is shaped the same way for the Gradle side of the same problem.
 *
 * <p>The values travel in the fork properties file (see {@code ForkSystemProperties}), which the
 * Tia agent republishes as system properties at {@code premain} time, before any listener
 * constructs. That is why the read half here takes no arguments: by the time a listener asks, the
 * agent has already made the values look exactly like ordinary system properties.
 *
 * <p>Nothing about how the build was split crosses this boundary, because the fork cannot act on
 * it - the split is the planner's decision and is already recorded in the plan the group was
 * claimed from.
 *
 * <p>See the distributed test runs chapter in {@code WIKI.md} for the lifecycle these properties
 * feed.
 */
public final class DistributedForkProperties {

    /** System property carrying the distributed-run master switch. */
    public static final String PROP_DISTRIBUTED = "tiaDistributed";

    /** System property carrying the shared identifier of the run this fork belongs to. */
    public static final String PROP_RUN_ID = "tiaRunId";

    /** System property carrying the identity the group was claimed under. */
    public static final String PROP_RUNNER_KEY = "tiaDistributedRunnerKey";

    /** System property carrying the claimed group number, absent for a surplus runner. */
    public static final String PROP_GROUP_NUMBER = "tiaDistributedGroupNumber";

    private DistributedForkProperties() {
    }

    /**
     * Build the properties a claimed runner's build JVM must hand to its forked test JVM.
     *
     * <p>The group number is omitted rather than emitted empty when this runner claimed no group:
     * a surplus runner that carried a group number it does not own would complete another runner's
     * group, releasing the barrier while that runner is still writing its mapping rows.
     *
     * @param runId the run this runner claimed from
     * @param runnerKey the identity the claim was recorded under; must be the value the claim
     *                  returned rather than one re-derived in the fork, or the guarded completion
     *                  write will match no row and the run will never seal
     * @param groupNumber the claimed group, or null when every group was already taken
     * @return the properties to write to the fork properties file
     */
    public static Map<String, String> forkProperties(final String runId, final String runnerKey,
                                                      final Integer groupNumber) {
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put(PROP_DISTRIBUTED, String.valueOf(true));
        properties.put(PROP_RUN_ID, runId);
        properties.put(PROP_RUNNER_KEY, runnerKey);
        if (groupNumber != null) {
            properties.put(PROP_GROUP_NUMBER, String.valueOf(groupNumber));
        }
        return properties;
    }

    /**
     * Resolve the distributed run this forked test JVM is a runner for, or report that it is not
     * one so its persist takes the ordinary single-host flow.
     *
     * <p>Returns null - before reading anything else - whenever {@link #PROP_DISTRIBUTED} is absent
     * or false, which is every ordinary build. Every other outcome is a context, never a null:
     * a fork that resolved no context while its build really is distributed would rebuild the
     * method catalogue and stamp the commit for itself, from an edge set missing every other
     * group's suites, so the methods only those groups reach would be dropped and the tests
     * covering them would silently stop being selected.
     *
     * <p>For the same reason a distributed fork missing its run id or runner key fails rather than
     * degrades: there is no safe way to persist a runner's share without knowing which run it is a
     * share of.
     *
     * @return the context to persist this runner's share under, or null when this fork is not a
     *         distributed runner
     * @throws IllegalArgumentException if the fork is distributed but its run id or runner key is
     *                                  missing, or its group number is not a number; the message
     *                                  names the property to fix
     */
    public static DistributedRunnerContext contextFromSystemProperties() {
        return contextFromProperties(System.getProperties());
    }

    /**
     * Resolve the distributed run context described by an arbitrary {@link Properties} instance,
     * applying the same "blank group number means a surplus runner" rule {@link
     * #contextFromSystemProperties()} applies to the live system properties - without touching
     * this JVM's system properties.
     *
     * <p>This is the single copy of that rule. {@link #contextFromSystemProperties()} delegates to
     * it for the fork's own resolution from its live system properties; a build-JVM step that reads
     * the fork properties file back directly - without ever publishing it into its own process's
     * system properties, since that process is not the fork - calls this overload with the
     * {@link Properties} it read from the file instead. Either caller gets the same group-number
     * parsing, via {@link #parseGroupNumber}, and so the same actionable message naming the
     * offending property on a malformed value.
     *
     * @param properties the properties to resolve the context from - either the JVM's live system
     *                   properties or a fork properties file's contents read back directly
     * @return the context described by {@code properties}, or null when {@link #PROP_DISTRIBUTED}
     *         is absent or false
     * @throws IllegalArgumentException if {@code properties} describes a distributed run but its
     *                                  run id or runner key is missing, or its group number is not
     *                                  a number; the message names the property to fix
     */
    public static DistributedRunnerContext contextFromProperties(final Properties properties) {
        if (!Boolean.parseBoolean(properties.getProperty(PROP_DISTRIBUTED))) {
            return null;
        }

        String runId = properties.getProperty(PROP_RUN_ID);
        String runnerKey = properties.getProperty(PROP_RUNNER_KEY);
        String groupNumber = properties.getProperty(PROP_GROUP_NUMBER);

        if (groupNumber == null || groupNumber.trim().isEmpty()) {
            // A surplus runner: the pipeline fanned out wider than the plan's group count, so the
            // build JVM claimed nothing and forwarded no group. It has nothing to persist, but it
            // is still a distributed runner and must not take the single-host path.
            return DistributedRunnerContext.surplusRunner(runId, runnerKey);
        }

        return DistributedRunnerContext.forClaimedGroup(runId, runnerKey,
                parseGroupNumber(groupNumber));
    }

    /**
     * Parse the forwarded group number, naming the property and the offending value on failure so
     * a misconfigured runner is told exactly what to fix.
     *
     * @param groupNumber the raw property value
     * @return the parsed group number
     * @throws IllegalArgumentException if the value is not a number
     */
    private static int parseGroupNumber(final String groupNumber) {
        try {
            return Integer.parseInt(groupNumber.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(PROP_GROUP_NUMBER + " must be the number of the "
                    + "group this runner claimed, was '" + groupNumber + "'", e);
        }
    }
}
