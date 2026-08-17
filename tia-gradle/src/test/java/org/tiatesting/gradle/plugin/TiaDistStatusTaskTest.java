package org.tiatesting.gradle.plugin;

import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tiatesting.core.diff.SourceFileDiffContext;
import org.tiatesting.core.model.DistributedRun;
import org.tiatesting.core.model.DistributedRunGroup;
import org.tiatesting.core.model.DistributedRunPlan;
import org.tiatesting.core.persistence.DataStore;
import org.tiatesting.core.vcs.VCSReader;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link TiaDistStatusTask} and its registration by {@link TiaBasePlugin}. The report's own
 * content is covered by {@code DistributedRunStatusReportTest} in {@code tia-core}, which both build
 * tools share; what is left for this class is the wiring only Gradle has - that the task is
 * registered unconditionally, that it reads the branch's datastore, and which of the two run-id
 * sources wins when both are set.
 *
 * <p>Runs against a real embedded-H2 datastore built by the plugin itself, rather than a stubbed
 * one, so the task's own resolution of the datastore and branch is exercised rather than bypassed -
 * a task that read from the wrong store would still pass against a stub.
 */
class TiaDistStatusTaskTest {

    /** Concrete plugin with a stub VCS reader so the task can run without a real repo. */
    static class TestPlugin extends TiaBasePlugin {
        @Override
        public VCSReader getVCSReader() {
            return new StubVCSReader();
        }
    }

    /**
     * Verify that {@link TiaBasePlugin#createDistStatusTask()} registers a {@code tia-dist-status}
     * task of type {@link TiaDistStatusTask}, under the {@code tia-} prefixed name Gradle's flat
     * project-wide task namespace requires - the Maven goal drops the prefix because its plugin
     * prefix already namespaces it.
     */
    @Test
    void pluginRegistersDistStatusTask(@TempDir File projectDir) {
        // given a project with the Tia plugin applied
        Project project = ProjectBuilder.builder().withProjectDir(projectDir).build();
        project.getPlugins().apply(TestPlugin.class);

        // when the task registry is queried for tia-dist-status
        Task task = project.getTasks().getByName("tia-dist-status");

        // then it is a TiaDistStatusTask
        assertInstanceOf(TiaDistStatusTask.class, task);
    }

    /**
     * Verify the task runs to completion and explains itself against a branch that has never planned
     * a distributed run, rather than failing. This is what a developer running the command for the
     * first time sees, and it is also what a CI step running it unconditionally on a non-distributed
     * build would hit - so it must never be the thing that fails the build.
     */
    @Test
    void reportsThatNothingIsPlannedRatherThanFailing(@TempDir File projectDir) {
        // given a project whose datastore holds no distributed run
        Project project = configuredProject(projectDir);

        // when the task runs
        String output = runTask(project);

        // then
        assertTrue(output.contains("No distributed run has been planned on this branch"), output);
    }

    /**
     * Verify the task reports the run configured as {@code tia { runId = ... } } when no command-line
     * flag is given, so a build already configured for a distributed run needs no extra argument.
     */
    @Test
    void reportsTheConfiguredRunIdWhenNoFlagIsGiven(@TempDir File projectDir) {
        // given a planned run, and an extension configured with that run's id
        Project project = configuredProject(projectDir);
        persistPlan(project, "build-99");
        project.getExtensions().getByType(TiaBaseTaskExtension.class).setRunId("build-99");

        // when the task runs with no --runId flag
        String output = runTask(project);

        // then
        assertTrue(output.contains("Distributed run 'build-99'"), output);
    }

    /**
     * Verify {@code --runId} overrides the configured {@code tia { runId } }, so a developer can
     * inspect any run from a workspace whose build file is configured for a different one - the
     * common case being a checkout configured for CI's run id while investigating another build's.
     */
    @Test
    void prefersTheCommandLineRunIdOverTheConfiguredOne(@TempDir File projectDir) {
        // given a planned run, an extension naming it, and a command-line flag naming another
        Project project = configuredProject(projectDir);
        persistPlan(project, "build-99");
        project.getExtensions().getByType(TiaBaseTaskExtension.class).setRunId("build-99");
        TiaDistStatusTask task = (TiaDistStatusTask) project.getTasks().getByName("tia-dist-status");
        task.setRunId("build-1");

        // when the task runs
        String output = runTask(project);

        // then the flag's id is the one looked up, and the configured one is only named as present
        assertTrue(output.contains("No distributed run is planned under run id 'build-1'"), output);
        assertTrue(output.contains("'build-99' (OPEN,"), output);
    }

