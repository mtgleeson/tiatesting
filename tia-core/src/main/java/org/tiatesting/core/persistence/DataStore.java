package org.tiatesting.core.persistence;

import org.tiatesting.core.library.LibraryImpactDrainResult;
import org.tiatesting.core.model.DistributedRun;
import org.tiatesting.core.model.DistributedRunGroup;
import org.tiatesting.core.model.DistributedRunPlan;
import org.tiatesting.core.model.LibraryPublish;
import org.tiatesting.core.model.MethodImpactTracker;
import org.tiatesting.core.model.PendingLibraryForcedSelection;
import org.tiatesting.core.model.PendingLibraryImpactedMethod;
import org.tiatesting.core.model.TestRunHistoryEntry;
import org.tiatesting.core.model.TestStats;
import org.tiatesting.core.model.TestSuiteTracker;
import org.tiatesting.core.model.TiaData;
import org.tiatesting.core.model.TrackedLibrary;

import java.util.List;
import java.util.Map;
import java.util.Set;

public interface DataStore extends AutoCloseable {

    /**
     * Release any process-level resources the data store holds open - in particular the H2
     * MVStore file lock that {@code DB_CLOSE_DELAY=-1} keeps in place for the lifetime of the
     * JVM. Maven and Gradle plugin task code <strong>must</strong> call {@code close()} when
     * done with a data store so the file lock is released before a forked test JVM (e.g.
     * surefire) tries to open the same database file; the test JVM otherwise fails with
     * {@code "Database may be already in use"}.
     *
     * <p>Overrides the {@link AutoCloseable#close()} declaration to drop the
     * {@code throws Exception} so callers can use plain {@code try}/{@code finally} without
     * a checked-exception wrapper. The default implementation here is a no-op so non-H2
     * data stores ({@link SerializedDataStore}) don't need to opt in.
     */
    @Override
    default void close() { }

    /**
     * Retrieve the full persisted Tia data.
     *
     * @param readFromDisk should the Tia data be read from disk (if not, it will read from the cache if loaded)
     * @return the fully loaded Tia DB
     */
    TiaData getTiaData(boolean readFromDisk);

    /**
     * Retrieve the persisted Tia core data.
     *
     * @return the Tia core data
     */
    TiaData getTiaCore();

    /**
     * Retrieve the persisted tracked source classes.
     *
     * @return the test suites tracked by Tia
     */
    Map<String, TestSuiteTracker> getTestSuitesTracked();

    /**
     * Retrieve the persisted indexed tracked source methods.
     *
     * @return the methods tracked by Tia
     */
    Map<Integer, MethodImpactTracker> getMethodsTracked();

    /**
     * Retrieve the unique set of method ids tracked for all source classes.
     *
     * @return the unique method ids tracked by Tia
     */
    Set<Integer> getUniqueMethodIdsTracked();

    /**
     * Targeted read (the changed-files-to-tracked-methods step of the select-tests flow): retrieve the tracked methods for a
     * specific set of source files, keyed by file then by method id. Used to resolve the
     * files changed in a VCS diff to their candidate methods without loading the full
     * suite-to-method mapping. The filenames must be in the stored mapping-key format
     * (relative, forward-slash, e.g. {@code com/example/Foo.java} - see
     * {@code SourceFilenameUtil.normalizeToMappingKey}).
     *
     * <p>Files in the input that are not tracked simply have no entry in the result.
     *
     * @param sourceFilenames the mapping keys of the source files to look up
     * @return map of source filename to (method id to method tracker) for the tracked
     *         methods in those files; empty when the input is null or empty
     */
    Map<String, Map<Integer, MethodImpactTracker>> getMethodsTrackedForFiles(final Set<String> sourceFilenames);

    /**
     * Targeted read (the methods-to-covering-suites step of the select-tests flow): retrieve the names of the test suites
     * whose coverage includes any of the given method ids, keyed per method id. Used to
     * resolve the diff-impacted methods to the suites that must run, without building the
     * full in-memory method-to-suites reverse index.
     *
     * <p>Method ids in the input with no covering suite simply have no entry in the result.
     *
     * @param methodIds the tracked method ids to find covering test suites for
     * @return map of method id to the names of the test suites covering it; empty when the
     *         input is null or empty
     */
    Map<Integer, Set<String>> getTestSuitesForMethods(final Set<Integer> methodIds);

