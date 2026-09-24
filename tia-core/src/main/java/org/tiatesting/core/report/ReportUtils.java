package org.tiatesting.core.report;

import org.tiatesting.core.model.TestRunHistoryEntry;

import java.time.Duration;
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
     * @return the formatted duration string; {@code 0ms} when no component is non-zero
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

        // A zero duration has no component to print, and an empty cell reads as missing data
        // rather than as a measured zero - a distributed build with no groups records exactly 0.
        return text.length() == 0 ? "0ms" : text.toString();
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
     * Compute the time Tia saved on a single run versus running the full suite: the full-suite
     * baseline minus the run's actual duration, clamped at zero. An all-tests run saved nothing,
     * and with no baseline yet there is nothing to compare against, so both yield {@code 0}. A run
     * that executed none of the suites it was expected to is handed {@code savedNothing} for the
     * same reason: it finished early because it ran nothing, not because Tia deselected anything.
     *
     * <p>This is computed once at persist time and frozen onto the history row (the baseline is a
     * rolling average that changes over time, so it can't be re-derived later).
     *
     * @param allTestsRunTimeMs the full-suite baseline current at the time of the run (ms)
     * @param durationMs the run's actual wall-clock duration (ms)
     * @param savedNothing {@code true} when this run cannot have saved anything - it executed the
     *                     full suite, or it executed none of the suites it was expected to, which is
     *                     a broken build finishing early rather than a Tia win
     * @return the time saved on this run (ms), never negative
     */
    public static long runSavingsMs(long allTestsRunTimeMs, long durationMs, boolean savedNothing){
        if (savedNothing || allTestsRunTimeMs <= 0){
            return 0L;
        }
        return Math.max(0L, allTestsRunTimeMs - durationMs);
    }

    /**
     * The full-suite baseline as wall-clock time: the serial all-tests run time spread evenly across
     * every group a distributed build had available. This is roughly how long running every test
     * would have taken end to end on that pool of machines, and it is what a build's wall clock is
     * compared against to give its wall-clock savings.
     *
     * @param allTestsRunTimeMs the serial full-suite baseline current at the time of the run (ms)
     * @param groupsAvailable the number of groups the build had available; at least 1
     * @return the baseline spread across {@code groupsAvailable} machines (ms)
     */
    public static long wallClockAllTestsRunTimeMs(long allTestsRunTimeMs, int groupsAvailable){
        return allTestsRunTimeMs / groupsAvailable;
    }

    /**
     * Format one run's savings for the history detail views as the duration followed by its share
     * of the baseline, e.g. {@code "8m (80%)"}, or {@code "-"} when the run saved nothing.
     *
     * @param savingsMs the savings frozen on the history row (ms)
     * @param savingsPercent {@code savingsMs} as a percentage of the baseline it was measured against
     * @return the formatted savings, or {@code "-"} when {@code savingsMs} is not positive
     */
    public static String savingsText(long savingsMs, int savingsPercent){
        if (savingsMs <= 0){
            return "-";
        }
        return prettyDuration(savingsMs, true) + " (" + savingsPercent + "%)";
    }

    /**
     * Sum the per-run wall-clock savings frozen on the history rows - the end-to-end time Tia saved
     * across every recorded run. Each row's {@code wall_clock_savings} was computed against the
     * all-tests baseline current at the time of that run, so summing the stored values is accurate
     * even though the baseline moves over time.
     *
     * @param history the recorded test-run history rows
     * @return the total wall-clock time saved across all runs, in ms
     */
    public static long totalWallClockSavingsMs(List<TestRunHistoryEntry> history){
        if (history == null){
            return 0L;
        }
        long total = 0L;
        for (TestRunHistoryEntry entry : history){
            total += entry.getWallClockSavingsMs();
        }
        return total;
    }

    /**
     * The number of groups the most recent all-tests run in the history was split across: its
     * group count for a distributed build, 1 for a single-host run. Only runs that owned the
     * mapping count, since they are the only ones that move the full-suite baseline; a local
     * all-tests run on a laptop says nothing about how the baseline was measured.
     *
     * @param history the run history to scan; may be null or empty
     * @return the most recent all-tests run's group count, or 1 when the history holds none
     */
    public static int lastAllTestsRunGroupCount(List<TestRunHistoryEntry> history){
        if (history == null){
            return 1;
        }
        TestRunHistoryEntry latest = null;
        for (TestRunHistoryEntry entry : history){
            boolean allTestsRun = entry.isUpdatedDbMapping() && entry.getNumSuitesIgnored() == 0
                    && entry.getNumSuitesRan() > 0;
            if (allTestsRun && (latest == null
                    || entry.getRunTimestampMs() > latest.getRunTimestampMs())){
                latest = entry;
            }
        }
        return latest == null || latest.getGroupCount() == null ? 1 : latest.getGroupCount();
    }
}