    /**
     * Verify the assigned suite names are printed only when {@code --suites} is passed. The list is
     * unbounded - a large project's plan can assign thousands of names to one group - so it must not
     * be the default even though the names are read either way.
     */
    @Test
    void listsAssignedSuiteNamesOnlyWhenTheSuitesFlagIsPassed(@TempDir File projectDir) {
        // given a planned run whose groups carry suite names
        Project project = configuredProject(projectDir);
        persistPlan(project, "build-99");
        TiaDistStatusTask task = (TiaDistStatusTask) project.getTasks().getByName("tia-dist-status");

        // when the task runs without the flag, then with it
        String withoutFlag = runTask(project);
        task.setSuites(true);
        String withFlag = runTask(project);

        // then
        assertFalse(withoutFlag.contains("com.example.ATest"), withoutFlag);
        assertTrue(withFlag.contains("Assigned suites:"), withFlag);
        assertTrue(withFlag.contains("com.example.ATest"), withFlag);
    }

    /**
     * Build a project with the plugin applied and an embedded H2 datastore rooted in its own temp
     * directory, so each test reads and writes an isolated store.
     *
     * @param projectDir the temp directory to root both the project and its datastore in
     * @return the configured project, with the Tia plugin applied
     */
    private static Project configuredProject(final File projectDir) {
        Project project = ProjectBuilder.builder().withProjectDir(projectDir).build();
        project.getPlugins().apply(TestPlugin.class);
        TiaBaseTaskExtension ext = project.getExtensions().getByType(TiaBaseTaskExtension.class);
        ext.setEnabled(true);
        ext.setDbFilePath(projectDir.getAbsolutePath());
        return project;
    }

    /**
     * Persist a two-group run plan into the same datastore the task will open, by building it
     * through the plugin rather than independently - a plan written to a store the task does not
     * read would make every assertion here pass for the wrong reason.
     *
     * @param project the project whose plugin owns the datastore
     * @param runId the run identifier to plan under
     */
    private static void persistPlan(final Project project, final String runId) {
        Map<Integer, List<String>> suitesByGroup = new LinkedHashMap<>();
        suitesByGroup.put(0, Arrays.asList("com.example.ATest", "com.example.BTest"));
        suitesByGroup.put(1, Collections.singletonList("com.example.CTest"));
        List<DistributedRunGroup> groups = new ArrayList<>();
        for (int groupNumber = 0; groupNumber < suitesByGroup.size(); groupNumber++) {
            groups.add(DistributedRunGroup.pending(runId, groupNumber, 1000L));
        }
        DistributedRun run = DistributedRun.open(runId, "main", "head-1", groups.size(), null, 2000L,
                System.currentTimeMillis(), false);

        TiaBasePlugin plugin = project.getPlugins().getPlugin(TestPlugin.class);
        try (DataStore dataStore = plugin.buildDataStore("main")) {
            dataStore.getTiaData(true);
            dataStore.persistDistributedRunPlan(new DistributedRunPlan(run, groups, suitesByGroup, null));
        }
    }

    /**
     * Run the project's {@code tia-dist-status} task with stdout captured, since the task's whole
     * output is what it prints.
     *
     * @param project the project whose task is run
     * @return everything the task wrote to stdout
     */
    private static String runTask(final Project project) {
        TiaDistStatusTask task = (TiaDistStatusTask) project.getTasks().getByName("tia-dist-status");
        PrintStream originalOut = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(captured));
            task.run();
        } finally {
            System.setOut(originalOut);
        }
        return captured.toString();
    }

    /** Fixed-value VCS reader, so the task resolves a branch without needing a repo on disk. */
    private static final class StubVCSReader implements VCSReader {

        /**
         * @return the fixed branch name {@code "main"}, which is the branch these tests plan against
         */
        @Override public String getBranchName() { return "main"; }

        /**
         * @return the fixed head commit {@code "head-1"}
         */
        @Override public String getHeadCommit() { return "head-1"; }

        /**
         * Report no diffs at all; this task performs no test selection, so nothing consumes them.
         *
         * @param baseChangeNum unused by this stub
         * @param sourceFilesDirs unused by this stub
         * @param testFilesDirs unused by this stub
         * @param checkLocalChanges unused by this stub
         * @return an empty set
         */
        @Override
        public Set<SourceFileDiffContext> getDiffFiles(String baseChangeNum, List<String> sourceFilesDirs,
                                                        List<String> testFilesDirs, boolean checkLocalChanges) {
            return new HashSet<>();
        }

        /**
         * No-op: this stub returns no diffs from {@link #getDiffFiles}, so there is never anything
         * to load content for.
         *
         * @param diffs unused by this stub
         * @param baseChangeNum unused by this stub
         * @param checkLocalChanges unused by this stub
         */
        @Override
        public void loadContentForDiffs(Collection<SourceFileDiffContext> diffs, String baseChangeNum,
                                         boolean checkLocalChanges) {
            // no-op: this stub returns no diffs
        }

        /**
         * Report no changed file paths; this task performs no test selection.
         *
         * @param baseChangeNum unused by this stub
         * @param checkLocalChanges unused by this stub
         * @return an empty set
         */
        @Override
        public Set<String> getChangedFilePaths(String baseChangeNum, boolean checkLocalChanges) {
            return Collections.emptySet();
        }

        /**
         * No-op: this stub holds no resources to release.
         */
        @Override public void close() { }
    }
}