    /**
     * Get the number of test suites tracked by Tia in the DB.
     *
     * @return the number of test suites tracked by Tia
     */
    int getNumTestSuites();

    /**
     * Get the number of source methods tracked;
     *
     * @return the number of source methods tracked by Tia
     */
    int getNumSourceMethods();

    /**
     * Get the list of test suites that failed the previous run and are tracked in the Tia to be executed in the next run.
     *
     * @return the list of tests that failed the previous test run
     */
    Set<String> getTestSuitesFailed();

    /**
     * Persist the core data for Tia to disk.
     *
     * @param tiaData the Tia DB object to persist.
     */
    void persistCoreData(final TiaData tiaData);

    /**
     * Persist only the Tia-level run stats onto the core row, leaving the commit value, the branch
     * and the last-updated timestamp exactly as they are stored.
     *
     * <p>This is the write a run that does not own mapping updates makes. Such a run has stats to
     * contribute but no claim over which commit the stored mapping describes, and
     * {@link #persistCoreData(TiaData)} would write the whole row back from a snapshot read at the
     * start of the persist - rolling the stored commit backwards if a mapping-owning build advanced
     * it in the meantime. See the "Persist flow and crash safety" chapter in {@code WIKI.md}.
     *
     * <p>A store with no core row yet has nowhere to put the stats: the commit value is the core
     * row's identity, and only a mapping run establishes it. Implementations leave the store
     * untouched in that case rather than inventing a row.
     *
     * @param testStats the Tia-level run stats to write onto the core row
     */
    void persistCoreStats(final TestStats testStats);

    /**
     * Persist the failed test suites data to disk.
     *
     * @param testSuitesFailed the test suites that were not successful in the test run.
     */
    void persistTestSuitesFailed(final Set<String> testSuitesFailed);

    /**
     * Clear the unsealed flag from every flagged test suite. Called as part of the seal, once the
     * commit value those mapping rows describe is about to become the stored commit.
     */
    void clearUnsealedTestSuites();

    /**
     * Persist the methods tracked data to disk.
     *
     * @param methodsTracked the list of methods that should be tracked by Tia.
     */
    void persistSourceMethods(final Map<Integer, MethodImpactTracker> methodsTracked);

    /**
     * Persist a run's seal atomically: the method catalogue, the library drain cleanup, the
     * clearing of every currently-flagged unsealed suite ({@link #clearUnsealedTestSuites()}) and
     * the commit value are written together, so none of them can end up ahead of the others. The
     * catalogue's line ranges and each library's mapping baseline are both claims about the commit
     * being sealed, so a partial write would leave a later diff reading them against the wrong
     * baseline. See the "Persist flow and crash safety" chapter in {@code WIKI.md}.
     *
     * <p><b>Instance contract.</b> {@code sealedRunData.getTiaData()} must be the very
     * {@link TiaData} instance obtained from {@link #getTiaCore()} for this run, mutated in place
     * with this run's commit, branch and stats. Some implementations persist that instance
     * wholesale rather than field-by-field, so handing in a detached copy would silently drop
     * whatever earlier steps of the run already wrote onto the original instance.
     *
     * @param sealedRunData the complete seal payload
     */
    void persistSealedRunData(final SealedRunData sealedRunData);

    /**
     * Persist the full test suites data to disk - both the per-suite row (name + stats) AND the
     * underlying suite-to-source-class-to-method edges. Used on primary-build runs that update
     * the mapping ({@code updateDBMapping=true}).
     *
     * <p>For stats-only runs ({@code updateDBStats=true, updateDBMapping=false}) use
     * {@link #persistTestSuiteStatsOnly(Map)} instead - that path skips the
     * {@code tia_source_class} / {@code tia_source_class_method} writes, which would otherwise
     * be a wasteful delete-then-reinsert of unchanged mapping rows.
     *
     * @param testSuites the test suites that should be persisted to disk.
     */
    void persistTestSuites(final Map<String, TestSuiteTracker> testSuites);

