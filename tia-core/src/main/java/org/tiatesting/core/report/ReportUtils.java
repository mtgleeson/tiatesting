package org.tiatesting.core.report;

import java.time.Duration;

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
        Duration avgDuration = Duration.ofMillis(durationMs);

        long hours = avgDuration.toHours();
        long minutes = avgDuration.toMinutes() > 59 ? avgDuration.toMinutes() % 60 : avgDuration.toMinutes();
        long seconds = avgDuration.getSeconds() > 59 ? avgDuration.getSeconds() % 60 : avgDuration.getSeconds();
        long ms = avgDuration.toMillis() > 999 ? avgDuration.toMillis() % 1000 : avgDuration.toMillis();

        if (dropMsWhenAboveSecond && durationMs >= 1000){
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
}
