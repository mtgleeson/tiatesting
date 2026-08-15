package org.tiatesting.spock.distributed;

import org.tiatesting.core.distributed.DistributedForkProperties;
import org.tiatesting.core.distributed.DistributedRunConfig;
import org.tiatesting.core.distributed.DistributedRunPreconditions;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Carries a distributed test run across the Gradle-to-test-JVM boundary. The Gradle plugin runs in
 * the daemon, where the {@code tia { ... }} extension's values live; the forked test JVM sees only
 * system properties. Both halves live here, in the same class and against the same constants, so
 * the property the plugin sets and the property the Spock extension reads cannot drift apart -
 * the same reason {@code StaticTestSelectionSystemProperties} and
 * {@code LibraryMetadataSystemProperties} are shaped this way.
 *
 * <p>Only two values cross: the run id and the runner's identity. Nothing about how the run was
 * split does, because a runner cannot act on it - the split is the planner's decision and is
 * already recorded in the plan the runner is about to claim from.
 *
 * <p>Unlike Maven, which claims its group in the build JVM before surefire forks and has to hand
 * the resolved runner key and group number on to the fork, a Gradle runner claims inside the test
 * JVM itself. So there is no claim result to forward here, only the configuration the claim is
 * made with.
 *
 * <p>See the "Group assignment" chapter in {@code WIKI.md} for the claim protocol these properties
 * feed.
 */
public final class DistributedRunSystemProperties {

    /**
     * System property carrying the distributed-run master switch. Taken from
     * {@link DistributedForkProperties} rather than spelled out again: Maven forwards the same
     * three values to its forked test JVM, and a Gradle build that named one of them differently
     * would leave a runner silently taking the single-host path.
     */
    public static final String PROP_DISTRIBUTED = DistributedForkProperties.PROP_DISTRIBUTED;

    /** System property carrying the shared identifier of the run to claim from. */
    public static final String PROP_RUN_ID = DistributedForkProperties.PROP_RUN_ID;

    /** System property carrying this runner's identity, absent to let the claim derive one. */
    public static final String PROP_RUNNER_KEY = DistributedForkProperties.PROP_RUNNER_KEY;

    private DistributedRunSystemProperties() {
    }

    /**
     * Resolve the distributed run this test JVM is a runner for, or report that it is not one.
     *
     * <p>Returns null - before checking anything else - whenever {@link #PROP_DISTRIBUTED} is
     * absent or false, which is every ordinary build. That ordering is deliberate: a developer
     * running a normal Gradle build against an embedded database must not be failed by a
     * distributed run's preconditions.
     *
     * <p>{@link DistributedRunPreconditions#check} enforces four rules; of those, the same three
     * that do not depend on the reactor - Tia enabled, a shared database, and local-changes
     * checking off - are enforced again here rather than trusted from the planning job, because a
     * runner is a separate process with its own configuration and can be misconfigured on its own:
     * one pointed at an embedded database would see no other runner's claims at all and would claim
     * group 0 alongside every other runner in the pipeline. The fourth rule, the reactor-size
     * check, is passed a literal {@code 1} below so it never fires here - see the comment on that
     * call for why, which is not the same reason a Maven runner is exempt from it.
     *
     * @return the configuration for claiming a group, or null when this build is not a distributed
     *         runner
     * @throws IllegalStateException if Tia is disabled, the configured database is not one every
     *                                runner can share, or local-changes checking is enabled
     * @throws IllegalArgumentException if no run id is configured, naming the property to set
     */
    public static DistributedRunConfig runnerConfigFromSystemProperties() {
        if (!Boolean.parseBoolean(System.getProperty(PROP_DISTRIBUTED))) {
            return null;
        }

        // Passing 1 here is correct, but not because this call sees only one project the way a
        // Maven claim does. A forked test JVM cannot see the Gradle multi-project build it belongs
        // to at all - it has no Project reference, only system properties - so it has no reactor
        // size to pass even if it wanted to. It is safe to always claim to be single-project because
        // TiaBasePlugin.getReactorProjects() reads project.getRootProject().getAllprojects(), so
        // planning already refuses ANY multi-project Gradle build, whichever project tia-dist-plan
        // was invoked on. There is no Gradle analogue of Maven's "mvn -pl <module>" that could invoke
        // planning against a single module of a larger reactor - which is precisely what makes the
        // Maven claim-time gap this rule closes reachable, and leaves no equivalent gap on Gradle.
        // If a Gradle claim-time guard is ever wanted anyway, it belongs in
        // TiaSpockGitGradlePluginTestExtension.applyTo, which runs in the build JVM at task-action
        // time with task.getProject() available and already forwards distributed properties into
        // the fork via forwardDistributedRunConfig - it is not blocked by the same "no Project
        // reference" limitation this method has.
        DistributedRunPreconditions.check(Boolean.parseBoolean(System.getProperty("tiaEnabled")), 1,
                System.getProperty("tiaDBUrl"), System.getProperty("tiaDBDialect"),
                Boolean.parseBoolean(System.getProperty("tiaCheckLocalChanges")));

        // forRunner, not validated: a runner configures the run it belongs to and who it is, never
        // a group count or a target run time. Requiring those of a runner would make every runner
        // job repeat configuration only the planning job uses, and would accept a value
        // disagreeing with the plan's while silently ignoring it.
        return DistributedRunConfig.forRunner(System.getProperty(PROP_RUN_ID),
                System.getProperty(PROP_RUNNER_KEY));
    }

    /**
     * Build the system properties the Gradle plugin must start the forked test JVM with, so
     * {@link #runnerConfigFromSystemProperties()} resolves the same run over there.
     *
     * <p>A non-distributed build produces an empty map rather than properties set to false, so an
     * ordinary build's test JVM is started with exactly the properties it was started with before
     * distributed runs existed. An unconfigured runner key is left out for a different reason: a
     * forwarded literal "null" would be claimed under, giving every runner in the pipeline one
     * shared identity, and the second claim would be read as the first runner's job retrying.
     *
     * @param distributed the configured distributed master switch, which may be null when the user
     *                    never set it
     * @param runId the configured shared run identifier, forwarded as-is so that a missing one
     *              fails on the runner naming the property rather than silently running everything
     * @param runnerKey the configured runner identity, or null to let the claim derive one
     * @return the properties to forward, empty when this build is not a distributed runner
     */
    public static Map<String, String> forwardedProperties(final Boolean distributed, final String runId,
                                                           final String runnerKey) {
        if (!Boolean.TRUE.equals(distributed)) {
            return Collections.emptyMap();
        }

        Map<String, String> properties = new LinkedHashMap<>();
        properties.put(PROP_DISTRIBUTED, String.valueOf(true));
        if (runId != null) {
            properties.put(PROP_RUN_ID, runId);
        }
        if (runnerKey != null) {
            properties.put(PROP_RUNNER_KEY, runnerKey);
        }
        return properties;
    }
}