    /**
     * Persist only the stats columns of the per-suite row (no source-class / method edges).
     * Used on stats-only runs where {@code updateDBStats=true} but {@code updateDBMapping=false}
     * - the mapping rows must remain untouched.
     *
     * @param testSuites the test suites whose stats should be persisted to disk.
     */
    void persistTestSuiteStatsOnly(final Map<String, TestSuiteTracker> testSuites);

    /**
     * Delete the given test suites from disk.
     *
     * @param testSuites the test suites that should be deleted from disk.
     */
    void deleteTestSuites(final Set<String> testSuites);

    /**
     * Read all tracked libraries from the data store, keyed by {@code groupArtifact}.
     *
     * @return map of tracked libraries.
     */
    Map<String, TrackedLibrary> readTrackedLibraries();

    /**
     * Persist (insert or update) a tracked library row.
     *
     * @param trackedLibrary the tracked library to persist.
     */
    void persistTrackedLibrary(final TrackedLibrary trackedLibrary);

    /**
     * Delete a tracked library row by its {@code groupArtifact} key.
     * Cascade-deletes any pending impacted method rows for this library.
     *
     * @param groupArtifact the {@code groupId:artifactId} of the library to delete.
     */
    void deleteTrackedLibrary(final String groupArtifact);

    /**
     * Read a tracked library's publish ledger: one row per published build, ordered by
     * {@code publishSeq} ascending. See the publish-ledger section of the library publish-time stamping chapter in {@code WIKI.md}.
     *
     * @param groupArtifact the {@code groupId:artifactId} to read the ledger for.
     * @return the library's publish rows in sequence order; empty when none exist.
     */
    List<LibraryPublish> readLibraryPublishes(final String groupArtifact);

    /**
     * Persist a published library build and, atomically, the impacted-method stamp and any
     * forced-selection batches for that build. Assigns and returns the next per-library publish
     * sequence. All rows are written in one transaction so a ledger row can never exist without
     * the stamps of the build it identifies.
     *
     * @param publish the publish-ledger row to write.
     * @param impactedMethodIds the tracked source method ids impacted since the baseline; may be empty.
     * @param forcedSelections the forced-selection batches produced by the library's static rules;
     *                         may be empty.
     * @return the assigned publish sequence.
     */
    long persistLibraryPublish(final LibraryPublish publish, final Set<Integer> impactedMethodIds,
                               final List<PendingLibraryForcedSelection> forcedSelections);

    /**
     * Read every pending forced-selection batch across all tracked libraries.
     *
     * @return the forced-selection batches; never {@code null}, may be empty.
     */
    List<PendingLibraryForcedSelection> readAllPendingLibraryForcedSelections();

    /**
     * Read the pending forced-selection batches for one tracked library.
     *
     * @param groupArtifact the {@code groupId:artifactId} of the library.
     * @return the library's forced-selection batches; never {@code null}, may be empty.
     */
    List<PendingLibraryForcedSelection> readPendingLibraryForcedSelections(final String groupArtifact);

    /**
     * Delete the forced-selection batches of one published build after they have been drained.
     *
     * @param groupArtifact the {@code groupId:artifactId} of the library.
     * @param publishSeq the publish sequence whose forced-selection rows to delete.
     */
    void deletePendingLibraryForcedSelections(final String groupArtifact, final long publishSeq);

    /**
     * Read the publish ledger across all tracked libraries, ordered by library then
     * {@code publishSeq} ascending. Reporting-only read (console and HTML library reports).
     *
     * @return every library's publish rows; empty when none exist.
     */
    List<LibraryPublish> readAllLibraryPublishes();

