package org.tiatesting.core.testrunner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tiatesting.core.model.RunOrigin;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * Resolve the {@link RunOrigin} to stamp on a test run's history row: whether this is a CI build or
 * a developer's local run, and which machine is executing it.
 *
 * <p><b>Why environment variables rather than configuration.</b> Every mainstream CI system exports
 * a marker variable, and a forked test JVM inherits its parent's environment, so the detection works
 * in the fork with no plumbing through the build plugins and nothing for a developer to set up. A
 * project that had to configure this per job would end up with mislabelled rows from whichever job
 * forgot - and a mislabelled row is worse than no column, because it looks authoritative.
 *
 * <p><b>The escape hatch.</b> A CI system this does not recognise, or a build that wants to declare
 * itself something else, sets {@code tiaRunSource} as a system property or {@code TIA_RUN_SOURCE} as
 * an environment variable. The environment variable is the more reliable of the two from a build
 * plugin's point of view, since it reaches the forked test JVM by inheritance; a system property set
 * on the build JVM's command line does not, unless the build forwards it. See the "Test run history"
 * chapter in {@code WIKI.md}.
 */
public final class RunEnvironment {

    private static final Logger log = LoggerFactory.getLogger(RunEnvironment.class);

    /** System property that overrides the detected run source. */
    public static final String PROP_RUN_SOURCE = "tiaRunSource";

    /** Environment variable that overrides the detected run source. */
    public static final String ENV_RUN_SOURCE = "TIA_RUN_SOURCE";

    /**
     * Environment variables whose mere presence marks the run as a CI build. {@code CI} is set by
     * GitHub Actions, GitLab CI, CircleCI, Travis and others as a de-facto convention; the rest
     * cover systems that do not set it. Presence is what counts, not the value - Jenkins sets
     * {@code BUILD_NUMBER} to a number, GitHub sets {@code CI} to "true", and a system that sets one
     * of these to something unexpected is still a CI system.
     */
    private static final List<String> CI_MARKER_ENV_VARS = Arrays.asList(
            "CI",
            "BUILD_NUMBER",
            "JENKINS_URL",
            "GITHUB_ACTIONS",
            "GITLAB_CI",
            "TEAMCITY_VERSION",
            "BUILDKITE",
            "CIRCLECI",
            "TF_BUILD",
            "bamboo_buildKey");

    private RunEnvironment() {
    }

    /**
     * Resolve the origin of the current run from this JVM's environment and system properties.
     *
     * @return the run source and the local hostname, either of which may be null when it cannot be
     *         determined
     */
    public static RunOrigin currentRunOrigin() {
        return RunOrigin.of(runSource(), hostName());
    }

    /**
     * Resolve the origin of a distributed build, which is one row describing work several machines
     * did between them. The source still applies to the build as a whole; the host deliberately does
     * not - naming the machine that happened to seal last would read as "this build ran here", which
     * is exactly what a distributed build did not do.
     *
     * @return the run source, with a null hostname
     */
    public static RunOrigin distributedRunOrigin() {
        return RunOrigin.of(runSource(), null);
    }

    /**
     * Resolve the run source from this JVM's system properties and environment.
     *
     * @return the declared override if one is set, otherwise {@link RunOrigin#SOURCE_CI} when any
     *         recognised CI marker variable is present and {@link RunOrigin#SOURCE_LOCAL} when none is
     */
    public static String runSource() {
        return runSource(System.getProperty(PROP_RUN_SOURCE), System::getenv);
    }

    /**
     * Resolve the run source from supplied lookups, so the precedence and the marker-variable set can
     * be tested without mutating the JVM's real environment.
     *
     * @param propertyOverride the {@code tiaRunSource} system property value, or null when unset
     * @param env lookup from environment-variable name to value, returning null for an unset variable
     * @return the resolved run source; never null
     */
    static String runSource(final String propertyOverride, final UnaryOperator<String> env) {
        if (isSet(propertyOverride)) {
            return propertyOverride.trim();
        }

        String envOverride = env.apply(ENV_RUN_SOURCE);
        if (isSet(envOverride)) {
            return envOverride.trim();
        }

        for (String markerVar : CI_MARKER_ENV_VARS) {
            if (isSet(env.apply(markerVar))) {
                log.debug("Run source detected as CI from the environment variable {}", markerVar);
                return RunOrigin.SOURCE_CI;
            }
        }

        return RunOrigin.SOURCE_LOCAL;
    }

    /**
     * Resolve the local hostname for the history row.
     *
     * @return the local hostname, or null when it cannot be resolved. Null rather than a placeholder
     *         because the column means "the machine that ran this", and an unresolvable hostname is a
     *         genuine absence of that fact - not a machine called "unknown-host" that several
     *         unrelated runs would then appear to share
     */
    public static String hostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            log.debug("Could not resolve the local hostname for the test run history row. The row "
                    + "will be stored without one.", e);
            return null;
        }
    }

    /**
     * Whether a looked-up value counts as present: non-null and not blank, so an exported-but-empty
     * variable does not read as a CI marker.
     *
     * @param value the value to check; may be null
     * @return true when the value is non-null and has non-whitespace content
     */
    private static boolean isSet(final String value) {
        return value != null && !value.trim().isEmpty();
    }
}
