package org.tiatesting.maven;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.project.MavenProject;
import org.tiatesting.core.diff.diffanalyze.selector.TestSelector;
import org.tiatesting.core.diff.diffanalyze.selector.TestSelectorResult;
import org.tiatesting.core.distributed.DistributedRunConfig;
import org.tiatesting.core.distributed.DistributedRunPlanSummary;
import org.tiatesting.core.distributed.DistributedRunPlanWriter;
import org.tiatesting.core.distributed.DistributedRunPlanner;
import org.tiatesting.core.distributed.DistributedRunPreconditions;
import org.tiatesting.core.library.LibraryImpactAnalysisConfig;
import org.tiatesting.core.persistence.DataStore;
import org.tiatesting.core.staticselection.StaticTestSelectionConfig;
import org.tiatesting.core.util.StringUtil;
import org.tiatesting.core.vcs.VCSReader;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * Mojo that plans a distributed test run: it runs the same test selection a normal build would,
 * then splits the selected suites into groups persisted to the shared database, so the CI
 * pipeline can fan out one job per group and every runner can later claim a group from the same
 * source of truth. It does not run any tests itself.
 *
 * <p>Wherever {@code DistributedRunPlanner} and its collaborators in {@code tia-core} refer to
 * "collecting coverage", the value supplied is this mojo's {@link #isTiaUpdateDBMapping()} - the
 * same flag that controls whether a normal, non-distributed run would update the stored mapping.
 * The plan must weight suites the same way the real distributed run will pay for them, so this
 * mojo passes that flag straight through rather than introducing a second name for the same
 * concept.
 *
 * <p>The selection this goal runs is a real one, so it performs the library-impact drain - deleting
 * pending rows and advancing sequences - before the plan is built. That drain cannot be repeated,
 * and repeating it per-runner would race, so its outcome must outlive this process. It does: the
 * {@link TestSelectorResult} handed to {@link DistributedRunPlanner#plan} carries the drain result,
 * and the planner stores it on the persisted run row. Nothing extra is passed alongside the
 * selection deliberately, since a second copy of the same value could disagree with it.
 *
 * <p>The goal's sequence is: validate the distributed-run preconditions and configuration; open
 * the datastore and run the selection exactly as {@link AbstractSelectTestsMojo} does, but with
 * {@code updateDBMapping} set to the real run's {@link #isTiaUpdateDBMapping()} rather than always
 * {@code false}; hand the selection to {@link DistributedRunPlanner#plan} to balance and persist
 * the plan; print the resulting summary to the console; and write it to {@code
 * <tiaBuildDir>/tia-run-plan.json} via {@link DistributedRunPlanWriter}.
 */
public abstract class AbstractTiaDistPlanMojo extends AbstractTiaMojo {

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        System.out.println("Planning a distributed Tia test run:");

        List<MavenProject> reactorProjects = getReactorProjects();
        DistributedRunConfig config;
        try {
            DistributedRunPreconditions.check(isTiaEnabled(), reactorProjects.size(), getTiaDBUrl(),
                    getTiaDBDialect(), isTiaCheckLocalChanges());
            config = DistributedRunConfig.validated(getTiaRunId(), getTiaDistributedGroupCount(),
                    getTiaDistributedTargetRunTime(), getTiaDistributedMaxGroups(),
                    getTiaDistributedRunnerKey());
        } catch (IllegalStateException | IllegalArgumentException e) {
            throw new MojoExecutionException("Distributed run configuration is invalid: "
                    + withReactorProjectNamesIfRelevant(e.getMessage(), reactorProjects), e);
        }

        final VCSReader vcsReader = getVCSReader();
        DistributedRunPlanSummary summary;
        try (DataStore dataStore = buildDataStore(vcsReader.getBranchName())) {
            List<String> sourceFilesDirs = getTiaSourceFilesDirs() != null
                    ? Arrays.asList(getTiaSourceFilesDirs().split(",")) : null;
            StringUtil.sanitizeInputArray(sourceFilesDirs);
            List<String> testFilesDirs = getTiaTestFilesDirs() != null
                    ? Arrays.asList(getTiaTestFilesDirs().split(",")) : null;
            StringUtil.sanitizeInputArray(testFilesDirs);

            TestSelector testSelector = new TestSelector(dataStore);
            LibraryImpactAnalysisConfig libraryConfig = buildLibraryImpactAnalysisConfig();
            StaticTestSelectionConfig staticMappingConfig = buildStaticTestSelectionConfig();
            // isTiaCheckLocalChanges() is already guaranteed false here - DistributedRunPreconditions.check
            // above rejects tiaCheckLocalChanges=true before this point is reached.
            TestSelectorResult selection = testSelector.selectTestsToIgnore(vcsReader, sourceFilesDirs,
                    testFilesDirs, isTiaCheckLocalChanges(), libraryConfig, staticMappingConfig,
                    isTiaUpdateDBMapping());

            DistributedRunPlanner planner = new DistributedRunPlanner(dataStore, config);
            try {
                summary = planner.plan(selection, vcsReader.getBranchName(), vcsReader.getHeadCommit(),
                        isTiaUpdateDBMapping(), System.currentTimeMillis());
            } catch (IllegalStateException e) {
                throw new MojoExecutionException("Failed to plan the distributed test run: " + e.getMessage(), e);
            }
        }

        System.out.println(summary.toConsoleSummary());

        try {
            Path written = DistributedRunPlanWriter.write(getTiaBuildDir(), summary.toJson());
            System.out.println("Wrote the distributed run plan to " + written);
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to write the distributed run plan under "
                    + getTiaBuildDir() + " - the run was still persisted to the database, but the "
                    + "pipeline has no file to read the group count from: " + e.getMessage(), e);
        }
    }
}
