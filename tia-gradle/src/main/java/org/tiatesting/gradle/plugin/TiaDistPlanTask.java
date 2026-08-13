package org.tiatesting.gradle.plugin;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.tasks.TaskAction;
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
import java.util.Set;

/**
 * Gradle task that plans a distributed test run: it runs the same test selection a normal build
 * would, then splits the selected suites into groups persisted to the shared database, so the CI
 * pipeline can fan out one job per group and every runner can later claim a group from the same
 * source of truth. It does not run any tests itself.
 *
 * <p>Mirrors the Maven {@code tia-dist-plan} goal ({@code AbstractTiaDistPlanMojo}) sequence
 * exactly: validate the distributed-run preconditions and configuration; open the datastore and
 * run the selection exactly as {@code tia-select-tests} does, but with {@code updateDBMapping} set
 * to the real run's configured value rather than always {@code false}; hand the selection to
 * {@link DistributedRunPlanner#plan} to balance and persist the plan; print the resulting summary
 * to the console; and write it to {@code <tiaBuildDir>/tia-run-plan.json} via {@link
 * DistributedRunPlanWriter} - the same writer class the Maven goal uses, so the file's format
 * cannot drift between the two build tools.
 *
 * <p>The selection this task runs is a real one, so it performs the library-impact drain - deleting
 * pending rows and advancing sequences - before the plan is built. That drain cannot be repeated,
 * and repeating it per-runner would race, so its outcome must outlive this process. It does: the
 * {@link TestSelectorResult} handed to {@link DistributedRunPlanner#plan} carries the drain result,
 * and the planner stores it on the persisted run row. Nothing extra is passed alongside the
 * selection deliberately, since a second copy of the same value could disagree with it.
 *
 * <p>Implemented as a {@link DefaultTask} subclass, like {@link TiaHistoryTask} and {@link
 * TiaLibraryPublishesTask}, with its one dependency - the owning {@link TiaBasePlugin}, which
 * exposes every configuration getter and helper this task needs - injected at registration time
 * via {@link #setPlugin(TiaBasePlugin)} rather than resolved when the plugin is applied.
 */
public class TiaDistPlanTask extends DefaultTask {

    private TiaBasePlugin plugin;

