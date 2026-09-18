package org.tiatesting.core.model;

import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable record of a single Tia test-run, persisted to the {@code tia_test_run_history} table
 * so the HTML report can show a history of executions for the current branch.
 *
 * <p>The {@code id} is deterministic - derived from {@code branch}, {@code commit}, and
 * {@code runTimestampMs} via {@link UUID#nameUUIDFromBytes(byte[])} - so the same logical run
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
    private final long timeSavingsMs;
    private final int savingsPercent;
    private final String runId;
    private final Long wallClockMs;
    private final Integer groupCount;
    private final RunOrigin runOrigin;
    private final Integer numModifiedTestFiles;
    private final Integer numNewTestFiles;
    private final Integer numPreviouslyFailed;
    private final Integer numUnsealedMapping;
    private final Integer numPendingLibrary;

    /**
     * Full constructor including the (caller-supplied) id. Used by the read path so the id
     * stored on disk is round-tripped exactly. New entries should normally be created via
     * {@link #create} which derives the id.
     *
     * <p>The three parameters after {@code savingsPercent} describe a distributed build and are
     * null for a single-host run. {@code durationMs} keeps the same meaning in both modes - the
     * serial-equivalent test-execution time, which for a distributed build is the sum of every
     * group's time - so savings stay comparable across the two. See the "Stats and history"
     * material in the distributed test runs chapter of {@code WIKI.md}.
     *
     * <p>The five trailing counters are null when not recorded - a row written before this
     * feature, or a distributed row before the seal populates it - rather than defaulting to zero,
     * so callers can distinguish "not recorded" from "recorded as zero". A single-host run always
     * records the counts (zero included). See the "Run history details" chapter in
     * {@code WIKI.md}.
     *
     * @param id deterministic entry id
     * @param runTimestampMs UTC epoch millis when the run started
     * @param branch VCS branch the run targeted
     * @param commit VCS HEAD commit / changelist the run targeted
     * @param numSuitesRan number of test suites that actually executed
     * @param numSuitesIgnored number of test suites Tia chose to ignore for this run. Sourced
     *                         from the selector's {@code TestSelectorResult.testsToIgnore} via
     *                         the {@code tiaIgnoredTestSuiteCount} system property. Engine-level
     *                         skips that Tia did not cause (user {@code @Disabled}, surefire
     *                         {@code groups} filters, etc.) are deliberately excluded.
     * @param numSuitesFailed number of test suites with at least one failed test
     * @param durationMs total wall-clock duration of the test run, in ms
     * @param updatedDbMapping whether this run persisted updates to the Tia mapping DB
     * @param timeSavingsMs time Tia saved this run versus running the full suite (ms), frozen at
     *                      persist time against the all-tests baseline then current; {@code 0} for
     *                      all-tests runs or when no baseline existed
     * @param savingsPercent {@code timeSavingsMs} as a percentage of the full-suite baseline; {@code 0}
     *                       for all-tests runs or when no baseline existed
     * @param runId the distributed run this row summarises, or null for a single-host run
     * @param wallClockMs the distributed build's wall-clock test time - the slowest group's
     *                    duration - or null for a single-host run
     * @param groupCount the number of groups the distributed build was split across, or null for a
     *                   single-host run
     * @param runOrigin where the run came from and which machine executed it; never null, though
     *                  its host may be
     * @param numModifiedTestFiles count of modified test files that were selected, or null when
     *                             not recorded
     * @param numNewTestFiles count of new test files that were selected, or null when not recorded
     * @param numPreviouslyFailed count of previously-failed suites re-run, or null when not recorded
     * @param numUnsealedMapping count of suites re-run from unsealed mapping rows, or null when not
     *                           recorded
     * @param numPendingLibrary count of suites selected from pending library changes, or null when
     *                          not recorded
     */
    public TestRunHistoryEntry(String id, long runTimestampMs, String branch, String commit,
                               int numSuitesRan, int numSuitesIgnored, int numSuitesFailed,
                               long durationMs, boolean updatedDbMapping,
                               long timeSavingsMs, int savingsPercent,
                               String runId, Long wallClockMs, Integer groupCount,
                               RunOrigin runOrigin, Integer numModifiedTestFiles,
                               Integer numNewTestFiles, Integer numPreviouslyFailed,
                               Integer numUnsealedMapping, Integer numPendingLibrary) {
        this.id = id;
        this.runTimestampMs = runTimestampMs;
        this.branch = branch;
        this.commit = commit;
        this.numSuitesRan = numSuitesRan;
        this.numSuitesIgnored = numSuitesIgnored;
        this.numSuitesFailed = numSuitesFailed;
        this.durationMs = durationMs;
        this.updatedDbMapping = updatedDbMapping;
        this.timeSavingsMs = timeSavingsMs;
        this.savingsPercent = savingsPercent;
        this.runId = runId;
        this.wallClockMs = wallClockMs;
        this.groupCount = groupCount;
        this.runOrigin = Objects.requireNonNull(runOrigin, "runOrigin");
        this.numModifiedTestFiles = numModifiedTestFiles;
        this.numNewTestFiles = numNewTestFiles;
        this.numPreviouslyFailed = numPreviouslyFailed;
        this.numUnsealedMapping = numUnsealedMapping;
        this.numPendingLibrary = numPendingLibrary;
    }

    /**
     * Factory for a single-host run that derives the entry's id from
     * {@code branch|commit|runTimestampMs} so two persists of the same logical run produce the same
     * row. Leaves the three distributed fields null, which is what makes a non-distributed history
     * row indistinguishable from one written before distributed runs existed.
     *
     * @param branch            VCS branch the run targeted
     * @param commit            VCS HEAD commit / changelist the run targeted
     * @param runTimestampMs    UTC epoch millis when the run started
     * @param numSuitesRan      number of test suites that actually executed
     * @param numSuitesIgnored  number of test suites Tia chose to ignore for this run (does not
     *                          include engine-level skips Tia did not cause)
     * @param numSuitesFailed   number of test suites with at least one failed test
     * @param durationMs        total wall-clock duration of the test run, in ms
     * @param updatedDbMapping  whether this run persisted updates to the Tia mapping DB
     * @param timeSavingsMs     time Tia saved this run versus running the full suite (ms)
     * @param savingsPercent    {@code timeSavingsMs} as a percentage of the full-suite baseline
     * @param runOrigin         where the run came from and which machine executed it
     * @param selectionDetails  the per-run breakdown of what drove test selection, used to
     *                          populate the five selection-counter fields; null leaves all five
     *                          null (not recorded) rather than defaulting to zero
     * @return a new entry with a deterministic id and no distributed-run fields
     */
    public static TestRunHistoryEntry create(String branch, String commit, long runTimestampMs,
                                             int numSuitesRan, int numSuitesIgnored,
                                             int numSuitesFailed, long durationMs,
                                             boolean updatedDbMapping, long timeSavingsMs,
                                             int savingsPercent, RunOrigin runOrigin,
                                             TestRunSelectionDetails selectionDetails) {
        String id = deriveId(branch, commit, runTimestampMs);
        return new TestRunHistoryEntry(id, runTimestampMs, branch, commit, numSuitesRan,
                numSuitesIgnored, numSuitesFailed, durationMs, updatedDbMapping, timeSavingsMs,
                savingsPercent, null, null, null, runOrigin,
                counterOrNull(selectionDetails, TestRunSelectionDetails::getNumModifiedTestFiles),
                counterOrNull(selectionDetails, TestRunSelectionDetails::getNumNewTestFiles),
                counterOrNull(selectionDetails, TestRunSelectionDetails::getNumPreviouslyFailed),
                counterOrNull(selectionDetails, TestRunSelectionDetails::getNumUnsealedMapping),
                counterOrNull(selectionDetails, TestRunSelectionDetails::getNumPendingLibrary));
    }

    /**
     * Factory for the one row a distributed build writes, produced by the runner that sealed it
     * rather than by each runner. The duration is the serial-equivalent time - the sum of every
     * group's test time - so it means the same thing as a single-host row's duration and savings
     * computed from it stay comparable; the wall clock the build actually took is carried
     * separately. See the "Stats and history" material in the distributed test runs chapter of
     * {@code WIKI.md}.
     *
     * @param branch            VCS branch the build targeted
     * @param commit            VCS commit / changelist the build ran against
     * @param runId             the distributed run this row summarises
     * @param runTimestampMs    UTC epoch millis when the build's run was planned, shared by every
     *                          runner in it
     * @param numSuitesRan      number of suites the build executed across every group
     * @param numSuitesIgnored  number of tracked suites the build did not run, which is Tia's
     *                          selection decision for the build as a whole
     * @param numSuitesFailed   number of suites with at least one failed test across every group
     * @param serialDurationMs  the serial-equivalent test-execution time (ms): the sum of every
     *                          group's time
     * @param updatedDbMapping  whether the build persisted updates to the Tia mapping DB
     * @param timeSavingsMs     time Tia saved this build versus running the full suite (ms),
     *                          measured against the serial-equivalent duration
     * @param savingsPercent    {@code timeSavingsMs} as a percentage of the full-suite baseline
     * @param wallClockMs       the build's wall-clock test time (ms): its slowest group
     * @param groupCount        the number of groups the build was split across
     * @param runOrigin         where the build came from. Its host is expected to be null: the build
     *                          ran across several machines, so no single one executed it
     * @param selectionDetails  the build-level breakdown of what drove test selection, used to
     *                          populate the five selection-counter fields; null leaves all five
     *                          null (not recorded) rather than defaulting to zero
     * @return a new entry carrying the build-level figures and the three distributed fields
     */
    public static TestRunHistoryEntry createForDistributedRun(String branch, String commit,
                                                              String runId, long runTimestampMs,
                                                              int numSuitesRan, int numSuitesIgnored,
                                                              int numSuitesFailed,
                                                              long serialDurationMs,
                                                              boolean updatedDbMapping,
                                                              long timeSavingsMs, int savingsPercent,
                                                              long wallClockMs, int groupCount,
                                                              RunOrigin runOrigin,
                                                              TestRunSelectionDetails selectionDetails) {
        // The run id joins the seed so two builds planned in the same millisecond against the same
        // branch and commit - a CI system replanning a retried build - cannot collide onto one row.
        String id = uuidFrom(nullSafe(branch) + "|" + nullSafe(commit) + "|" + runTimestampMs
                + "|" + nullSafe(runId));
        return new TestRunHistoryEntry(id, runTimestampMs, branch, commit, numSuitesRan,
                numSuitesIgnored, numSuitesFailed, serialDurationMs, updatedDbMapping, timeSavingsMs,
                savingsPercent, runId, Long.valueOf(wallClockMs), Integer.valueOf(groupCount),
                runOrigin,
                counterOrNull(selectionDetails, TestRunSelectionDetails::getNumModifiedTestFiles),
                counterOrNull(selectionDetails, TestRunSelectionDetails::getNumNewTestFiles),
                counterOrNull(selectionDetails, TestRunSelectionDetails::getNumPreviouslyFailed),
                counterOrNull(selectionDetails, TestRunSelectionDetails::getNumUnsealedMapping),
                counterOrNull(selectionDetails, TestRunSelectionDetails::getNumPendingLibrary));
    }

    /**
     * Derive one selection-counter value from a {@link TestRunSelectionDetails}, applying the
     * given extractor, or return null when no details were recorded. Centralises the
     * null-safety so {@link #create} and {@link #createForDistributedRun} do not repeat a null
     * check per counter.
     *
     * @param d the selection details to read, or null when none were recorded
     * @param f the extractor to apply to {@code d} to obtain one counter's value
     * @return the extracted counter boxed as an {@code Integer}, or null when {@code d} is null
     */
    private static Integer counterOrNull(TestRunSelectionDetails d,
                                         java.util.function.ToIntFunction<TestRunSelectionDetails> f) {
        return d == null ? null : Integer.valueOf(f.applyAsInt(d));
    }

    /**
     * Compute the deterministic id for a {@code (branch, commit, runTimestampMs)} triple.
     * Exposed package-private for unit tests; otherwise reach it via {@link #create}.
     *
     * @param branch          VCS branch the run targeted
     * @param commit          VCS HEAD commit / changelist the run targeted
     * @param runTimestampMs  UTC epoch millis when the run started
     * @return a UUID v3 (MD5-based) string derived from the triple
     */
    static String deriveId(String branch, String commit, long runTimestampMs) {
        return uuidFrom(nullSafe(branch) + "|" + nullSafe(commit) + "|" + runTimestampMs);
    }

    /**
     * Hash a seed string into the UUID v3 (MD5-based) form the entry ids use, so every id in the
     * table is produced the same way whichever factory built the row.
     *
     * @param seed the seed string to hash
     * @return the derived UUID as a string
     */
    private static String uuidFrom(String seed) {
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)).toString();
    }

    /**
     * Coalesce a null seed component to the empty string, so a null branch or commit hashes
     * consistently rather than as the literal {@code "null"}.
     *
     * @param value the possibly-null seed component
     * @return {@code value}, or {@code ""} when it is null
     */
    private static String nullSafe(String value) {
        return value == null ? "" : value;
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

    /**
     * @return the number of test suites Tia chose to ignore for this run. Engine-level skips
     *         that Tia did not cause (user {@code @Disabled}, surefire {@code groups} filters,
     *         etc.) are deliberately excluded so the value reflects Tia's selection decision
     *         only.
     */
    public int getNumSuitesIgnored() { return numSuitesIgnored; }

    /** @return the number of suites that had at least one failed test */
    public int getNumSuitesFailed() { return numSuitesFailed; }

    /** @return total wall-clock duration of the run, ms */
    public long getDurationMs() { return durationMs; }

    /** @return whether this run persisted updates to the Tia mapping DB */
    public boolean isUpdatedDbMapping() { return updatedDbMapping; }

    /**
     * @return the time Tia saved this run versus running the full suite (ms), frozen at persist
     *         time against the all-tests baseline then current. {@code 0} for all-tests runs and
     *         for runs persisted before any all-tests baseline existed
     */
    public long getTimeSavingsMs() { return timeSavingsMs; }

    /** @return {@link #getTimeSavingsMs()} as a percentage of the full-suite baseline; {@code 0} when none */
    public int getSavingsPercent() { return savingsPercent; }

    /** @return the distributed run this row summarises, or null when the run was single-host */
    public String getRunId() { return runId; }

    /**
     * @return the distributed build's wall-clock test time in ms (the slowest group's duration),
     *         or null when the run was single-host. Deliberately not the primary duration:
     *         reporting it as such would conflate Tia's selection savings with the parallelism the
     *         CI system provided
     */
    public Long getWallClockMs() { return wallClockMs; }

    /** @return the number of groups the distributed build was split across, or null when single-host */
    public Integer getGroupCount() { return groupCount; }

    /**
     * @return where the run came from and which machine executed it; never null, though its host
     *         may be - see {@link RunOrigin}
     */
    public RunOrigin getRunOrigin() { return runOrigin; }

    /**
     * @return count of modified test files that were selected; null when not recorded (a row
     *         written before this feature, or a distributed row before the seal populates it). A
     *         single-host run records the actual count, zero included
     */
    public Integer getNumModifiedTestFiles() { return numModifiedTestFiles; }

    /**
     * @return count of new test files that were selected; null when not recorded (a row written
     *         before this feature, or a distributed row before the seal populates it). A
     *         single-host run records the actual count, zero included
     */
    public Integer getNumNewTestFiles() { return numNewTestFiles; }

    /**
     * @return count of previously-failed suites re-run; null when not recorded (a row written
     *         before this feature, or a distributed row before the seal populates it). A
     *         single-host run records the actual count, zero included
     */
    public Integer getNumPreviouslyFailed() { return numPreviouslyFailed; }

    /**
     * @return count of suites re-run from unsealed mapping rows; null when not recorded (a row
     *         written before this feature, or a distributed row before the seal populates it). A
     *         single-host run records the actual count, zero included
     */
    public Integer getNumUnsealedMapping() { return numUnsealedMapping; }

    /**
     * @return count of suites selected from pending library changes; null when not recorded (a
     *         row written before this feature, or a distributed row before the seal populates it).
     *         A single-host run records the actual count, zero included
     */
    public Integer getNumPendingLibrary() { return numPendingLibrary; }

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
