package org.tiatesting.core.model;

import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable record of a single Tia test-run, persisted to the {@code tia_test_run_history} table
 * so the HTML report can show a history of executions for the current branch.
 *
 * <p>The {@code id} is deterministic — derived from {@code branch}, {@code commit}, and
 * {@code runTimestampMs} via {@link UUID#nameUUIDFromBytes(byte[])} — so the same logical run
 * always maps to the same id and re-inserts of the same row are idempotent.
 *
 * <p>{@code runTimestampMs} is stored as UTC epoch milliseconds (the value returned by
 * {@link System#currentTimeMillis()}, which is inherently UTC). The HTML report renders it in
 * the viewer's local time on the client.
 */
public final class TestRunHistoryEntry implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String id;
    private final long runTimestampMs;
    private final String branch;
    private final String commit;
    private final int numSuitesRan;
    private final int numSuitesIgnored;
    private final int numSuitesFailed;
    private final long durationMs;
    private final boolean updatedDbMapping;

    /**
     * Full constructor including the (caller-supplied) id. Used by the read path so the id
     * stored on disk is round-tripped exactly. New entries should normally be created via
     * {@link #create(String, String, long, int, int, int, long, boolean)} which derives the id.
     *
     * @param id deterministic entry id
     * @param runTimestampMs UTC epoch millis when the run started
     * @param branch VCS branch the run targeted
     * @param commit VCS HEAD commit / changelist the run targeted
     * @param numSuitesRan number of test suites that actually executed
     * @param numSuitesIgnored number of test suites the test runner saw but did not execute
     * @param numSuitesFailed number of test suites with at least one failed test
     * @param durationMs total wall-clock duration of the test run, in ms
     * @param updatedDbMapping whether this run persisted updates to the Tia mapping DB
     */
    public TestRunHistoryEntry(String id, long runTimestampMs, String branch, String commit,
                               int numSuitesRan, int numSuitesIgnored, int numSuitesFailed,
                               long durationMs, boolean updatedDbMapping) {
        this.id = id;
        this.runTimestampMs = runTimestampMs;
        this.branch = branch;
        this.commit = commit;
        this.numSuitesRan = numSuitesRan;
        this.numSuitesIgnored = numSuitesIgnored;
        this.numSuitesFailed = numSuitesFailed;
        this.durationMs = durationMs;
        this.updatedDbMapping = updatedDbMapping;
    }

    /**
     * Factory that derives the entry's id from {@code branch|commit|runTimestampMs} so two
     * persists of the same logical run produce the same row.
     *
     * @return a new entry with a deterministic id
     */
    public static TestRunHistoryEntry create(String branch, String commit, long runTimestampMs,
                                             int numSuitesRan, int numSuitesIgnored,
                                             int numSuitesFailed, long durationMs,
                                             boolean updatedDbMapping) {
        String id = deriveId(branch, commit, runTimestampMs);
        return new TestRunHistoryEntry(id, runTimestampMs, branch, commit, numSuitesRan,
                numSuitesIgnored, numSuitesFailed, durationMs, updatedDbMapping);
    }

    /**
     * Compute the deterministic id for a {@code (branch, commit, runTimestampMs)} triple.
     * Exposed package-private for unit tests; otherwise reach it via {@link #create}.
     *
     * @return a UUID v3 (MD5-based) string derived from the triple
     */
    static String deriveId(String branch, String commit, long runTimestampMs) {
        String seed = (branch == null ? "" : branch) + "|"
                + (commit == null ? "" : commit) + "|"
                + runTimestampMs;
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)).toString();
    }

    /** @return the deterministic entry id (UUID v3 derived from branch|commit|timestamp) */
    public String getId() { return id; }

    /** @return UTC epoch millis when the run started */
    public long getRunTimestampMs() { return runTimestampMs; }

    /** @return the VCS branch the run targeted */
    public String getBranch() { return branch; }

    /** @return the VCS commit / changelist the run was against */
    public String getCommit() { return commit; }

    /** @return the number of suites that actually executed */
    public int getNumSuitesRan() { return numSuitesRan; }

    /** @return the number of suites the runner saw but did not execute */
    public int getNumSuitesIgnored() { return numSuitesIgnored; }

    /** @return the number of suites that had at least one failed test */
    public int getNumSuitesFailed() { return numSuitesFailed; }

    /** @return total wall-clock duration of the run, ms */
    public long getDurationMs() { return durationMs; }

    /** @return whether this run persisted updates to the Tia mapping DB */
    public boolean isUpdatedDbMapping() { return updatedDbMapping; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TestRunHistoryEntry)) return false;
        TestRunHistoryEntry that = (TestRunHistoryEntry) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() { return Objects.hash(id); }
}
