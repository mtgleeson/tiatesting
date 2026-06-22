package org.tiatesting.core.model;

import java.io.Serializable;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;

public class TiaData implements Serializable {
    private static final long serialVersionUID = 1L;

    /**
     * The version of project used to generate the saved class/method impact analysis for the test stuites.
     */
    private String commitValue;

    /**
     * The VCS branch the stored mapping was generated against. Recorded alongside the commit so a
     * shared (server-mode) database can be attributed to a branch when queried or reported on.
     */
    private String branch;

    /**
     * The date and time the stored mapping was last updated.
     */
    private Instant lastUpdated;

    /**
     * The saved mapping of impacted classes/methods for each test suite.
     */
    private Map<String, TestSuiteTracker> testSuitesTracked = new HashMap<>();

    /**
     * The set of test suites that failed on the previous run. These tests will be forced to re-run in the next
     * (official) selective test run where the mapping is being updated.
     */
    private Set<String> testSuitesFailed = new HashSet<>();

    /**
     * The set of source code methods that are tracked in the DB due to having been part of the test suite coverage.
     * We store the methods keyed by the method name hashcode as an index for storage optimization.
     * i.e. rather than store the method details for every class tracked, we stored the method hashcode only.
     * We can use the method hashcode to look up the method details from this map.
     */
    private Map<Integer, MethodImpactTracker> methodsTracked = new HashMap<>();

    /**
     * Libraries tracked by TIA for deferred impact analysis, keyed by {@code groupId:artifactId}.
     */
    private Map<String, TrackedLibrary> librariesTracked = new HashMap<>();

    /**
     * Pending impacted source method batches from library changes, awaiting drain. Only
     * consumed by the HTML report renderers; the selector and drainer use the per-library
     * {@code DataStore.readPendingLibraryImpactedMethods(groupArtifact)} reads instead. To
     * keep the {@code select-tests} read path from paying for a full-table scan it doesn't
     * need, this field is lazy: the data-store load path may install a
     * {@link #pendingLibraryImpactedMethodsLoader} via
     * {@link #setPendingLibraryImpactedMethodsLoader(Supplier)} and skip the eager populate;
     * the first call to {@link #getPendingLibraryImpactedMethods()} then invokes the loader
     * and caches the result.
     */
    private List<PendingLibraryImpactedMethod> pendingLibraryImpactedMethods = new ArrayList<>();

    /**
     * When set, supplies the pending-method list on first access via
     * {@link #getPendingLibraryImpactedMethods()}. Marked {@code transient} because the
     * supplier typically closes over the {@code DataStore} and is not part of TiaData's
     * persistable state.
     */
    private transient Supplier<List<PendingLibraryImpactedMethod>> pendingLibraryImpactedMethodsLoader;

    /**
     * Tracks whether {@link #pendingLibraryImpactedMethods} reflects a real load (eagerly
     * populated, explicitly set, or already-resolved-via-loader). Starts {@code true} for the
     * default empty list at construction. {@link #setPendingLibraryImpactedMethodsLoader} flips
     * it to {@code false} so the next getter call triggers the loader exactly once.
     */
    private transient boolean pendingLibraryImpactedMethodsLoaded = true;

    /**
     * Log of past Tia test runs on the current branch, ordered most-recent-first.
     * Populated from the {@code tia_test_run_history} table when {@link TiaData} is loaded.
     */
    private List<TestRunHistoryEntry> testRunHistory = new ArrayList<>();

    private TestStats testStats = new TestStats();

    /**
     * Increment the Tia-level stats, routing the run's duration into the selected-run or
     * all-tests-run rolling average.
     *
     * @param testStats   the test statistics
     * @param allTestsRun {@code true} when Tia ignored zero suites this run
     */
    public void incrementStats(final TestStats testStats, final boolean allTestsRun){
        this.testStats.incrementStats(testStats, allTestsRun);
    }

    public String getCommitValue() {
        return commitValue;
    }

    public void setCommitValue(String commitValue) {
        this.commitValue = commitValue;
    }

    /**
     * @return the VCS branch the stored mapping was generated against, or {@code null} if unknown
     */
    public String getBranch() {
        return branch;
    }

