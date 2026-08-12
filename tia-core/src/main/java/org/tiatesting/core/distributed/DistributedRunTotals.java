package org.tiatesting.core.distributed;

import org.tiatesting.core.model.DistributedRunGroup;

import java.util.List;

/**
 * The whole-build figures a distributed run's completed groups add up to, computed client-side from
 * the group rows the sealer reads. This is what turns a build split across runners back into the
 * one row and one set of stats a single-host run produces for itself.
 *
 * <p><b>Two durations, and they are not interchangeable.</b>
 * <ul>
 *   <li>The <b>serial-equivalent</b> duration is the sum of every group's test-execution time -
 *       what the same selection would have cost on one host. It is the primary figure: the
 *       Tia-level stats and the savings calculation are computed from it, so savings keep meaning
 *       "time saved by not running unimpacted tests" and a project's history stays comparable
 *       across the point where it switched distributed mode on.</li>
 *   <li>The <b>wall clock</b> is the slowest group, since the build was not finished until that
 *       group was. It is recorded alongside so the user can see what the build actually took and
 *       whether the configured target was met - never as the primary duration, which would credit
 *       Tia with the parallelism the CI system provided and would quietly redefine
 *       {@code avgRunTime}.</li>
 * </ul>
 *
 * <p>The suite counters are plain sums because the plan assigns each suite to exactly one group, so
 * no suite can be counted twice.
 *
 * <p>See the distributed test runs chapter in {@code WIKI.md} for how these figures are reported.
 */
public final class DistributedRunTotals {

    private final int groupCount;
    private final long serialDurationMs;
    private final long wallClockMs;
    private final int suitesRan;
    private final int suitesFailed;

    /**
     * Store the computed totals.
     *
     * @param groupCount the number of groups the build was split across
     * @param serialDurationMs the summed test-execution time of every group, in ms
     * @param wallClockMs the slowest group's test-execution time, in ms
     * @param suitesRan the number of suites the build executed across every group
     * @param suitesFailed the number of suites with at least one failed test across every group
     */
    private DistributedRunTotals(final int groupCount, final long serialDurationMs,
                                 final long wallClockMs, final int suitesRan,
                                 final int suitesFailed) {
        this.groupCount = groupCount;
        this.serialDurationMs = serialDurationMs;
        this.wallClockMs = wallClockMs;
        this.suitesRan = suitesRan;
        this.suitesFailed = suitesFailed;
    }

    /**
     * Total up a run's groups. A group with no recorded duration contributes no time rather than
     * failing the seal: the sealer is only reached once every group is complete and a completion
     * always records its duration, so this cannot normally happen, and abandoning a build that has
     * already run all its tests would be a far worse answer than under-reporting its time.
     *
     * @param groups the run's groups, as read back from the datastore after the barrier
     * @return the whole build's figures; all zero when there are no groups
     */
    public static DistributedRunTotals from(final List<DistributedRunGroup> groups) {
        long serialDurationMs = 0L;
        long wallClockMs = 0L;
        int suitesRan = 0;
        int suitesFailed = 0;

        for (DistributedRunGroup group : groups) {
            Long groupDurationMs = group.getActualDurationMs();
            if (groupDurationMs != null) {
                serialDurationMs += groupDurationMs.longValue();
                wallClockMs = Math.max(wallClockMs, groupDurationMs.longValue());
            }
            suitesRan += group.getSuitesRan();
            suitesFailed += group.getSuitesFailed();
        }

        return new DistributedRunTotals(groups.size(), serialDurationMs, wallClockMs, suitesRan,
                suitesFailed);
    }

    /** @return the number of groups the build was split across */
    public int getGroupCount() { return groupCount; }

    /**
     * @return the serial-equivalent test-execution time in ms - the sum of every group's time, and
     *         the figure the stats and savings are computed from
     */
    public long getSerialDurationMs() { return serialDurationMs; }

    /**
     * @return the build's wall-clock test time in ms - the slowest group's time, reported alongside
     *         the serial figure rather than in place of it
     */
    public long getWallClockMs() { return wallClockMs; }

    /** @return the number of suites the build executed across every group */
    public int getSuitesRan() { return suitesRan; }

    /** @return the number of suites with at least one failed test across every group */
    public int getSuitesFailed() { return suitesFailed; }

    /**
     * Diagnostic rendering naming both durations and the counters.
     *
     * @return a short human-readable description
     */
    @Override
    public String toString() {
        return "DistributedRunTotals{groups=" + groupCount + ", serialMs=" + serialDurationMs
                + ", wallClockMs=" + wallClockMs + ", suitesRan=" + suitesRan
                + ", suitesFailed=" + suitesFailed + "}";
    }
}
