package org.tiatesting.core.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Verifies the deterministic id derivation on {@link TestRunHistoryEntry}. Two persists of the
 * same logical run must produce the same id so the {@code tia_test_run_history} MERGE is
 * idempotent; runs that differ on any of branch / commit / timestamp must produce a different id.
 */
class TestRunHistoryEntryIdTest {

    @Test
    void sameInputsProduceSameId() {
        // given
        String branch = "feature/test-run-history";
        String commit = "abc123";
        long ts = 1_700_000_000_000L;

        // when
        TestRunHistoryEntry first = TestRunHistoryEntry.create(branch, commit, ts, 1, 2, 0, 100L, true);
        TestRunHistoryEntry second = TestRunHistoryEntry.create(branch, commit, ts, 9, 9, 9, 999L, false);

        // then — counts/duration/flag don't participate in the id; identity is by triple only.
        assertNotNull(first.getId());
        assertEquals(first.getId(), second.getId());
    }

    @Test
    void changingBranchChangesId() {
        // given
        long ts = 1_700_000_000_000L;
        TestRunHistoryEntry baseline = TestRunHistoryEntry.create("main", "abc123", ts, 1, 0, 0, 0L, true);

        // when
        TestRunHistoryEntry other = TestRunHistoryEntry.create("develop", "abc123", ts, 1, 0, 0, 0L, true);

        // then
        assertNotEquals(baseline.getId(), other.getId());
    }

    @Test
    void changingCommitChangesId() {
        // given
        long ts = 1_700_000_000_000L;
        TestRunHistoryEntry baseline = TestRunHistoryEntry.create("main", "abc123", ts, 1, 0, 0, 0L, true);

        // when
        TestRunHistoryEntry other = TestRunHistoryEntry.create("main", "def456", ts, 1, 0, 0, 0L, true);

        // then
        assertNotEquals(baseline.getId(), other.getId());
    }

    @Test
    void changingTimestampChangesId() {
        // given
        TestRunHistoryEntry baseline = TestRunHistoryEntry.create("main", "abc123", 1L, 1, 0, 0, 0L, true);

        // when
        TestRunHistoryEntry other = TestRunHistoryEntry.create("main", "abc123", 2L, 1, 0, 0, 0L, true);

        // then
        assertNotEquals(baseline.getId(), other.getId());
    }

    @Test
    void nullBranchAndCommitProduceStableId() {
        // given a defensive nulls case — should still produce a non-null id
        long ts = 42L;

        // when
        TestRunHistoryEntry first = TestRunHistoryEntry.create(null, null, ts, 0, 0, 0, 0L, false);
        TestRunHistoryEntry second = TestRunHistoryEntry.create(null, null, ts, 0, 0, 0, 0L, false);

        // then
        assertNotNull(first.getId());
        assertEquals(first.getId(), second.getId());
    }
}
