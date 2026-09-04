package org.tiatesting.core.distributed;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tiatesting.core.model.DistributedRunGroup;

import java.util.List;

/**
 * The whole-build figures a distributed run's completed groups add up to, computed client-side from
 * the group rows the sealer reads. This is what turns a build split across runners back into the
 * one row and one set of stats a single-host run produces for itself.
 *
 * <p><b>Two durations, and they are not interchangeable.</b>
 * <ul>
 *   <li>The <b>serial-equivalent</b> duration is what the same selection would have cost on one
 *       host: every group's suite-execution time, plus the fixed per-JVM overhead <b>once</b>. It is
 *       the primary figure: the Tia-level stats and the savings calculation are computed from it, so
 *       savings keep meaning "time saved by not running unimpacted tests" and a project's history
 *       stays comparable across the point where it switched distributed mode on.</li>
 *   <li>The <b>wall clock</b> is the slowest group, since the build was not finished until that
 *       group was. It is recorded alongside so the user can see what the build actually took and
 *       whether the configured target was met - never as the primary duration, which would credit
 *       Tia with the parallelism the CI system provided and would quietly redefine
 *       {@code avgRunTime}.</li>
 * </ul>
 *
 * <p><b>Why the overhead is charged once.</b> A plain sum of the group durations is not the
 * serial-equivalent time, because every runner pays a fixed per-JVM cost inside its measured window
 * - engine start-up, class loading, the gaps between suites, the coverage dump - that a single-host
 * run of the same suites would pay once. Summing it charges that cost once per group, so a build
 * fanned out ten ways looks minutes slower than the same selection run serially. That distortion is
 * not cosmetic: it under-reports savings on a partial build, and on an all-tests build it inflates
 * the full-suite baseline that <em>every later</em> savings figure is measured against.
 *
 * <p>Each runner reports the split ({@link DistributedRunGroup#getSuitesDurationMs()} against
 * {@link DistributedRunGroup#getActualDurationMs()}), so the overhead is measured rather than
 * guessed, and the correction is one subtraction:
 *
 * <pre>
 *   overhead(group)  = actualDuration - suitesDuration     (clamped at 0)
 *   fixedOverhead    = min over groups of overhead(group)
 *   serialEquivalent = Σ actualDuration - (groups - 1) × fixedOverhead
 * </pre>
 *
 * <p>The <b>minimum</b> is the right estimator of the shared fixed cost, not the mean or the
 * maximum: it is the largest amount every runner demonstrably paid, so subtracting {@code N-1}
 * copies of it can never subtract time that was not there. It also leaves the variable part of each
 * group's overhead in the total, which is what a build with a Surefire retry needs - the retry's
 * wall time lands in that group's overhead remainder (a retry re-runs failed tests without timing a
 * fresh suite) and is real time the build spent, so it must survive the correction rather than being
 * mistaken for a duplicated fixed cost.
 *
 * <p><b>The correction is skipped when the split is not available.</b> Suite times are only measured
 * on a mapping-owning build, so a group can complete having run suites and report zero
 * suite-attributable time. Reading that as "this group was pure overhead" would make
 * {@code fixedOverhead} the whole of the fastest group's duration and gut the total, so any group
 * that ran suites without reporting suite time disqualifies the whole build and the serial figure
 * falls back to the plain sum - the behaviour that predates the split. Falling back over-states the
 * duration, which under-reports savings and makes Tia look worse than it is; applying a correction
 * off a figure that was never measured under-states the duration, which over-reports savings and
 * claims credit the build did not earn. Only one of those two is safe to be wrong about. The
 * fall-back error is also bounded - at worst it is the behaviour that predates the split - whereas
 * a correction computed from an unmeasured split can drive the total towards zero.
 *
 * <p>The suite counters are plain sums because the plan assigns each suite to exactly one group, so
 * no suite can be counted twice.
 *
 * <p>See the distributed test runs chapter in {@code WIKI.md} for how these figures are reported.
 */
public final class DistributedRunTotals {

    private static final Logger log = LoggerFactory.getLogger(DistributedRunTotals.class);

    private final int groupCount;
    private final long serialDurationMs;
    private final long wallClockMs;
    private final int suitesRan;
    private final int suitesFailed;
    private final long fixedOverheadMs;

    /**
     * Store the computed totals.
     *
     * @param groupCount the number of groups the build was split across
     * @param serialDurationMs the serial-equivalent test-execution time, in ms
     * @param wallClockMs the slowest group's test-execution time, in ms
     * @param suitesRan the number of suites the build executed across every group
     * @param suitesFailed the number of suites with at least one failed test across every group
     * @param fixedOverheadMs the per-JVM fixed overhead charged once, in ms; 0 when the build's
     *                        groups did not report the split needed to measure it
     */
    private DistributedRunTotals(final int groupCount, final long serialDurationMs,
                                 final long wallClockMs, final int suitesRan,
                                 final int suitesFailed, final long fixedOverheadMs) {
        this.groupCount = groupCount;
        this.serialDurationMs = serialDurationMs;
        this.wallClockMs = wallClockMs;
        this.suitesRan = suitesRan;
        this.suitesFailed = suitesFailed;
        this.fixedOverheadMs = fixedOverheadMs;
    }