    /**
     * Record the VCS branch the stored mapping was generated against.
     *
     * @param branch the VCS branch name
     */
    public void setBranch(String branch) {
        this.branch = branch;
    }

    public Map<String, TestSuiteTracker> getTestSuitesTracked() {
        return testSuitesTracked;
    }

    public void setTestSuitesTracked(Map<String, TestSuiteTracker> testSuitesTracked) {
        this.testSuitesTracked = testSuitesTracked;
    }

    public Set<String> getTestSuitesFailed() {
        return testSuitesFailed;
    }

    public void setTestSuitesFailed(Set<String> testSuitesFailed) {
        this.testSuitesFailed = testSuitesFailed;
    }

    public Instant getLastUpdated() {
        return lastUpdated;
    }

    public void setLastUpdated(Instant lastUpdated) {
        this.lastUpdated = lastUpdated;
    }

    public Map<Integer, MethodImpactTracker> getMethodsTracked() {
        return methodsTracked;
    }

    public void setMethodsTracked(Map<Integer, MethodImpactTracker> methodsTracked) {
        this.methodsTracked = methodsTracked;
    }

    public Map<String, TrackedLibrary> getLibrariesTracked() {
        return librariesTracked;
    }

    public void setLibrariesTracked(Map<String, TrackedLibrary> librariesTracked) {
        this.librariesTracked = librariesTracked;
    }

    /**
     * Return the pending-method list. On first call after
     * {@link #setPendingLibraryImpactedMethodsLoader(Supplier)} was used to install a lazy
     * loader, this invokes the loader and caches the result; subsequent calls return the
     * cached list directly.
     *
     * @return the list of pending impacted-method batches (never null)
     */
    public List<PendingLibraryImpactedMethod> getPendingLibraryImpactedMethods() {
        if (!pendingLibraryImpactedMethodsLoaded && pendingLibraryImpactedMethodsLoader != null) {
            pendingLibraryImpactedMethods = pendingLibraryImpactedMethodsLoader.get();
            pendingLibraryImpactedMethodsLoaded = true;
        }
        return pendingLibraryImpactedMethods;
    }

    /**
     * Eager setter. Marks the field as loaded so a previously-installed lazy loader is not
     * consulted on the next getter call.
     *
     * @param pendingLibraryImpactedMethods the resolved list
     */
    public void setPendingLibraryImpactedMethods(List<PendingLibraryImpactedMethod> pendingLibraryImpactedMethods) {
        this.pendingLibraryImpactedMethods = pendingLibraryImpactedMethods;
        this.pendingLibraryImpactedMethodsLoaded = true;
    }

    /**
     * Install a lazy loader for the pending-method list. The loader is invoked on the first
     * subsequent call to {@link #getPendingLibraryImpactedMethods()}; the resolved list is
     * cached so the loader runs at most once per TiaData instance.
     *
     * @param loader supplier that materialises the pending-method list, typically backed by
     *               {@code DataStore.readAllPendingLibraryImpactedMethods()}. {@code null}
     *               clears any previously-installed loader and leaves the current cached
     *               value in place.
     */
    public void setPendingLibraryImpactedMethodsLoader(Supplier<List<PendingLibraryImpactedMethod>> loader) {
        this.pendingLibraryImpactedMethodsLoader = loader;
        this.pendingLibraryImpactedMethodsLoaded = (loader == null);
    }

    public TestStats getTestStats() {
        return testStats;
    }

    public void setTestStats(TestStats testStats) {
        this.testStats = testStats;
    }

    /**
     * @return the persisted history of past Tia test runs, ordered most-recent-first
     */
    public List<TestRunHistoryEntry> getTestRunHistory() {
        return testRunHistory;
    }

    /**
     * Replace the in-memory test-run-history list. Used by the data store on load.
     *
     * @param testRunHistory the new history list
     */
    public void setTestRunHistory(List<TestRunHistoryEntry> testRunHistory) {
        this.testRunHistory = testRunHistory;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TiaData that = (TiaData) o;
        return Objects.equals(commitValue, that.commitValue) && Objects.equals(lastUpdated, that.lastUpdated);
    }

    @Override
    public int hashCode() {
        return Objects.hash(commitValue, lastUpdated);
    }
}