    /**
     * Inject the owning plugin; called from {@link TiaBasePlugin#createDistPlanTask()} at task
     * registration so every configuration getter this task needs is resolved lazily at execution
     * time rather than at plugin-apply time.
     *
     * @param plugin the {@link TiaBasePlugin} instance that registered this task
     */
    public void setPlugin(TiaBasePlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Plan a distributed test run: validate the distributed-run preconditions and configuration,
     * run the same test selection {@code tia-select-tests} would with the real run's {@code
     * updateDBMapping} value, balance and persist the plan, then print and write its summary.
     *
     * @throws GradleException if the distributed-run preconditions or configuration are invalid,
     *                          if planning fails (for example no stored mapping exists yet for
     *                          this branch), or if the plan file cannot be written under {@code
     *                          tiaBuildDir}
     */
    @TaskAction
    public void run() {
        System.out.println("Planning a distributed Tia test run:");

        boolean checkLocalChanges = Boolean.TRUE.equals(plugin.getCheckLocalChanges());
        boolean tiaEnabled = Boolean.TRUE.equals(plugin.getEnabled());
        Set<Project> reactorProjects = plugin.getReactorProjects();
        DistributedRunConfig config;
        try {
            DistributedRunPreconditions.check(tiaEnabled, reactorProjects.size(), plugin.getDbUrl(),
                    plugin.getDbDialect(), checkLocalChanges);
            config = DistributedRunConfig.validated(plugin.getRunId(), plugin.getDistributedGroupCount(),
                    plugin.getDistributedTargetRunTime(), plugin.getDistributedMaxGroups(),
                    plugin.getDistributedRunnerKey());
        } catch (IllegalStateException | IllegalArgumentException e) {
            throw new GradleException("Distributed run configuration is invalid: "
                    + withReactorProjectNamesIfRelevant(e.getMessage(), tiaEnabled, reactorProjects), e);
        }

        VCSReader vcsReader = plugin.getVCSReader();
        DistributedRunPlanSummary summary;
        try (DataStore dataStore = plugin.buildDataStore(vcsReader.getBranchName())) {
            List<String> sourceFilesDirs = plugin.getSourceFilesDirs() != null
                    ? Arrays.asList(plugin.getSourceFilesDirs().split(",")) : null;
            StringUtil.sanitizeInputArray(sourceFilesDirs);
            List<String> testFilesDirs = plugin.getTestFilesDirs() != null
                    ? Arrays.asList(plugin.getTestFilesDirs().split(",")) : null;
            StringUtil.sanitizeInputArray(testFilesDirs);

            TestSelector testSelector = new TestSelector(dataStore);
            LibraryImpactAnalysisConfig libraryConfig = plugin.buildLibraryImpactAnalysisConfig();
            StaticTestSelectionConfig staticMappingConfig = plugin.buildStaticTestSelectionConfig();
            boolean updateDBMapping = Boolean.TRUE.equals(plugin.getUpdateDBMapping());
            // checkLocalChanges is already guaranteed false here - DistributedRunPreconditions.check
            // above rejects checkLocalChanges=true before this point is reached. Passed through as
            // the same resolved value (not a literal false) so this stays in lockstep with the
            // Maven goal's isTiaCheckLocalChanges() if that precondition is ever relaxed.
            TestSelectorResult selection = testSelector.selectTestsToIgnore(vcsReader, sourceFilesDirs,
                    testFilesDirs, checkLocalChanges, libraryConfig, staticMappingConfig, updateDBMapping);

            DistributedRunPlanner planner = new DistributedRunPlanner(dataStore, config);
            try {
                summary = planner.plan(selection, vcsReader.getBranchName(), vcsReader.getHeadCommit(),
                        updateDBMapping, System.currentTimeMillis());
            } catch (IllegalStateException e) {
                throw new GradleException("Failed to plan the distributed test run: " + e.getMessage(), e);
            }
        }

        System.out.println(summary.toConsoleSummary());

        try {
            Path written = DistributedRunPlanWriter.write(plugin.getTiaBuildDir(), summary.toJson());
            System.out.println("Wrote the distributed run plan to " + written);
        } catch (IOException e) {
            throw new GradleException("Failed to write the distributed run plan under "
                    + plugin.getTiaBuildDir() + " - the run was still persisted to the database, but "
                    + "the pipeline has no file to read the group count from: " + e.getMessage(), e);
        }
    }

    /**
     * Append the reactor's project names to a precondition failure message when the failure is the
     * multi-project rule, so a user reading this task's console output sees exactly which projects
     * were found in the build - information {@code DistributedRunPreconditions.check} cannot
     * supply itself, since {@code tia-core} has no Gradle type to name them with. The
     * multi-project rule is checked immediately after the Tia-enabled rule and does not depend on
     * any other property, so whenever Tia is enabled and the build holds more than one project the
     * message from {@code check} is always this rule's - there is no need to inspect the message
     * text to tell.
     *
     * @param message the failure message from {@code DistributedRunPreconditions.check}
     * @param tiaEnabled the resolved {@code tia.enabled} value this run started {@code check} with
     * @param reactorProjects the projects {@link TiaBasePlugin#getReactorProjects()} resolved for
     *                        this build
     * @return {@code message} unchanged, or with the build's project names appended when Tia is
     *         enabled and more than one project took part in the build
     */
    private static String withReactorProjectNamesIfRelevant(final String message, final boolean tiaEnabled,
                                                              final Set<Project> reactorProjects) {
        if (!tiaEnabled || reactorProjects.size() <= 1) {
            return message;
        }
        StringBuilder names = new StringBuilder();
        for (Project reactorProject : reactorProjects) {
            if (names.length() > 0) {
                names.append(", ");
            }
            names.append(reactorProject.getName());
        }
        return message + " Projects found in this build: " + names + ".";
    }
}
