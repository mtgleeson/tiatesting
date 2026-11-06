package org.tiatesting.core.model;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import org.tiatesting.core.model.RunOrigin;

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
        TestRunHistoryEntry first = TestRunHistoryEntry.create(branch, commit, String.valueOf(ts), false, ts, 1, 2, 0, 100L, 100L, true, 0L, 0, RunOrigin.of(RunOrigin.SOURCE_LOCAL, null), null);
        TestRunHistoryEntry second = TestRunHistoryEntry.create(branch, commit, String.valueOf(ts), false, ts, 9, 9, 9, 999L, 999L, false, 0L, 0, RunOrigin.of(RunOrigin.SOURCE_LOCAL, null), null);

        // then - counts/duration/flag don't participate in the id; identity is by triple only.
        assertNotNull(first.getId());
        assertEquals(first.getId(), second.getId());
    }

    @Test
    void changingBranchChangesId() {
        // given
        long ts = 1_700_000_000_000L;
        TestRunHistoryEntry baseline = TestRunHistoryEntry.create("main", "abc123", String.valueOf(ts), false, ts, 1, 0, 0, 0L, 0L, true, 0L, 0, RunOrigin.of(RunOrigin.SOURCE_LOCAL, null), null);

        // when
        TestRunHistoryEntry other = TestRunHistoryEntry.create("develop", "abc123", String.valueOf(ts), false, ts, 1, 0, 0, 0L, 0L, true, 0L, 0, RunOrigin.of(RunOrigin.SOURCE_LOCAL, null), null);

        // then
        assertNotEquals(baseline.getId(), other.getId());
    }

    @Test
    void changingCommitChangesId() {
        // given
        long ts = 1_700_000_000_000L;
        TestRunHistoryEntry baseline = TestRunHistoryEntry.create("main", "abc123", String.valueOf(ts), false, ts, 1, 0, 0, 0L, 0L, true, 0L, 0, RunOrigin.of(RunOrigin.SOURCE_LOCAL, null), null);

        // when
        TestRunHistoryEntry other = TestRunHistoryEntry.create("main", "def456", String.valueOf(ts), false, ts, 1, 0, 0, 0L, 0L, true, 0L, 0, RunOrigin.of(RunOrigin.SOURCE_LOCAL, null), null);

        // then
        assertNotEquals(baseline.getId(), other.getId());
    }

    @Test
    void changingTheRunChangesId() {
        // given
        TestRunHistoryEntry baseline = TestRunHistoryEntry.create("main", "abc123", "run-1", false, 1L, 1, 0, 0, 0L, 0L, true, 0L, 0, RunOrigin.of(RunOrigin.SOURCE_LOCAL, null), null);

        // when
        TestRunHistoryEntry other = TestRunHistoryEntry.create("main", "abc123", "run-2", false, 1L, 1, 0, 0, 0L, 0L, true, 0L, 0, RunOrigin.of(RunOrigin.SOURCE_LOCAL, null), null);

        // then
        assertNotEquals(baseline.getId(), other.getId());
    }

    /**
     * Every test JVM of a run builds its rows from the same run, whatever its own plan start, so
     * they upsert one first-attempt row and one rerun row rather than one row each.
     */
    @Test
    void everyJvmOfARunWritesTheSameTwoRows() {
        // given two JVMs' rows for one run, built at different plan start times
        TestRunHistoryEntry firstJvm = TestRunHistoryEntry.create("main", "abc123", "run-1", false, 1L, 1, 0, 0, 0L, 0L, true, 0L, 0, RunOrigin.of(RunOrigin.SOURCE_LOCAL, null), null);
        TestRunHistoryEntry secondJvm = TestRunHistoryEntry.create("main", "abc123", "run-1", false, 2L, 1, 0, 0, 0L, 0L, true, 0L, 0, RunOrigin.of(RunOrigin.SOURCE_LOCAL, null), null);

        // when
        TestRunHistoryEntry rerun = TestRunHistoryEntry.create("main", "abc123", "run-1", true, 2L, 1, 0, 0, 0L, 0L, true, 0L, 0, RunOrigin.of(RunOrigin.SOURCE_LOCAL, null), null);

        // then
        assertEquals(firstJvm.getId(), secondJvm.getId());
        assertNotEquals(firstJvm.getId(), rerun.getId());
    }

    /**
     * A first-attempt id is the UUID of exactly {@code branch|commit|runId}, and a rerun row's
     * appends {@code |rerun}. Pinned against the raw seeds so a refactor cannot quietly change the id
     * every JVM of a run relies on to land on the same row.
     */
    @Test
    void theIdIsTheUuidOfBranchPipeCommitPipeRunId() {
        // given
        String firstAttemptSeed = "main|abc123|run-1";
        String rerunSeed = "main|abc123|run-1|rerun";

        // when
        String firstAttemptId = TestRunHistoryEntry.idFor("main", "abc123", "run-1", false);
        String rerunId = TestRunHistoryEntry.idFor("main", "abc123", "run-1", true);

        // then
        assertEquals(UUID.nameUUIDFromBytes(firstAttemptSeed.getBytes(StandardCharsets.UTF_8)).toString(), firstAttemptId);
        assertEquals(UUID.nameUUIDFromBytes(rerunSeed.getBytes(StandardCharsets.UTF_8)).toString(), rerunId);
    }

    /**
     * A distributed build's row is identified by its run too, so two builds planned in the same
     * millisecond against the same branch and commit cannot collide onto one row.
     */
    @Test
    void aDistributedBuildsIdAlsoDependsOnItsRunId() {
        // given
        long ts = 1_700_000_000_000L;
        TestRunHistoryEntry baseline = TestRunHistoryEntry.createForDistributedRun("main", "abc123",
                "run-1", ts, 5, 0, 0, 1000L, true, 0L, 0, 0L, 0, 500L, 2, 2, RunOrigin.of(RunOrigin.SOURCE_LOCAL, null), null, false);

        // when
        TestRunHistoryEntry other = TestRunHistoryEntry.createForDistributedRun("main", "abc123",
                "run-2", ts, 5, 0, 0, 1000L, true, 0L, 0, 0L, 0, 500L, 2, 2, RunOrigin.of(RunOrigin.SOURCE_LOCAL, null), null, false);

        // then
        assertNotEquals(baseline.getId(), other.getId(),
                "two runs planned in the same millisecond must not share a row");
        assertNotEquals(TestRunHistoryEntry.create("main", "abc123", "run-3", false, ts, 5, 0, 0, 1000L, 1000L, true, 0L, 0, RunOrigin.of(RunOrigin.SOURCE_LOCAL, null), null)
                        .getId(), baseline.getId(),
                "nor may a distributed build collide with another run's row");
    }

    @Test
    void nullBranchAndCommitProduceStableId() {
        // given a defensive nulls case - should still produce a non-null id
        long ts = 42L;

        // when
        TestRunHistoryEntry first = TestRunHistoryEntry.create(null, null, String.valueOf(ts), false, ts, 0, 0, 0, 0L, 0L, false, 0L, 0, RunOrigin.of(RunOrigin.SOURCE_LOCAL, null), null);
        TestRunHistoryEntry second = TestRunHistoryEntry.create(null, null, String.valueOf(ts), false, ts, 0, 0, 0, 0L, 0L, false, 0L, 0, RunOrigin.of(RunOrigin.SOURCE_LOCAL, null), null);

        // then
        assertNotNull(first.getId());
        assertEquals(first.getId(), second.getId());
    }
}
