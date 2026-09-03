package org.tiatesting.core.model;

import java.io.Serializable;
import java.util.Objects;

/**
 * Where a recorded test run came from: whether it was a CI build or a developer's local run, and
 * which machine executed it.
 *
 * <p>Carried on {@link TestRunHistoryEntry} so the history table can be split by origin. Without it
 * the only available discriminator is {@code updated_db_mapping}, which is a proxy rather than a
 * fact about the run - a CI job configured with mapping updates off is indistinguishable from a
 * developer's laptop - and there is nothing at all to group runs by machine, so a per-machine
 * average silently mixes a maxed-out laptop with a workstation. See the "Test run history" chapter
 * in {@code WIKI.md}.
 *
 * <p>Both components are nullable and mean "not known" when null: rows written before these columns
 * existed read back that way, as does a run whose hostname could not be resolved.
 */
public final class RunOrigin implements Serializable {

    private static final long serialVersionUID = 1L;

    /** {@link #getRunSource()} value for a run detected as, or declared to be, a CI build. */
    public static final String SOURCE_CI = "CI";

    /** {@link #getRunSource()} value for a run that is not a CI build - typically a developer's machine. */
    public static final String SOURCE_LOCAL = "LOCAL";

    private final String runSource;
    private final String hostName;

    private RunOrigin(final String runSource, final String hostName) {
        this.runSource = runSource;
        this.hostName = hostName;
    }

    /**
     * Build an origin from an already-resolved source and host.
     *
     * @param runSource where the run came from, conventionally {@link #SOURCE_CI} or
     *                  {@link #SOURCE_LOCAL}; may be null when not known
     * @param hostName the machine that executed the run; may be null when not known
     * @return the populated origin
     */
    public static RunOrigin of(final String runSource, final String hostName) {
        return new RunOrigin(runSource, hostName);
    }

    /**
     * The origin of a run nothing is known about: a history row written before these columns
     * existed, or a test that does not care about the origin.
     *
     * @return an origin with both components null
     */
    public static RunOrigin unknown() {
        return new RunOrigin(null, null);
    }

    /**
     * @return where the run came from ({@link #SOURCE_CI} / {@link #SOURCE_LOCAL}), or null when the
     *         run predates the column or its source could not be determined
     */
    public String getRunSource() { return runSource; }

    /**
     * @return the machine that executed the run, or null when the run predates the column, the
     *         hostname could not be resolved, or the run spanned several machines (a distributed
     *         build, where no single host executed it)
     */
    public String getHostName() { return hostName; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RunOrigin)) return false;
        RunOrigin that = (RunOrigin) o;
        return Objects.equals(runSource, that.runSource) && Objects.equals(hostName, that.hostName);
    }

    @Override
    public int hashCode() { return Objects.hash(runSource, hostName); }

    @Override
    public String toString() {
        return "RunOrigin{runSource='" + runSource + "', hostName='" + hostName + "'}";
    }
}