    /**
     * Total up a run's groups, charging the fixed per-JVM overhead once for the build rather than
     * once per group. A group with no recorded duration contributes no time rather than failing the
     * seal: the sealer is only reached once every group is complete and a completion always records
     * its duration, so this cannot normally happen, and abandoning a build that has already run all
     * its tests would be a far worse answer than under-reporting its time. Such a group is also left
     * out of the overhead measurement entirely - it has no measurement to contribute.
     *
     * @param groups the run's groups, as read back from the datastore after the barrier
     * @return the whole build's figures; all zero when there are no groups
     */
    public static DistributedRunTotals from(final List<DistributedRunGroup> groups) {
        long summedDurationMs = 0L;
        long wallClockMs = 0L;
        int suitesRan = 0;
        int suitesFailed = 0;
        int measuredGroups = 0;

        for (DistributedRunGroup group : groups) {
            Long groupDurationMs = group.getActualDurationMs();
            if (groupDurationMs != null) {
                summedDurationMs += groupDurationMs.longValue();
                wallClockMs = Math.max(wallClockMs, groupDurationMs.longValue());
                measuredGroups++;
            }
            suitesRan += group.getSuitesRan();
            suitesFailed += group.getSuitesFailed();
        }

        // One copy of the overhead is kept, so the multiplier counts the groups that actually
        // contributed a duration - not groups.size(), which would subtract a copy on behalf of a
        // group that added no time to the sum in the first place.
        long fixedOverheadMs = fixedOverheadMs(groups);
        long serialDurationMs = Math.max(0L,
                summedDurationMs - (long) Math.max(0, measuredGroups - 1) * fixedOverheadMs);

        log.debug("Distributed run totals: {} group(s), {} with a recorded duration. Summed "
                        + "duration {}ms, fixed per-JVM overhead {}ms charged once (deducted {} "
                        + "time(s)), giving a serial-equivalent duration of {}ms against a wall "
                        + "clock of {}ms.", groups.size(), measuredGroups, summedDurationMs,
                fixedOverheadMs, Math.max(0, measuredGroups - 1), serialDurationMs, wallClockMs);

        return new DistributedRunTotals(groups.size(), serialDurationMs, wallClockMs, suitesRan,
                suitesFailed, fixedOverheadMs);
    }

    /**
     * Measure the fixed per-JVM overhead the build pays once: the smallest gap any group left
     * between its total duration and the time it could attribute to named suites. See this class's
     * javadoc for why the minimum is the estimator and why the whole correction is abandoned when
     * one group cannot supply the split.
     *
     * @param groups the run's groups, as read back from the datastore after the barrier
     * @return the fixed overhead in ms, or 0 when it cannot be measured - which is also the value
     *         that makes the correction a no-op
     */
    private static long fixedOverheadMs(final List<DistributedRunGroup> groups) {
        long minOverheadMs = Long.MAX_VALUE;

        for (DistributedRunGroup group : groups) {
            Long groupDurationMs = group.getActualDurationMs();
            if (groupDurationMs == null) {
                // Nothing measured for this group at all, so it can neither supply an overhead
                // figure nor invalidate anyone else's.
                continue;
            }

            if (group.getSuitesRan() > 0 && group.getSuitesDurationMs() <= 0L) {
                // This group ran suites but timed none of them, so its whole duration would look
                // like overhead. That is the un-decomposable case: correct nothing at all.
                log.debug("Distributed run: group {} ran {} suite(s) but reported no suite time, "
                                + "so the build's per-JVM overhead cannot be measured. Charging the "
                                + "overhead once is skipped for the whole build and the "
                                + "serial-equivalent duration falls back to the plain sum of the "
                                + "group durations. Suite times are only recorded by a build that "
                                + "updates the mapping DB.",
                        group.getGroupNumber(), group.getSuitesRan());
                return 0L;
            }

            long overheadMs = Math.max(0L, groupDurationMs.longValue() - group.getSuitesDurationMs());
            log.debug("Distributed run: group {} took {}ms, of which {}ms was attributable to named "
                            + "suites, leaving {}ms of per-JVM overhead.", group.getGroupNumber(),
                    groupDurationMs, group.getSuitesDurationMs(), overheadMs);
            minOverheadMs = Math.min(minOverheadMs, overheadMs);
        }

        return minOverheadMs == Long.MAX_VALUE ? 0L : minOverheadMs;
    }

    /** @return the number of groups the build was split across */
    public int getGroupCount() { return groupCount; }

    /**
     * @return the serial-equivalent test-execution time in ms - every group's suite time plus the
     *         fixed per-JVM overhead once, and the figure the stats and savings are computed from
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
     * @return the fixed per-JVM overhead in ms that the serial-equivalent duration charges once
     *         instead of once per group; 0 when the groups did not report the split it is measured
     *         from, in which case the serial figure is the plain sum of the group durations
     */
    public long getFixedOverheadMs() { return fixedOverheadMs; }

    /**
     * Diagnostic rendering naming both durations, the overhead charged once, and the counters.
     *
     * @return a short human-readable description
     */
    @Override
    public String toString() {
        return "DistributedRunTotals{groups=" + groupCount + ", serialMs=" + serialDurationMs
                + ", wallClockMs=" + wallClockMs + ", fixedOverheadMs=" + fixedOverheadMs
                + ", suitesRan=" + suitesRan + ", suitesFailed=" + suitesFailed + "}";
    }
}
