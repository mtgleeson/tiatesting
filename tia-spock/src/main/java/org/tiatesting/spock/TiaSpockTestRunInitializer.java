package org.tiatesting.spock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tiatesting.core.diff.diffanalyze.selector.TestSelector;
import org.tiatesting.core.diff.diffanalyze.selector.TestSelectorResult;
import org.tiatesting.core.distributed.DistributedRunConfig;
import org.tiatesting.core.distributed.DistributedRunnerAssignment;
import org.tiatesting.core.library.LibraryImpactAnalysisConfig;
import org.tiatesting.core.persistence.DataStore;
import org.tiatesting.core.staticselection.StaticTestSelectionConfig;
import org.tiatesting.core.vcs.VCSReader;

import java.util.List;

public class TiaSpockTestRunInitializer {
    private static final Logger log = LoggerFactory.getLogger(TiaSpockTestRunInitializer.class);

    private final VCSReader vcsReader;
    private final DataStore dataStore;

    public TiaSpockTestRunInitializer(final VCSReader vcsReader, final DataStore dataStore){
        this.vcsReader = vcsReader;
        this.dataStore = dataStore;
    }

    /**
     * Run Tia's test selection for a Spock build, returning the suites to ignore.
     *
     * @param sourceFilesDirs the configured source-file directories.
     * @param testFilesDirs the configured test-file directories.
     * @param checkLocalChanges whether to inspect the local workspace instead of the commit range.
     * @param updateDBMapping whether this run owns mapping-DB updates.
     * @param libraryConfig the library impact analysis config; may be {@code null}.
     * @param staticMappingConfig the static test selection config; may be {@code null}.
     * @return the {@link TestSelectorResult} produced by {@link TestSelector#selectTestsToIgnore}.
     */
    TestSelectorResult selectTests(final List<String> sourceFilesDirs, final List<String> testFilesDirs,
                                   boolean checkLocalChanges, boolean updateDBMapping,
                                   LibraryImpactAnalysisConfig libraryConfig,
                                   StaticTestSelectionConfig staticMappingConfig){
        TestSelector testSelector = new TestSelector(dataStore);
        return testSelector.selectTestsToIgnore(vcsReader, sourceFilesDirs, testFilesDirs, checkLocalChanges,
                libraryConfig, staticMappingConfig, updateDBMapping);
    }

    /**
     * Claim this runner's group of an already-planned distributed run and resolve the suites it
     * must run and skip, without repeating any of the planning work.
     *
     * <p>This is the distributed alternative to {@link #selectTests}, not an addition to it. The
     * plan produced by {@code tia-dist-plan} already ran the VCS diff, the static rules and the
     * library-impact drain once, for every runner, and its output is in the shared database.
     * Selecting again here would pay for the diff a second time and, worse, drain the pending
     * library rows again - which races with every other runner doing the same.
     *
     * <p>The decision itself lives in {@link DistributedRunnerAssignment}, which the Maven runner
     * also calls. Maven claims in the build JVM before surefire forks and this claims inside the
     * test JVM, so the two entry points differ; sharing the class is what stops the two build
     * tools disagreeing by even one suite about which suites a group owns, which would have a
     * suite run twice or not at all while both builds reported success.
     *
     * <p>Two of the three claim outcomes are failures, and both are left to propagate rather than
     * degraded to a warning: a run id with no plan (this build was superseded, or was never
     * planned) and a workspace on a different commit than the plan was built by diffing. A runner
     * that cannot tell whether its share of the suite ran has no way to report that, so carrying
     * on would report a green build for untested code. The third outcome - every group already
     * claimed - is the legitimate surplus runner, and returns an assignment that runs nothing.
     *
     * @param config the runner's distributed configuration, naming the run to claim from and the
     *               identity to claim under
     * @return this runner's assignment, either its claimed group's suites or the run-nothing
     *         assignment of a surplus runner
     * @throws IllegalStateException if no run is planned under the configured run id, or if the
     *                                plan was built against a different commit than this
     *                                workspace is on
     */
    DistributedRunnerAssignment claimDistributedRunGroup(final DistributedRunConfig config){
        DistributedRunnerAssignment assignment = DistributedRunnerAssignment.claim(dataStore, config,
                vcsReader.getHeadCommit(), System.currentTimeMillis());

        if (assignment.isClaimed()){
            log.info("Tia distributed run '{}': runner '{}' claimed group {} and will run {} test suite(s).",
                    config.getRunId(), assignment.getRunnerKey(), assignment.getGroupNumber(),
                    assignment.getTestsToRun().size());
        } else {
            log.info("Tia distributed run '{}': runner '{}' claimed no group - every group was already "
                            + "claimed, so this runner will run no tests. This is expected when the "
                            + "pipeline fans out to more jobs than the plan has groups.",
                    config.getRunId(), assignment.getRunnerKey());
        }
        return assignment;
    }
}
