package org.tiatesting.core.model;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The per-run breakdown of what drove test selection: the per-changed-method and per-static-rule
 * triggers (each with a suite count), plus scalar counts for the other selection sources that are
 * recorded as totals only. Carried from selection to the point the history row is written; see the
 * "Run history details" chapter in {@code WIKI.md}.
 */
public final class TestRunSelectionDetails implements Serializable {
    private static final long serialVersionUID = 1L;

    private final List<TestRunTrigger> triggers;
    private final int numModifiedTestFiles;
    private final int numNewTestFiles;
    private final int numPreviouslyFailed;
    private final int numUnsealedMapping;
    private final int numPendingLibrary;

    /**
     * Build a breakdown from the per-method and per-rule triggers and the five scalar source
     * counts. The trigger list is defensively copied and exposed unmodifiable.
     *
     * @param triggers the per-method and per-rule triggers; null is tolerated and treated as no
     *                 triggers
     * @param numModifiedTestFiles count of modified test files that were selected
     * @param numNewTestFiles count of new test files that were selected
     * @param numPreviouslyFailed count of previously-failed suites re-run
     * @param numUnsealedMapping count of suites re-run from unsealed mapping rows
     * @param numPendingLibrary count of suites selected from pending library changes
     */
    public TestRunSelectionDetails(List<TestRunTrigger> triggers, int numModifiedTestFiles,
                                   int numNewTestFiles, int numPreviouslyFailed,
                                   int numUnsealedMapping, int numPendingLibrary) {
        this.triggers = triggers == null ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(triggers));
        this.numModifiedTestFiles = numModifiedTestFiles;
        this.numNewTestFiles = numNewTestFiles;
        this.numPreviouslyFailed = numPreviouslyFailed;
        this.numUnsealedMapping = numUnsealedMapping;
        this.numPendingLibrary = numPendingLibrary;
    }

    /**
     * @return an empty breakdown - no triggers, all counters zero - used where a run has nothing
     *         to attribute (an all-tests run) or the breakdown was not recorded
     */
    public static TestRunSelectionDetails empty() {
        return new TestRunSelectionDetails(Collections.emptyList(), 0, 0, 0, 0, 0);
    }

    /** @return every trigger, in the order supplied */
    public List<TestRunTrigger> getTriggers() { return triggers; }

    /** @return the source-method triggers, sorted by suite count descending */
    public List<TestRunTrigger> getSourceMethodTriggers() {
        return TestRunTrigger.filterByTypeSortedByCountDesc(triggers, TestRunTrigger.Type.SOURCE_METHOD);
    }

    /** @return the static-rule triggers, sorted by suite count descending */
    public List<TestRunTrigger> getStaticRuleTriggers() {
        return TestRunTrigger.filterByTypeSortedByCountDesc(triggers, TestRunTrigger.Type.STATIC_RULE);
    }

    /** @return count of modified test files that were selected */
    public int getNumModifiedTestFiles() { return numModifiedTestFiles; }

    /** @return count of new test files that were selected */
    public int getNumNewTestFiles() { return numNewTestFiles; }

    /** @return count of previously-failed suites re-run */
    public int getNumPreviouslyFailed() { return numPreviouslyFailed; }

    /** @return count of suites re-run from unsealed mapping rows */
    public int getNumUnsealedMapping() { return numUnsealedMapping; }

    /** @return count of suites selected from pending library changes */
    public int getNumPendingLibrary() { return numPendingLibrary; }
}
