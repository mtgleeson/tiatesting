package org.tiatesting.core.model;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

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
        TestRunHistoryEntry first = TestRunHistoryEntry.create(branch, commit, ts, 1, 2, 0, 100L, true, 0L, 0);
        TestRunHistoryEntry second = TestRunHistoryEntry.create(branch, commit, ts, 9, 9, 9, 999L, false, 0L, 0);

        // then - counts/duration/flag don't participate in the id; identity is by triple only.
        assertNotNull(first.getId());
        assertEquals(first.getId(), second.getId());
    }

    @Test
    void changingBranchChangesId() {
        // given
        long ts = 1_700_000_000_000L;
        TestRunHistoryEntry baseline = TestRunHistoryEntry.create("main", "abc123", ts, 1, 0, 0, 0L, true, 0L, 0);

        // when
        TestRunHistoryEntry other = TestRunHistoryEntry.create("develop", "abc123", ts, 1, 0, 0, 0L, true, 0L, 0);

        // then
        assertNotEquals(baseline.getId(), other.getId());
    }

    @Test
    void changingCommitChangesId() {
        // given
        long ts = 1_700_000_000_000L;
        TestRunHistoryEntry baseline = TestRunHistoryEntry.create("main", "abc123", ts, 1, 0, 0, 0L, true, 0L, 0);

        // when
        TestRunHistoryEntry other = TestRunHistoryEntry.create("main", "def456", ts, 1, 0, 0, 0L, true, 0L, 0);

        // then
        assertNotEquals(baseline.getId(), other.getId());
    }

    @Test
    void changingTimestampChangesId() {
        // given
        TestRunHistoryEntry baseline = TestRunHistoryEntry.create("main", "abc123", 1L, 1, 0, 0, 0L, true, 0L, 0);

        // when
        TestRunHistoryEntry other = TestRunHistoryEntry.create("main", "abc123", 2L, 1, 0, 0, 0L, true, 0L, 0);

        // then
        assertNotEquals(baseline.getId(), other.getId());
    }

    /**
     * A single-host id is still the UUID of exactly {@code branch|commit|timestamp}. Pinned against
     * the raw seed so a refactor cannot quietly change the id of a row every existing history table
     * already holds, which would turn a re-persist of the same run into a second row.
     */
    @Test
    void aSingleHostIdIsStillTheUuidOfBranchPipeCommitPipeTimestamp() {
        // given
        String seed = "main|abc123|1700000000000";

        // when
        TestRunHistoryEntry entry = TestRunHistoryEntry.create("main", "abc123", 1_700_000_000_000L,
                1, 0, 0, 0L, true, 0L, 0);

        // then
        assertEquals(UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)).toString(),
                entry.getId(), "the single-host id derivation must not have changed");
    }

    /**
     * A distributed build's row is identified by its run as well, so two builds planned in the same
     * millisecond against the same branch and commit cannot collide onto one row.
     */
    @Test
    void aDistributedBuildsIdAlsoDependsOnItsRunId() {
        // given
        long ts = 1_700_000_000_000L;
        TestRunHistoryEntry baseline = TestRunHistoryEntry.createForDistributedRun("main", "abc123",
                "run-1", ts, 5, 0, 0, 1000L, true, 0L, 0, 500L, 2);

        // when
        TestRunHistoryEntry other = TestRunHistoryEntry.createForDistributedRun("main", "abc123",
                "run-2", ts, 5, 0, 0, 1000L, true, 0L, 0, 500L, 2);

        // then
        assertNotEquals(baseline.getId(), other.getId(),
                "two runs planned in the same millisecond must not share a row");
        assertNotEquals(TestRunHistoryEntry.create("main", "abc123", ts, 5, 0, 0, 1000L, true, 0L, 0)
                        .getId(), baseline.getId(),
                "nor may a distributed build collide with a single-host run's row");
    }

    @Test
    void nullBranchAndCommitProduceStableId() {
        // given a defensive nulls case - should still produce a non-null id
        long ts = 42L;

        // when
        TestRunHistoryEntry first = TestRunHistoryEntry.create(null, null, ts, 0, 0, 0, 0L, false, 0L, 0);
        TestRunHistoryEntry second = TestRunHistoryEntry.create(null, null, ts, 0, 0, 0, 0L, false, 0L, 0);

        // then
        assertNotNull(first.getId());
        assertEquals(first.getId(), second.getId());
    }
}
