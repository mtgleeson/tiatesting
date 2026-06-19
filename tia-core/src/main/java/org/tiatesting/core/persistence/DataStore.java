package org.tiatesting.core.persistence;

import org.tiatesting.core.model.MethodImpactTracker;
import org.tiatesting.core.model.PendingLibraryImpactedMethod;
import org.tiatesting.core.model.TestRunHistoryEntry;
import org.tiatesting.core.model.TestSuiteTracker;
import org.tiatesting.core.model.TiaData;
import org.tiatesting.core.model.TrackedLibrary;

import java.util.List;
import java.util.Map;
import java.util.Set;

public interface DataStore extends AutoCloseable {

    /**
     * Release any process-level resources the data store holds open — in particular the H2
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
     * Persist the failed test suites data to disk.
     *
     * @param testSuitesFailed the test suites that were not successful in the test run.
     */
    void persistTestSuitesFailed(final Set<String> testSuitesFailed);

    /**
     * Persist the methods tracked data to disk.
     *
     * @param methodsTracked the list of methods that should be tracked by Tia.
     */
    void persistSourceMethods(final Map<Integer, MethodImpactTracker> methodsTracked);

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
     * Read all pending library impacted method batches for a given library.
     *
     * @param groupArtifact the {@code groupId:artifactId} to read pending batches for.
     * @return list of pending batches, one per stamp version.
     */
    List<PendingLibraryImpactedMethod> readPendingLibraryImpactedMethods(final String groupArtifact);

    /**
     * Read all pending library impacted method batches across all libraries.
     *
     * @return list of all pending batches.
     */
    List<PendingLibraryImpactedMethod> readAllPendingLibraryImpactedMethods();

    /**
     * Persist (insert or merge) a pending library impacted methods batch.
     * If a batch already exists for the same {@code (groupArtifact, stampVersion)},
     * the method IDs are replaced.
     *
     * @param pending the pending batch to persist.
     */
    void persistPendingLibraryImpactedMethods(final PendingLibraryImpactedMethod pending);

    /**
     * Delete all pending library impacted method rows for a specific stamp.
     *
     * @param groupArtifact the {@code groupId:artifactId} of the library.
     * @param stampVersion the stamp version to delete.
     */
    void deletePendingLibraryImpactedMethods(final String groupArtifact, final String stampVersion);

    /**
     * Persist a single Tia test-run history entry. Idempotent on the entry's deterministic id —
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
}