    /**
     * Read the tracked source methods for a specific set of method ids - a targeted read for
     * callers (e.g. the pending-methods report) that need method names and line ranges for a
     * small id set without loading the full methods map.
     *
     * @param methodIds the tracked method ids to read.
     * @return map of method id to its tracker; ids with no tracked row are absent.
     */
    Map<Integer, MethodImpactTracker> getMethodsTrackedForIds(final Set<Integer> methodIds);

    /**
     * Resolve a consumed artifact to its publish ledger row: match by jar content hash first
     * (identifies both SNAPSHOT and release builds), falling back to an exact published-version
     * match when no hash row matches. When multiple rows match (e.g. an identical artifact
     * republished), the highest sequence wins - contents are identical or cumulative, so the
     * higher sequence drains a superset, which is the safe direction.
     *
     * @param groupArtifact the {@code groupId:artifactId} of the library.
     * @param jarHash the resolved artifact's SHA-256 hash, or null to skip hash matching.
     * @param version the resolved version to fall back to, or null to skip version matching
     *                (callers pass null for SNAPSHOT resolved versions, which are shared across
     *                builds and identify nothing).
     * @return the matching ledger row with the highest sequence, or null when nothing matches.
     */
    LibraryPublish lookupLibraryPublish(final String groupArtifact, final String jarHash, final String version);

    /**
     * Read all pending library impacted method batches for a given library.
     *
     * @param groupArtifact the {@code groupId:artifactId} to read pending batches for.
     * @return list of pending batches, one per publish sequence.
     */
    List<PendingLibraryImpactedMethod> readPendingLibraryImpactedMethods(final String groupArtifact);

    /**
     * Read all pending library impacted method batches across all libraries.
     *
     * @return list of all pending batches.
     */
    List<PendingLibraryImpactedMethod> readAllPendingLibraryImpactedMethods();

    /**
     * Persist (insert or merge) a pending library impacted methods batch, keyed by
     * {@code (groupArtifact, publishSeq)}. Production stamps are written atomically with their
     * ledger row via {@link #persistLibraryPublish}; this standalone persist serves batch-level
     * writes outside that flow (e.g. tests).
     *
     * @param pending the pending batch to persist.
     */
    void persistPendingLibraryImpactedMethods(final PendingLibraryImpactedMethod pending);

    /**
     * Delete all pending library impacted method rows for a drained publish.
     *
     * @param groupArtifact the {@code groupId:artifactId} of the library.
     * @param publishSeq the publish sequence whose stamp rows should be deleted.
     */
    void deletePendingLibraryImpactedMethods(final String groupArtifact, final long publishSeq);

    /**
     * Persist a single Tia test-run history entry. Idempotent on the entry's deterministic id -
     * re-inserts of the same logical run are a no-op (MERGE on primary key).
     *
     * @param entry the entry to persist
     */
    void persistTestRunHistoryEntry(final TestRunHistoryEntry entry);

    /**
     * Read all persisted test-run history rows, ordered by {@code runTimestampMs} descending
     * so the most-recent run is first.
     *
     * @return the test-run history list (empty when no rows have been persisted yet)
     */
    List<TestRunHistoryEntry> readTestRunHistory();

    /**
     * Write a complete distributed run plan - the run row, its group rows and its suite
     * assignment - in a single transaction, so a runner reading the plan never observes it
     * partially written.
     *
     * <p>Includes the library-impact drain result the plan's own test selection already performed.
     * That drain deleted pending rows and advanced sequences before the plan was built and cannot
     * be repeated, so storing it here is what lets the run's outstanding cleanup outlive the
     * planning process.
     *
     * @param plan the validated plan to persist
     */
    void persistDistributedRunPlan(final DistributedRunPlan plan);

    /**
     * Read a distributed run by its CI-supplied identifier. Does not carry the plan's drain result;
     * ask {@link #readDistributedRunDrainResult} for that.
     *
     * @param runId the run identifier
     * @return the run, or null if no run is planned under that id
     */
    DistributedRun readDistributedRun(final String runId);

