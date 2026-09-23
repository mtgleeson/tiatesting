package org.tiatesting.core.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.tiatesting.core.model.RunOrigin;

/**
 * Verifies {@link TestRunHistoryEntry} carries the per-run savings figures
 * ({@code timeSavingsMs} and {@code savingsPercent}) frozen at persist time, through both the
 * {@link TestRunHistoryEntry#create} factory and the full constructor.
 */
class TestRunHistoryEntrySavingsTest {

    /**
     * The savings figures supplied to {@code create} are exposed by the getters.
     */
    @Test
    void create_carriesSavingsFigures(){
        // given / when
        TestRunHistoryEntry entry = TestRunHistoryEntry.create(
                "main", "abc", 1000L, 3, 2, 0, 1000L, true, 4000L, 80, RunOrigin.of(RunOrigin.SOURCE_LOCAL, null), null);

        // then
        assertEquals(4000L, entry.getTimeSavingsMs());
        assertEquals(80, entry.getSavingsPercent());
    }

    /**
     * The full constructor (read path) round-trips the savings figures.
     */
    @Test
    void fullConstructor_carriesSavingsFigures(){
        // given / when
        TestRunHistoryEntry entry = new TestRunHistoryEntry(
                "id", 1000L, "main", "abc", 3, 2, 0, 1000L, true, 4000L, 80, 4000L, 80, null, null, null, null, RunOrigin.of(RunOrigin.SOURCE_LOCAL, null),
                null, null, null, null, null);

        // then
        assertEquals(4000L, entry.getTimeSavingsMs());
        assertEquals(80, entry.getSavingsPercent());
    }

    /**
     * A single-host run's wall clock is its duration: one machine ran the whole selection.
     */
    @Test
    void runWallClock_isTheDurationForASingleHostRun(){
        // given
        TestRunHistoryEntry entry = TestRunHistoryEntry.create(
                "main", "abc", 1000L, 3, 2, 0, 12_000L, true, 4000L, 80, RunOrigin.of(RunOrigin.SOURCE_LOCAL, null), null);

        // when
        long wallClockMs = entry.getRunWallClockMs();

        // then
        assertEquals(12_000L, wallClockMs);
    }

    /**
     * A distributed run's wall clock is its slowest group, not its serial duration.
     */
    @Test
    void runWallClock_isTheSlowestGroupForADistributedRun(){
        // given
        TestRunHistoryEntry entry = TestRunHistoryEntry.createForDistributedRun("main", "abc",
                "run-1", 1000L, 3, 2, 0, 20_000L, true, 0L, 0, 0L, 0, 8_000L, 3, 3,
                RunOrigin.of(RunOrigin.SOURCE_CI, null), null);

        // when
        long wallClockMs = entry.getRunWallClockMs();

        // then
        assertEquals(8_000L, wallClockMs);
    }
}
