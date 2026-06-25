package org.tiatesting.core.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
                "main", "abc", 1000L, 3, 2, 0, 1000L, true, 4000L, 80);

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
                "id", 1000L, "main", "abc", 3, 2, 0, 1000L, true, 4000L, 80);

        // then
        assertEquals(4000L, entry.getTimeSavingsMs());
        assertEquals(80, entry.getSavingsPercent());
    }
}