    /**
     * Read the library-impact drain result recorded when the run was planned, so the sealer can
     * apply the cleanup that drain still owes. The drain deleted pending rows and advanced
     * sequences before the plan was built and cannot be repeated, so this is its only record.
     *
     * <p>Separate from {@link #readDistributedRun} on purpose. The value is a Java-serialized blob
     * that only the sealer consumes, while every runner in the build reads the run row to claim its
     * group. Folding it into that read would make a blob no runner looks at - one written by a
     * planner on a different Tia version, say - fail every claim in the build rather than only the
     * cleanup that needs it.
     *
     * @param runId the run identifier
     * @return the drain result, or null if the run drained nothing or no run is planned under that id
     */
    LibraryImpactDrainResult readDistributedRunDrainResult(final String runId);

    /**
     * Read every group of a distributed run, ordered by group number.
     *
     * @param runId the run identifier
     * @return the run's groups in group-number order, empty if the run is unknown
     */
    List<DistributedRunGroup> readDistributedRunGroups(final String runId);

    /**
     * Read the suite names assigned to one group of a distributed run, ordered by name.
     *
     * @param runId the run identifier
     * @param groupNumber the group's zero-based index within the run
     * @return the group's suite names in name order, empty if the group is unknown
     */
    List<String> readDistributedRunGroupSuites(final String runId, final int groupNumber);

    /**
     * Read every distributed run currently held in the plan tables. Because Tia isolates each
     * branch in its own schema and each plan write clears the previous run, this normally returns
     * at most one row - the previous build's run, which the planner inspects so it can warn about
     * groups that never completed before clearing them.
     *
     * @return the runs currently planned, most recently created first, empty if there are none
     */
    List<DistributedRun> readAllDistributedRuns();

    /**
     * Claim exactly one {@code PENDING} group of a distributed run for the calling runner, so no
     * two runners ever end up running the same group and no group is ever left unclaimed while
     * groups remain. No runner is told its group number in advance - it claims one, and this is
     * the operation that decides which.
     *
     * <p>First checks whether this runner key already holds a still-{@code CLAIMED} group in this
     * run (a CI job retry re-claiming its own group after a crash or restart) and returns that group
     * unchanged if so, without attempting a new claim. A runner key holding a group in any other
     * status has already finished with it and is given nothing at all: handing the group back would
     * re-run its suites and then fail {@link #completeGroup}'s {@code status = 'CLAIMED'} predicate,
     * so the run could never seal, while giving it a fresh {@code PENDING} group instead would put
     * that runner key on two group rows - and the "does this runner already hold a group" lookup is
     * a single unordered read of the rows under that key, so from then on it has no one answer.
     * One runner key holds at most one group. Otherwise repeatedly reads the
     * lowest-numbered {@code PENDING} group and attempts a single-row compare-and-swap update
     * guarded by {@code status = 'PENDING'}: two runners racing for the same candidate both attempt
     * that update, the database serialises them, and only one sees a row affected. The loser
     * observes zero rows affected and retries against whatever is now the lowest-numbered {@code
     * PENDING} group. See the "Distributed test runs" chapter in {@code WIKI.md} for the full
     * protocol.
     *
     * @param runId the distributed run to claim a group from
     * @param runnerKey the calling runner's stable identity; must be stable across CI job retries
     *                  for the retry-reclaims-its-own-group behaviour to apply
     * @param claimedAtMs UTC epoch millis to record as the claim time
     * @return the claimed group (freshly claimed, or the still-{@code CLAIMED} one this runner key
     *         already held), or {@code null} when the run has no group left to claim or this runner
     *         key already holds a group it has finished with
     */
    DistributedRunGroup claimNextPendingGroup(final String runId, final String runnerKey, final long claimedAtMs);

