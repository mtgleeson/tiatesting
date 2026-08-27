package org.tiatesting.core.report;

import org.tiatesting.core.model.TestRunHistoryEntry;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public class ReportUtils {

    /**
     * Build a user-friendly string showing a duration in hours, minutes, seconds and ms.
     * i.e. 1h 23m 57s 687ms
     * If component of time is 0 it will not be shown.
     *
     * @param durationMs the duration represented in MS
     * @return a user-friendly string showing a duration in hours, minutes, seconds and ms.
     */
    public static String prettyDuration(long durationMs){
        return prettyDuration(durationMs, false);
    }

    /**
     * Build a user-friendly string showing a duration in hours, minutes, seconds and ms.
     * When {@code dropMsWhenAboveSecond} is {@code true}, the {@code ms} component is
     * suppressed for durations of one second or more — sub-second precision is rarely
     * useful when reading times measured in seconds or minutes.
     *
     * @param durationMs the duration represented in ms
     * @param dropMsWhenAboveSecond when {@code true}, omit the {@code ms} component for any
     *                              duration of one second or more
     * @return a user-friendly string showing the duration
     */
    public static String prettyDuration(long durationMs, boolean dropMsWhenAboveSecond){
        return formatDuration(durationMs, dropMsWhenAboveSecond && durationMs >= 1000);
    }

    /**
     * Build a user-friendly duration string, dropping the {@code ms} component only when the
     * duration is more than one minute. Useful for averages and cumulative totals where
     * sub-second precision is noise at the minute level, but still meaningful below a minute.
     *
     * @param durationMs the duration represented in ms
     * @return a user-friendly duration string with {@code ms} suppressed above one minute
     */
    public static String prettyDurationDropMsAboveMinute(long durationMs){
        return formatDuration(durationMs, durationMs > 60_000);
    }

    /**
     * Render the duration, optionally suppressing the {@code ms} component. The boolean is the
     * already-resolved decision (callers apply their own threshold), keeping this method a pure
     * formatter.
     *
     * @param durationMs the duration in ms
     * @param dropMs whether to suppress the {@code ms} component
     * @return the formatted duration string
     */
    private static String formatDuration(long durationMs, boolean dropMs){
        Duration avgDuration = Duration.ofMillis(durationMs);

        long hours = avgDuration.toHours();
        long minutes = avgDuration.toMinutes() > 59 ? avgDuration.toMinutes() % 60 : avgDuration.toMinutes();
        long seconds = avgDuration.getSeconds() > 59 ? avgDuration.getSeconds() % 60 : avgDuration.getSeconds();
        long ms = avgDuration.toMillis() > 999 ? avgDuration.toMillis() % 1000 : avgDuration.toMillis();

        if (dropMs){
            ms = 0;
        }

        StringBuilder text = new StringBuilder();
        if (hours > 0){
            text.append(hours + "h");
        }
        if (minutes > 0){
            text.append((text.length() > 0 ? " " : "") + minutes + "m");
        }
        if (seconds > 0){
            text.append((text.length() > 0 ? " " : "") + seconds + "s");
        }
        if (ms > 0){
            text.append((text.length() > 0 ? " " : "") + ms + "ms");
        }

        return text.toString();
    }

    /**
     * The percentage of {@code part} relative to {@code total}, rounded to the nearest whole
     * percent. A {@code total} of {@code 0} yields {@code 0} rather than dividing by zero.
     *
     * @param part the part value
     * @param total the total value
     * @return {@code round(part / total * 100)}, or {@code 0} when {@code total} is {@code 0}
     */
    public static long percentOfTotal(long part, long total){
        if (total == 0){
            return 0;
        }
        return Math.round((double) part / total * 100);
    }

    /**
     * Format the average-run-time value for the summary reports: the duration with its {@code ms}
     * component dropped above a minute, followed by its share of the all-tests-run baseline in
     * brackets (e.g. {@code "1m 30s (50%)"}). The percentage is omitted when no all-tests baseline
     * has been recorded yet, since there is nothing to compare against.
     *
     * @param avgRunTimeMs the average selected-run time (ms)
     * @param allTestsRunTimeMs the all-tests-run baseline (ms); {@code 0} when none recorded
     * @return the formatted average-run-time string, with a percentage bracket when a baseline exists
     */
    public static String formatAverageRunTime(long avgRunTimeMs, long allTestsRunTimeMs){
        String duration = prettyDurationDropMsAboveMinute(avgRunTimeMs);
        if (allTestsRunTimeMs > 0){
            return duration + " (" + percentOfTotal(avgRunTimeMs, allTestsRunTimeMs) + "%)";
        }
        return duration;
    }

    /**
     * Compute the time Tia saved on a single run versus running the full suite: the full-suite
     * baseline minus the run's actual duration, clamped at zero. An all-tests run saved nothing,
     * and with no baseline yet there is nothing to compare against, so both yield {@code 0}.
     *
     * <p>This is computed once at persist time and frozen onto the history row (the baseline is a
     * rolling average that changes over time, so it can't be re-derived later).
     *
     * @param allTestsRunTimeMs the full-suite baseline current at the time of the run (ms)
     * @param durationMs the run's actual wall-clock duration (ms)
     * @param allTestsRun {@code true} when this run executed the full suite (saved nothing)
     * @return the time saved on this run (ms), never negative
     */
    public static long runSavingsMs(long allTestsRunTimeMs, long durationMs, boolean allTestsRun){
        if (allTestsRun || allTestsRunTimeMs <= 0){
            return 0L;
        }
        return Math.max(0L, allTestsRunTimeMs - durationMs);
    }

    /**
     * Sum the per-run savings frozen on the history rows. Each row's {@code time_savings} was
     * computed against the all-tests baseline current at the time of that run, so summing the
     * stored values is accurate even though the baseline moves over time.
     *
     * @param history the recorded test-run history rows
     * @return the total time saved across all runs, in ms
     */
    public static long totalSavingsMs(List<TestRunHistoryEntry> history){
        if (history == null){
            return 0L;
        }
        long total = 0L;
        for (TestRunHistoryEntry entry : history){
            total += entry.getTimeSavingsMs();
        }
        return total;
    }

    /**
     * The average wall clock of the distributed builds in the history - each one's slowest group,
     * which is what that build actually waited for.
     *
     * <p>Derived from the history rather than read from {@link org.tiatesting.core.model.TestStats},
     * which has no such counter and deliberately so: {@code avgRunTime} is the <b>serial
     * equivalent</b> in both modes, because that is the figure savings are computed from and the
     * one that keeps a project's stats comparable across the build where distributed mode was
     * switched on - see {@code DistributedRunTotals}. The wall clock is recorded per run, on the
     * history row, so an average of it belongs here.
     *
     * <p>Averages a different population from {@code avgRunTime}, which covers every run: only
     * distributed builds have a wall clock at all. Callers reporting the two side by side should
     * say how many runs this one covers - {@link #distributedRunCount(List)} - or the pair reads as
     * though the same builds got faster.
     *
     * @param history the run history to scan; may be null or empty
     * @return the mean wall clock in ms across the rows carrying one, or {@code 0} when the history
     *         holds no distributed build
     */
    public static long averageDistributedWallClockMs(List<TestRunHistoryEntry> history){
        if (history == null){
            return 0L;
        }
        long total = 0L;
        int count = 0;
        for (TestRunHistoryEntry entry : history){
            if (entry.getWallClockMs() != null){
                total += entry.getWallClockMs().longValue();
                count++;
            }
        }
        return count == 0 ? 0L : total / count;
    }

    /**
     * How many runs in the history were distributed builds, counted by the same test {@link
     * #averageDistributedWallClockMs(List)} averages over - the presence of a wall clock - so the
     * count and the average can never describe different sets of rows.
     *
     * @param history the run history to scan; may be null or empty
     * @return the number of rows carrying a wall clock, or {@code 0} when there are none
     */
    public static int distributedRunCount(List<TestRunHistoryEntry> history){
        if (history == null){
            return 0;
        }
        int count = 0;
        for (TestRunHistoryEntry entry : history){
            if (entry.getWallClockMs() != null){
                count++;
            }
        }
        return count;
    }

    /**
     * Render the pair of average-run-time lines for a summary report, so the console, plain-text
     * and HTML summaries cannot drift on the wording or on when the second line appears.
     *
     * <p>With no distributed build in the history the output is the single {@code Average run time}
     * line those reports have always printed, unchanged. With one or more, that line gains a
     * {@code (serial equivalent)} qualifier and a second line reports the average wall clock and
     * how many runs it covers - both are needed together, since the qualifier without the second
     * line explains nothing, and the second line without the qualifier leaves two unlabelled
     * averages of different things next to each other.
     *
     * @param avgRunTimeMs the stored average run time in ms, which is the serial equivalent in both
     *                     modes
     * @param allTestsRunTimeMs the full-suite baseline in ms, used for both lines' percentages so
     *                          the two are directly comparable; percentages are omitted when it is
     *                          not yet recorded
     * <p>Returned as a list rather than a joined string because the three summary reports emit lines
     * differently - two of them join with a line separator, the HTML one wraps each in its own
     * element - and joining here would force the HTML report to split the result back apart or to
     * duplicate the wording.
     *
     * @param history the run history the distributed average is derived from; may be null
     * @return one line, or two when the history holds at least one distributed build
     */
    public static List<String> averageRunTimeLines(long avgRunTimeMs, long allTestsRunTimeMs,
                                                    List<TestRunHistoryEntry> history){
        int distributedRuns = distributedRunCount(history);
        List<String> lines = new ArrayList<>(2);
        lines.add("Average run time" + (distributedRuns > 0 ? " (serial equivalent)" : "")
                + ": " + formatAverageRunTime(avgRunTimeMs, allTestsRunTimeMs));
        if (distributedRuns > 0){
            lines.add("Average distributed run time: "
                    + formatAverageRunTime(averageDistributedWallClockMs(history), allTestsRunTimeMs)
                    + " over " + distributedRuns + " distributed run(s)");
        }
        return lines;
    }
}
