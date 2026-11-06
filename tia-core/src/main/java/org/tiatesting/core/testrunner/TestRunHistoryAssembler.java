package org.tiatesting.core.testrunner;

import org.tiatesting.core.model.RunOrigin;
import org.tiatesting.core.model.TestRunContribution;
import org.tiatesting.core.model.TestRunContributionTotals;
import org.tiatesting.core.model.TestRunHistoryEntry;
import org.tiatesting.core.model.TestRunSelectionDetails;
import org.tiatesting.core.report.ReportUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds a single-host run's history rows from its JVMs' contributions: one first-attempt row and,
 * once a rerun has contributed, one rerun row. Called under the run's lock on every persist, so the
 * last JVM to persist leaves rows that add up every JVM. See the "Test run history" chapter in
 * {@code WIKI.md}.
 */
final class TestRunHistoryAssembler {

    private TestRunHistoryAssembler() {
    }

    /**
     * Build the run's rows.
     *
     * @param contributions every contribution the run has
     * @param context the run-constant fields, identical in every JVM of the run
     * @return the first-attempt row (if any first-attempt contribution exists) followed by the rerun
     *         row (if any rerun contribution exists)
     */
    static List<TestRunHistoryEntry> assemble(final List<TestRunContribution> contributions,
                                              final RunContext context) {
        List<TestRunContribution> firstAttempt = new ArrayList<>();
        List<TestRunContribution> reruns = new ArrayList<>();
        for (TestRunContribution contribution : contributions) {
            (contribution.isRerun() ? reruns : firstAttempt).add(contribution);
        }

        List<TestRunHistoryEntry> entries = new ArrayList<>();
        if (!firstAttempt.isEmpty()) {
            entries.add(entry(TestRunContributionTotals.of(firstAttempt), false, context));
        }
        if (!reruns.isEmpty()) {
            entries.add(entry(TestRunContributionTotals.of(reruns), true, context));
        }
        return entries;
    }

    /**
     * Build one row. Its savings are zero for a rerun, an all-tests run (nothing ignored), or a run
     * that expected suites and ran none - the last having finished early because nothing ran, not
     * because Tia deselected anything. A run that ignored everything because nothing was impacted
     * keeps its savings. The first-attempt row is stamped with the run's start, and the rerun row
     * with the start of the run's first rerun plan, so reports list the rerun after the attempt it
     * retried.
     *
     * @param totals the row's contributions added up
     * @param rerun whether this is the rerun row
     * @param context the run-constant fields
     * @return the row
     */
    private static TestRunHistoryEntry entry(final TestRunContributionTotals totals, final boolean rerun,
                                             final RunContext context) {
        int ran = totals.getSuitesRan();
        boolean noSavings = rerun || context.ignoredSuiteCount == 0 || (ran == 0 && context.suitesWereSelected);
        long savingsMs = ReportUtils.runSavingsMs(context.allTestsRunTimeMs, totals.getTestTimeMs(), noSavings);
        int savingsPercent = (int) ReportUtils.percentOfTotal(savingsMs, context.allTestsRunTimeMs);
        long runTimestampMs = rerun ? totals.getFirstStartMs() : context.runStartMs;
        return TestRunHistoryEntry.create(context.branch, context.commit, context.runId, rerun,
                runTimestampMs, ran, context.ignoredSuiteCount, totals.getFailedSuites().size(),
                totals.getTestTimeMs(), totals.getWallClockMs(), context.updatesMapping && ran > 0,
                savingsMs, savingsPercent, context.runOrigin, context.selectionDetails);
    }

    /**
     * The fields every JVM of a run shares, taken from its handoff and the baseline read before the
     * write.
     */
    static final class RunContext {
        private final String branch;
        private final String commit;
        private final String runId;
        private final long runStartMs;
        private final int ignoredSuiteCount;
        private final boolean suitesWereSelected;
        private final boolean updatesMapping;
        private final long allTestsRunTimeMs;
        private final RunOrigin runOrigin;
        private final TestRunSelectionDetails selectionDetails;

        /**
         * Construct the context.
         *
         * @param branch the branch the run targeted
         * @param commit the commit the run targeted
         * @param runId the single-host test run id
         * @param runStartMs the run's start time
         * @param ignoredSuiteCount the suites Tia ignored for the run
         * @param suitesWereSelected whether the selection expected at least one suite to run
         * @param updatesMapping whether the run updates the mapping
         * @param allTestsRunTimeMs the full-suite baseline savings are measured against
         * @param runOrigin where the run came from
         * @param selectionDetails the run's selection breakdown
         */
        RunContext(String branch, String commit, String runId, long runStartMs, int ignoredSuiteCount,
                   boolean suitesWereSelected, boolean updatesMapping, long allTestsRunTimeMs,
                   RunOrigin runOrigin, TestRunSelectionDetails selectionDetails) {
            this.branch = branch;
            this.commit = commit;
            this.runId = runId;
            this.runStartMs = runStartMs;
            this.ignoredSuiteCount = ignoredSuiteCount;
            this.suitesWereSelected = suitesWereSelected;
            this.updatesMapping = updatesMapping;
            this.allTestsRunTimeMs = allTestsRunTimeMs;
            this.runOrigin = runOrigin;
            this.selectionDetails = selectionDetails;
        }
    }
}