    /**
     * Record one test plan's progress on a runner's group, without releasing the barrier: the
     * group stays {@code CLAIMED}. Called on every persist a runner's test JVM makes - possibly
     * several times per JVM, once per Surefire retry - so the figures it carries fall into three
     * kinds that must not be treated alike:
     *
     * <ul>
     *   <li>{@code actualDurationMs} and {@code suitesRan} are counters: this call adds to
     *       whatever is already stored, so several test plans within one JVM sum to the JVM's
     *       total instead of the last one's figure silently overwriting the ones before it. This
     *       is what makes retried suites count correctly toward the group's totals - see the
     *       "Suite retries" material in the distributed test runs chapter of {@code WIKI.md}.
     *       {@code suitesRan} counts only suites that <b>finished</b>, and it is what the sealer
     *       later aggregates into the build's one history row, where {@code ignoredSuiteCount} and
     *       {@code allTestsRun} depend on it meaning exactly that.</li>
     *   <li>{@code suitesFailed} is current state, not a counter: it is replaced outright, because
     *       a suite that passes on retry must legitimately leave the failed set, and accumulating
     *       it would instead leave a fixed suite recorded as permanently failed.</li>
     *   <li>{@code suitesObserved} is written as {@code GREATEST(COALESCE(stored, 0), value)}
     *       rather than accumulated, but for a different reason than {@code suitesFailed}: the set
     *       it is drawn from ({@link org.tiatesting.core.testrunner.TestRunResult#getSuitesObserved()})
     *       is already cumulative across every test plan in the JVM, so summing it here would
     *       double-count. It counts every suite the runner <b>observed</b> - saw finish or saw
     *       skipped - which is deliberately not the same figure as {@code suitesRan}: a suite Tia
     *       never got to run (a class-level {@code @Disabled}, a Surefire/Gradle filter, a class
     *       deleted since the last mapping run) is observed (skipped) without ever being executed.
     *       This is the figure {@link #completeGroup}'s completeness guard reads, precisely so that
     *       a group with such a suite can still complete. Do not conflate {@code suitesRan} and
     *       {@code suitesObserved}: one is the sealer's "executed" figure, the other is the guard's
     *       "accounted for" figure, and they answer different questions.</li>
     * </ul>
     *
     * <p><strong>Precondition: one JVM works one group end to end.</strong> The "already cumulative"
     * claim above about {@code suitesObserved} - and therefore the correctness of {@code GREATEST}
     * over a plain replace - holds only when every call for a given {@code (runId, groupNumber,
     * runnerKey)} comes from the same JVM's shared, monotonically-growing observed set, exactly what
     * a Surefire retry within one JVM is. Maven {@code forkCount > 1} / {@code reuseForks=false} is
     * what breaks this precondition: {@code AbstractTiaAgentMojo.writeForkPropertiesFile} writes one
     * claimed group number and runner key into a single fork-properties file that every Surefire
     * fork for the module reads, so several independent JVMs end up reporting against the same group
     * under the same runner key, each with its own smaller observed set; {@code GREATEST} then
     * converges on the largest single fork's count rather than the true union, which under-counts and
     * can leave the completeness guard blocked even once every fork has finished. This is strictly
     * safer than a plain replace (which could regress below an earlier, more-complete report
     * depending on write order) but does not make multi-fork correct - it is a known-unsupported
     * configuration for the mapping write already, and this call does not attempt to lift that.
     *
     * <p>Gradle {@code maxParallelForks > 1} / {@code forkEvery > 0} breaks the same precondition
     * for the same structural reason: {@code TiaSpockGitGradlePluginTestExtension.claimDistributedRun}
     * claims once in the daemon and forwards the one run id, runner key and group number to the test
     * task as system properties, which Gradle passes to <em>every</em> worker JVM, so all of them
     * report against the same {@code (runId, groupNumber, runnerKey)}. Gradle's case is the worse of
     * the two, because Gradle really does split the group's suites across its workers: no single
     * worker ever observes the whole group, {@code GREATEST} converges on the largest worker's count
     * which is strictly less than the group's assigned total, the completeness guard never passes,
     * the group never completes and the run never seals - on a build that still exits green. That is
     * why {@code claimDistributedRun} refuses both settings when the test task starts, before it
     * forks anything, rather than letting the run hang.
     *
     * <p>Conditional on the group still being {@code CLAIMED} <strong>by this runner key</strong>,
     * the same straggler-protection predicate {@link #completeGroup} is guarded on. A {@code
     * false} return means the claim is no longer live, for exactly the reasons a failed {@link
     * #completeGroup} call would report, and the caller must treat it the same way: there is
     * nothing further to persist for this group from here.
     *
     * @param runId the distributed run the group belongs to
     * @param groupNumber the group's zero-based index within the run
     * @param runnerKey the calling runner's stable identity; must match the key that claimed the
     *                  group or nothing is written
     * @param actualDurationMs this call's measured test-execution time, in ms, added to whatever
     *                         duration is already stored for the group
     * @param suitesRan the number of suites this call's test plan executed, added to whatever
     *                  count is already stored for the group
     * @param suitesFailed the number of suites currently failing, replacing whatever was stored
     * @param suitesObserved the number of suites the runner has observed so far (finished or
     *                       skipped), written as the greatest of this value and whatever was
     *                       already stored
     * @param suitesDurationMs the summed measured run time, in ms, of every suite this runner has
     *                         timed so far, written as the greatest of this value and whatever was
     *                         already stored. Splits the group's duration into the part attributable
     *                         to named suites and the remainder - the runner's fixed per-JVM
     *                         overhead - so the sealer can charge that overhead once for the build
     *                         rather than once per group; see {@code DistributedRunTotals}. Zero
     *                         when suite timing was not collected ({@code updateDBStats} off), which
     *                         the sealer reads as "no decomposition available", not "no overhead"
     * @return {@code true} when the guarded update applied, {@code false} when this runner's
     *         claim is no longer live
     */
    boolean reportGroupProgress(final String runId, final int groupNumber, final String runnerKey,
                                final long actualDurationMs, final int suitesRan,
                                final int suitesFailed, final int suitesObserved,
                                final long suitesDurationMs);

