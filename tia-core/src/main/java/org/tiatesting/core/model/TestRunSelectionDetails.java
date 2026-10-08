package org.tiatesting.core.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The per-run breakdown of what drove test selection: the per-changed-method and per-static-rule
 * triggers (each with a suite count), plus scalar counts for the other selection sources that are
 * recorded as totals only. Carried from selection to the point the history row is written; see the
 * "Run history details" chapter in {@code WIKI.md}.
 */
public final class TestRunSelectionDetails {

    private final List<TestRunTrigger> triggers;
    private final int numModifiedTestFiles;
    private final int numNewTestFiles;
    private final int numPreviouslyFailed;
    private final int numUnsealedMapping;
    private final int numPendingLibrary;
    private final SelectionMode selectionMode;

    /**
     * Build a breakdown from the per-method and per-rule triggers, the five scalar source counts,
     * and the mode that decided the run's selection. The trigger list is defensively copied and
     * exposed unmodifiable.
     *
     * @param triggers the per-method and per-rule triggers; null is tolerated and treated as no
     *                 triggers
     * @param numModifiedTestFiles count of modified test files that were selected
     * @param numNewTestFiles count of new test files that were selected
     * @param numPreviouslyFailed count of previously-failed suites re-run
     * @param numUnsealedMapping count of suites re-run from unsealed mapping rows
     * @param numPendingLibrary count of suites selected from pending library changes
     * @param selectionMode how the run's selection was decided; null is treated as SELECTIVE
     */
    public TestRunSelectionDetails(List<TestRunTrigger> triggers, int numModifiedTestFiles,
                                   int numNewTestFiles, int numPreviouslyFailed,
                                   int numUnsealedMapping, int numPendingLibrary,
                                   SelectionMode selectionMode) {
        this.triggers = triggers == null ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(triggers));
        this.numModifiedTestFiles = numModifiedTestFiles;
        this.numNewTestFiles = numNewTestFiles;
        this.numPreviouslyFailed = numPreviouslyFailed;
        this.numUnsealedMapping = numUnsealedMapping;
        this.numPendingLibrary = numPendingLibrary;
        this.selectionMode = selectionMode == null ? SelectionMode.SELECTIVE : selectionMode;
    }

    /**
     * @return an empty breakdown - no triggers, all counters zero, {@link SelectionMode#SELECTIVE} -
     *         used where the breakdown was not recorded
     */
    public static TestRunSelectionDetails empty() {
        return new TestRunSelectionDetails(Collections.emptyList(), 0, 0, 0, 0, 0,
                SelectionMode.SELECTIVE);
    }

    /**
     * Build the breakdown of a run that executed every test: nothing to attribute, so no triggers
     * and zero counters, but the mode that made it a full run is kept for the history row.
     *
     * @param selectionMode the full-run mode
     * @return an empty breakdown carrying {@code selectionMode}
     */
    public static TestRunSelectionDetails forFullRun(SelectionMode selectionMode) {
        return new TestRunSelectionDetails(Collections.emptyList(), 0, 0, 0, 0, 0, selectionMode);
    }

    /**
     * Copy this breakdown with a different mode. The distributed sealer uses it to stamp the mode
     * recorded on the run row onto the breakdown the planner staged.
     *
     * @param newMode the mode for the copy
     * @return a copy with every trigger and counter unchanged and the mode replaced
     */
    public TestRunSelectionDetails withSelectionMode(SelectionMode newMode) {
        return new TestRunSelectionDetails(triggers, numModifiedTestFiles, numNewTestFiles,
                numPreviouslyFailed, numUnsealedMapping, numPendingLibrary, newMode);
    }

    /** @return every trigger, in the order supplied */
    public List<TestRunTrigger> getTriggers() { return triggers; }

    /** @return the source-method triggers, sorted by suite count descending */
    public List<TestRunTrigger> getSourceMethodTriggers() {
        return TestRunTrigger.filterByTypeSortedByCountDesc(triggers, TestRunTrigger.Type.SOURCE_METHOD);
    }

    /**
     * The catalogue ids of the changed methods that triggered this run, for the per-method
     * triggered-run count accumulated at the seal. See the "Method run stats" chapter in
     * {@code WIKI.md}.
     *
     * @return the method ids of the source-method triggers that carry one; empty if none do
     */
    public Set<Integer> getTriggeredMethodIds() {
        Set<Integer> methodIds = new HashSet<>();
        for (TestRunTrigger trigger : triggers) {
            if (trigger.getType() == TestRunTrigger.Type.SOURCE_METHOD && trigger.getMethodId() != null) {
                methodIds.add(trigger.getMethodId());
            }
        }
        return methodIds;
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

    /** @return how this run's selection was decided; never null */
    public SelectionMode getSelectionMode() { return selectionMode; }
}
