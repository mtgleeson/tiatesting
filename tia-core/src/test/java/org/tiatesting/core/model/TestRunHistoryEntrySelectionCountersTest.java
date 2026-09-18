package org.tiatesting.core.model;

import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for the five selection-counter fields on {@link TestRunHistoryEntry}: that
 * {@link TestRunHistoryEntry#create} copies them across from a supplied
 * {@link TestRunSelectionDetails}, and that a null details argument leaves every counter null
 * rather than defaulting to zero (the caller cannot tell "not recorded" from "recorded as zero"
 * otherwise).
 */
public class TestRunHistoryEntrySelectionCountersTest {

    @Test
    public void createCopiesCountersFromSelectionDetails() {
        // given
        TestRunSelectionDetails details = new TestRunSelectionDetails(
                Collections.emptyList(), 1, 2, 3, 4, 5);

        // when
        TestRunHistoryEntry entry = TestRunHistoryEntry.create("main", "abc", 1000L,
                10, 20, 0, 5000L, true, 0L, 0, RunOrigin.of("local", "host"), details);

        // then
        assertEquals(Integer.valueOf(1), entry.getNumModifiedTestFiles());
        assertEquals(Integer.valueOf(2), entry.getNumNewTestFiles());
        assertEquals(Integer.valueOf(3), entry.getNumPreviouslyFailed());
        assertEquals(Integer.valueOf(4), entry.getNumUnsealedMapping());
        assertEquals(Integer.valueOf(5), entry.getNumPendingLibrary());
    }

    @Test
    public void nullSelectionDetailsLeavesCountersNull() {
        // given
        TestRunSelectionDetails details = null;

        // when
        TestRunHistoryEntry entry = TestRunHistoryEntry.create("main", "abc", 1000L,
                10, 20, 0, 5000L, true, 0L, 0, RunOrigin.of("local", "host"), details);

        // then
        assertNull(entry.getNumModifiedTestFiles());
        assertNull(entry.getNumNewTestFiles());
        assertNull(entry.getNumPreviouslyFailed());
        assertNull(entry.getNumUnsealedMapping());
        assertNull(entry.getNumPendingLibrary());
    }
}