    /**
     * Flip a runner's group to {@code COMPLETED}, releasing the barrier in {@link #electSealer}.
     * Carries no measurements of its own - {@link #reportGroupProgress} records those on every
     * persist - because only the status flip is order-sensitive: it must be the runner's last
     * write for the whole test JVM, not merely the last write of one test plan's persist, since it
     * is what lets the sealer rebuild the method catalogue from a suite-to-method edge set that is
     * guaranteed complete.
     *
     * <p>Conditional on two things at once, both evaluated in the same guarded {@code UPDATE} so
     * the check and the flip are atomic:
     * <ul>
     *   <li>the group is still {@code CLAIMED} <strong>by this runner key</strong> - the straggler
     *       protection, not a defensive nicety: it is what tells a runner from a superseded build,
     *       or one whose group another runner has since re-claimed, that it must write nothing
     *       further;</li>
     *   <li>{@code suites_observed} recorded by {@link #reportGroupProgress} is at least the
     *       number of suites the plan assigned to this group ({@code suites_observed >=
     *       COUNT(*)} over the group's assigned suites, {@code >=} rather than {@code =} because a
     *       completed retry's report can run past the originally assigned total). This is what
     *       stands in for the crash protection a JVM shutdown hook used to provide: without it, a
     *       JVM killed mid-retry (SIGKILL, OOM) after reporting only part of its group's suites
     *       could still have its group completed, sealing the build on a catalogue missing
     *       whatever the killed JVM never got to run - the one failure Tia must never have. The
     *       guard reads {@code suites_observed} rather than {@code suites_ran} deliberately: a
     *       group can observe every assigned suite while executing fewer of them (a class-level
     *       {@code @Disabled} suite, a Surefire/Gradle filter, a class deleted since the last
     *       mapping run), and such a group is genuinely complete - guarding on {@code suites_ran}
     *       would block it forever. {@code suites_observed} is fed from
     *       {@link org.tiatesting.core.testrunner.TestRunResult#getSuitesObserved()}, the suites
     *       this runner's own JVM actually saw finish or saw skipped - never from {@link
     *       org.tiatesting.core.testrunner.TestRunResult#getRunnerTestSuites()}, which on Maven can
     *       be a project-wide directory scan carrying no information about this runner's own
     *       progress.</li>
     * </ul>
     *
     * <p>A {@code null} return means either guard failed - the claim is no longer live (a
     * superseded run, a re-claimed group, or a group already completed), or the group has not yet
     * reported enough progress to be considered finished. The caller <strong>must</strong> treat
     * both the same way: the group stays open, and this run can never elect a sealer or advance
     * the stored commit value. See the "Straggler protection" material in the distributed test
     * runs chapter of {@code WIKI.md}.
     *
     * @param runId the distributed run the group belongs to
     * @param groupNumber the group's zero-based index within the run
     * @param runnerKey the calling runner's stable identity; must match the key that claimed the
     *                  group or nothing is written
     * @param completedAtMs UTC epoch millis to record as the completion time
     * @return the updated group, or {@code null} when this runner's claim is no longer live or the
     *         group has not reported enough progress to close
     */
    DistributedRunGroup completeGroup(final String runId, final int groupNumber, final String runnerKey,
                                      final long completedAtMs);

