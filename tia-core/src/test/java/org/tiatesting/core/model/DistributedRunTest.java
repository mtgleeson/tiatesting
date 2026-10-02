package org.tiatesting.core.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lock the value semantics of {@link DistributedRun}. The persistence tests assert a read-back run
 * equal to the one written, so a field left out of {@code equals} would let that field be lost on
 * the round trip without any test noticing.
 */
class DistributedRunTest {

    /**
     * Build an open run carrying the given run source, otherwise identical across calls.
     *
     * @param runSource the run source to record, or null for none
     * @return a freshly planned run
     */
    private static DistributedRun runWithSource(String runSource) {
        return DistributedRun.open("run-1", "main", "abc123", 2, 2, null, 300L, 5L, SelectionMode.SELECTIVE,
                runSource);
    }

    /**
     * Verify that two runs differing only in their run source are not equal, so a round trip that
     * dropped or altered the planned source fails the persistence tests' equality assertions.
     */
    @Test
    void shouldNotBeEqualWhenOnlyTheRunSourceDiffers() {
        // given
        DistributedRun ciRun = runWithSource("CI");
        DistributedRun unrecordedRun = runWithSource(null);

        // when
        boolean equal = ciRun.equals(unrecordedRun);

        // then
        assertFalse(equal, "the run source must take part in equality");
    }

    /**
     * Verify that two runs with the same run source are equal and hash alike, keeping {@code
     * hashCode} consistent with {@code equals} now that the source is part of both.
     */
    @Test
    void shouldBeEqualAndHashAlikeWhenTheRunSourceMatches() {
        // given
        DistributedRun first = runWithSource("CI");
        DistributedRun second = runWithSource("CI");

        // when
        boolean equal = first.equals(second);

        // then
        assertTrue(equal);
        assertEquals(first.hashCode(), second.hashCode());
        assertEquals("CI", first.getRunSource());
    }
}
