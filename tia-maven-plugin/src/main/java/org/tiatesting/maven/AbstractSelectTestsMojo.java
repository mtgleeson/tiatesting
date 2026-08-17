package org.tiatesting.maven;

import org.tiatesting.core.diff.diffanalyze.selector.SelectTestsOutputFormatter;
import org.tiatesting.core.diff.diffanalyze.selector.TestSelector;
import org.tiatesting.core.diff.diffanalyze.selector.TestSelectorResult;
import org.tiatesting.core.distributed.DistributedRunPlanner;
import org.tiatesting.core.distributed.DistributedRunPreviewFormatter;
import org.tiatesting.core.distributed.GroupingResult;
import org.tiatesting.core.library.LibraryImpactAnalysisConfig;
import org.tiatesting.core.persistence.DataStore;
import org.tiatesting.core.staticselection.StaticTestSelectionConfig;
import org.tiatesting.core.util.StringUtil;
import org.tiatesting.core.vcs.VCSReader;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * Mojo used to display the tests selected by Tia based on the changes it will analyse.
 * Note: this previews the selected tests but doesn't actually run them - selection runs with
 * {@code updateDBMapping=false} so library reconcile and pending-stamp persistence are skipped.
 * Drain analysis still runs (read-only) so the preview matches what the agent mojo would select.
 *
 * <p>When {@link #getTiaDistributedGroupCount()} or {@link #getTiaDistributedTargetRunTime()} is
 * configured, the selected tests are also balanced into groups - see {@link
 * #buildDistributedGroupingIfConfigured(TestSelectorResult)} - so the estimate block can report the
 * heaviest group as the time a split build waits for, and {@link
 * #printDistributedRunPreview(TestSelectorResult, GroupingResult)} can describe the grouping. That
 * balance calls {@link DistributedRunPlanner#balance} directly rather than {@link
 * DistributedRunPlanner#plan}, so nothing is persisted and a developer machine without a
 * {@code tiaRunId} can still see it.
 */
public abstract class AbstractSelectTestsMojo extends AbstractTiaMojo {
    @Override
    public void execute() {
        System.out.println("Displaying the tests selected by Tia:");
        final VCSReader vcsReader = getVCSReader();
        try (DataStore dataStore = buildDataStore(vcsReader.getBranchName())) {
            List<String> sourceFilesDirs = getTiaSourceFilesDirs() != null ? Arrays.asList(getTiaSourceFilesDirs().split(",")) : null;
            StringUtil.sanitizeInputArray(sourceFilesDirs);
            List<String> testFilesDirs = getTiaTestFilesDirs() != null ? Arrays.asList(getTiaTestFilesDirs().split(",")) : null;
            StringUtil.sanitizeInputArray(testFilesDirs);

            TestSelector testSelector = new TestSelector(dataStore);
            LibraryImpactAnalysisConfig libraryConfig = buildLibraryImpactAnalysisConfig();
            StaticTestSelectionConfig staticMappingConfig = buildStaticTestSelectionConfig();
            // Read-only preview: no mapping writes (updateDBMapping=false).
            TestSelectorResult result = testSelector.selectTestsToIgnore(vcsReader, sourceFilesDirs,
                    testFilesDirs, isCheckLocalChanges(), libraryConfig, staticMappingConfig, false);
            Set<String> testsToRun = result.getTestsToRun();
            System.out.println("Selected tests to run: ");

            if (result.isRunAllTests()) {
                // No stored mapping for this branch yet: every test runs. testsToRun is empty in
                // this case (see TestSelectorResult#isRunAllTests), so it is checked first and
                // reported distinctly from "nothing selected" below.
                System.out.println("all (no stored mapping for this branch yet)");
                printDistributedRunPreview(result, buildDistributedGroupingIfConfigured(result));
            } else if (testsToRun.isEmpty()){
                System.out.println("none");
            } else {
                System.out.println(SelectTestsOutputFormatter.formatSelectedTestsList(result, "\n"));
                // Balanced before the estimate is printed, not inside the preview below it, because
                // the estimate block reports the heaviest group as the time a distributed build
                // waits for. Balancing once and passing the result down also keeps the balancer's
                // debug logging to a single account of how the suites were packed.
                GroupingResult grouping = buildDistributedGroupingIfConfigured(result);
                // Include the mapping overhead in the estimate when the actual run being previewed
                // will collect coverage (the configured updateDBMapping).
                System.out.println(SelectTestsOutputFormatter.formatEstimateBlock(result, "\n",
                        isTiaUpdateDBMapping(),
                        grouping == null ? null : Long.valueOf(grouping.getHeaviestGroupMs())));
                printDistributedRunPreview(result, grouping);
            }
        }
    }

    /**
     * Balance the selection into groups for preview purposes when the user has configured a
     * distributed run group count or target run time, so a developer previewing {@code select-tests}
     * can see how the selection would be split across runners without creating an actual plan. A
     * user who has not configured either property sees no change at all in this command's output -
     * {@link #getTiaDistributedGroupCount()} and {@link #getTiaDistributedTargetRunTime()} are both
     * {@code null} unless explicitly set, so this returns {@code null} for every non-distributed
     * build.
     *
     * <p>Separate from {@link #printDistributedRunPreview} because the grouping is needed before the
     * preview is printed: the estimate block above it reports the heaviest group as the time a
     * distributed build waits for. Balancing once and passing the result to both consumers also
     * keeps {@code TestGroupBalancer}'s debug logging to one account of how the suites were packed,
     * which a second balance for the same selection would duplicate and make look like two
     * different packings.
     *
     * <p>Calls {@link DistributedRunPlanner#balance}, never {@link DistributedRunPlanner#plan} -
     * {@code plan} persists a claimable run to the shared database, which a preview must not do.
     * It also does not build a {@code DistributedRunConfig} or call {@code
     * DistributedRunPreconditions.check}: a config requires a {@code tiaRunId} this command does
     * not have, and previewing against an embedded database - which a real distributed run would
     * reject - is a legitimate thing to want here since nothing is written.
     *
     * <p>{@code select-tests} is a read-only command every developer runs, often against a shared
     * parent pom's distributed-run properties that developer did not set and may not even be aware
     * of; a misconfiguration in those properties (for example both {@link
     * #getTiaDistributedGroupCount()} and {@link #getTiaDistributedTargetRunTime()} set) must not
     * abort this command. {@link DistributedRunPlanner#balance} throws {@link
     * IllegalArgumentException} for every way the grouping shape can be invalid, so that is caught
     * here and printed as a skip notice instead of propagating - the real {@code dist-plan}
     * goal is still the one place a bad configuration fails the build.
     *
     * @param selection the test selection already computed by {@link #execute()}, whose selected
     *                   suites and their estimated run times are what the preview balances
     * @return the grouping to preview, or null when no distributed shape is configured or the
     *         configured one is invalid (in which case a skip notice has been printed)
     */
    GroupingResult buildDistributedGroupingIfConfigured(final TestSelectorResult selection) {
        if (!isDistributedPreviewConfigured()) {
            return null;
        }
        try {
            return DistributedRunPlanner.balance(selection, isTiaUpdateDBMapping(),
                    getTiaDistributedGroupCount(), getTiaDistributedTargetRunTime(),
                    getTiaDistributedMaxGroups());
        } catch (IllegalArgumentException e) {
            System.out.println("Distributed run grouping preview skipped: " + e.getMessage());
            return null;
        }
    }

    /**
     * Print the grouping preview block for an already-balanced grouping, or nothing at all when
     * there is none - a non-distributed build, or one whose configured shape {@link
     * #buildDistributedGroupingIfConfigured} rejected and already reported.
     *
     * @param selection the test selection the grouping was balanced from; its {@link
     *                   TestSelectorResult#isRunAllTests()} is what tells the formatter to render
     *                   the block as a seed run's
     * @param grouping the balanced grouping to describe, or null to print nothing
     */
    void printDistributedRunPreview(final TestSelectorResult selection, final GroupingResult grouping) {
        if (grouping == null) {
            return;
        }
        System.out.println(DistributedRunPreviewFormatter.formatPreview(grouping,
                getTiaDistributedTargetRunTime(), selection.isRunAllTests(), "\n"));
    }

    /**
     * Report whether this build has configured a distributed run shape, and therefore whether a
     * grouping is balanced for the estimate block and the preview below it.
     *
     * @return true when either a group count or a target run time is configured
     */
    private boolean isDistributedPreviewConfigured() {
        return getTiaDistributedGroupCount() != null || getTiaDistributedTargetRunTime() != null;
    }

    /**
     * Check if Tia should analyze local changes.
     * If we're updating the DB, we shouldn't check for local changes as the DB needs to be in sync with
     * committed changes only.
     *
     * @return
     */
    private boolean isCheckLocalChanges(){
        if (isTiaUpdateDBMapping()){
            return false;
        } else{
            return isTiaCheckLocalChanges();
        }
    }
}