    /**
     * The barrier: atomically elect the calling runner as the run's one and only sealer, but only
     * once every group of the run has reached {@code COMPLETED} and no other runner has already
     * been elected. Exactly one caller can ever see {@code true} for a given run.
     *
     * <p>The all-groups-complete condition is what makes the sealer's catalogue rebuild correct.
     * The rebuild takes the distinct method ids off the suite-to-method edge table wholesale and
     * any id that query omits is dropped, while each runner writes only its own suites' edges - so
     * running it while a group is still going drops the methods reachable only from that group's
     * suites, making them invisible to the next build's diff.
     *
     * <p><strong>A {@code false} return means do nothing at all, and the caller must not read
     * anything first to "check why".</strong> {@code false} covers both "another runner won" and
     * "my run no longer exists", and the row count is the only thing that distinguishes winning
     * from having been superseded. A straggler sealer from a superseded run that proceeded anyway
     * would find the staging table empty (the superseding plan write cleared it), resolve every
     * method id from disk instead, and silently drop from the catalogue every id the disk catalogue
     * lacks - the exact under-selection the staging table exists to prevent.
     *
     * @param runId the distributed run to elect a sealer for
     * @param runnerKey the calling runner's stable identity, recorded as the sealer when it wins
     * @param sealedAtMs UTC epoch millis to record as the election time
     * @return {@code true} when this runner won the election and must perform the seal,
     *         {@code false} when it must do nothing
     */
    boolean electSealer(final String runId, final String runnerKey, final long sealedAtMs);

    /**
     * Move a distributed run to {@code SEALED}, its terminal state, once its elected sealer has
     * written the catalogue and the commit value. Marking an unknown run is a no-op.
     *
     * @param runId the distributed run to mark sealed
     */
    void markDistributedRunSealed(final String runId);

    /**
     * Stage the method trackers one runner observed, so the sealer can rebuild the method
     * catalogue after the barrier from the union of every runner's observations. In a distributed
     * run no single process holds the whole run's trackers, which is what this table replaces.
     *
     * <p>Repeated calls for the same run merge rather than replace, and a method id staged twice
     * takes the later value. That is safe because every runner is verified at the plan's commit
     * before it runs, so two runners observing the same method observe the same line numbers.
     *
     * @param runId the distributed run to stage under
     * @param methodsTracked the trackers this runner observed, keyed by method id; may be empty
     */
    void persistStagedMethodTrackers(final String runId, final Map<Integer, MethodImpactTracker> methodsTracked);

    /**
     * Read the union of every runner's staged method trackers for a run. The sealer resolves the
     * catalogue's method ids against this, falling back to the stored catalogue for ids no runner
     * re-observed.
     *
     * @param runId the distributed run to read
     * @return the staged trackers keyed by method id, empty if nothing was staged
     */
    Map<Integer, MethodImpactTracker> readStagedMethodTrackers(final String runId);

    /**
     * Delete a run's staged method trackers once the sealer has consumed them. The staging table
     * is roughly the size of the method catalogue, so it is cleared at the seal rather than left
     * until the next plan.
     *
     * @param runId the distributed run to clear; deleting an unknown run is a no-op
     */
    void deleteStagedMethodTrackers(final String runId);
}
