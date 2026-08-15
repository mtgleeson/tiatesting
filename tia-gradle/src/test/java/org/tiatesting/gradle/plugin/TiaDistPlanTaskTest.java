package org.tiatesting.gradle.plugin;

import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tiatesting.core.diff.SourceFileDiffContext;
import org.tiatesting.core.vcs.VCSReader;

import java.io.File;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link TiaDistPlanTask} and its registration by {@link TiaBasePlugin}. The
 * preconditions checked by {@link org.tiatesting.core.distributed.DistributedRunPreconditions#check}
 * and {@link org.tiatesting.core.distributed.DistributedRunConfig#validated} run before any
 * datastore is opened, so each failure path is exercised here without a real database - the same
 * reason the Maven goal's equivalent checks run first.
 */
class TiaDistPlanTaskTest {

    /** Concrete plugin with a stub VCS reader so the task can run without a real repo. */
    static class TestPlugin extends TiaBasePlugin {
        @Override
        public VCSReader getVCSReader() {
            return new StubVCSReader();
        }
    }

    /**
     * Verify that {@link TiaBasePlugin#createDistPlanTask()} registers a {@code tia-dist-plan}
     * task of type {@link TiaDistPlanTask}, mirroring the Maven module's {@code tia-dist-plan}
     * goal name.
     */
    @Test
    void pluginRegistersDistPlanTask(@TempDir File projectDir) {
        // given a project with the Tia plugin applied
        Project project = ProjectBuilder.builder().withProjectDir(projectDir).build();
        project.getPlugins().apply(TestPlugin.class);

        // when the task registry is queried for tia-dist-plan
        Task task = project.getTasks().getByName("tia-dist-plan");

        // then it is a TiaDistPlanTask
        assertInstanceOf(TiaDistPlanTask.class, task);
    }

    /**
     * Verify that running the task with Tia disabled fails fast with a {@link GradleException}
     * naming {@code tiaEnabled}, even though the rest of the configuration (a server-mode URL and
     * a run id) would otherwise be valid - the disabled-Tia rule is checked first, before the
     * shared-database rule, and before any datastore is opened.
     */
    @Test
    void rejectsDisabledTiaBeforeOpeningAnything(@TempDir File projectDir) {
        // given a project with an otherwise-valid distributed-run configuration but Tia disabled
        Project project = ProjectBuilder.builder().withProjectDir(projectDir).build();
        project.getPlugins().apply(TestPlugin.class);
        TiaBaseTaskExtension ext = project.getExtensions().getByType(TiaBaseTaskExtension.class);
        ext.setEnabled(false);
        ext.setDbUrl("jdbc:h2:tcp://h2host:9092/tiadb");
        ext.setRunId("run-1");
        ext.setDistributedGroupCount(4);

        // when the task runs
        TiaDistPlanTask task = (TiaDistPlanTask) project.getTasks().getByName("tia-dist-plan");

        // then it fails with the disabled-Tia precondition message, not the shared-database one
        GradleException e = assertThrows(GradleException.class, task::run);
        assertTrue(e.getMessage().contains("tiaEnabled"));
    }

    /**
     * Verify that running the task against an embedded H2 datastore (no {@code dbUrl} configured)
     * fails fast with a {@link GradleException} naming the shared-database requirement, before any
     * datastore is opened - the case a developer forgetting to configure a server-mode database
     * before a distributed run hits.
     */
    @Test
    void rejectsEmbeddedDatastoreBeforeOpeningAnything(@TempDir File projectDir) {
        // given a project configured for a distributed run but left on embedded H2 (no dbUrl)
        Project project = ProjectBuilder.builder().withProjectDir(projectDir).build();
        project.getPlugins().apply(TestPlugin.class);
        TiaBaseTaskExtension ext = project.getExtensions().getByType(TiaBaseTaskExtension.class);
        ext.setEnabled(true);
        ext.setRunId("run-1");
        ext.setDistributedGroupCount(4);

        // when the task runs
        TiaDistPlanTask task = (TiaDistPlanTask) project.getTasks().getByName("tia-dist-plan");

        // then it fails with the shared-database precondition message
        GradleException e = assertThrows(GradleException.class, task::run);
        assertTrue(e.getMessage().contains("shared database"));
    }

    /**
     * Verify that running the task against a build of more than one project fails fast with a
     * {@link GradleException} naming the project count and every project found, before any
     * datastore is opened - {@code tia-dist-plan} is not bound to run only once across a
     * multi-project build, so each project's plan write would clear the previous project's plan
     * from the shared distributed-run tables.
     */
    @Test
    void rejectsMultiProjectBuildNamingTheProjects(@TempDir File projectDir) {
        // given a root project with a subproject, and an otherwise-valid distributed configuration
        Project root = ProjectBuilder.builder().withProjectDir(projectDir).withName("root-project").build();
        ProjectBuilder.builder().withParent(root).withName("sub-project").build();
        root.getPlugins().apply(TestPlugin.class);
        TiaBaseTaskExtension ext = root.getExtensions().getByType(TiaBaseTaskExtension.class);
        ext.setEnabled(true);
        ext.setDbUrl("jdbc:h2:tcp://h2host:9092/tiadb");
        ext.setRunId("run-1");
        ext.setDistributedGroupCount(4);

        // when the task runs
        TiaDistPlanTask task = (TiaDistPlanTask) root.getTasks().getByName("tia-dist-plan");

        // then it fails naming the project count and both project names
        GradleException e = assertThrows(GradleException.class, task::run);
        assertTrue(e.getMessage().contains("2"), e.getMessage());
        assertTrue(e.getMessage().contains("root-project"), e.getMessage());
        assertTrue(e.getMessage().contains("sub-project"), e.getMessage());
    }

    /**
     * Verify that a disabled Tia, not the multi-project rule, is what a single-project build fails
     * on, and that no project names are appended to the message - the {@code !tiaEnabled} gate on
     * the project-naming helper suppresses naming just as effectively as the multi-project rule
     * never firing does. This test does not prove a single-project build gets past the
     * multi-project rule itself: the disabled-Tia rule is checked first in {@code
     * DistributedRunPreconditions.check}, so with Tia disabled the multi-project rule is never
     * reached regardless of how many projects took part - see {@link
     * #rejectsMissingRunIdBeforeOpeningAnything(File)} for a test that actually proves a
     * single-project build gets past every precondition, including the multi-project rule.
     */
    @Test
    void disabledTiaSuppressesProjectNamingRegardlessOfProjectCount(@TempDir File projectDir) {
        // given a single-project build with an otherwise-valid configuration but Tia disabled
        Project project = ProjectBuilder.builder().withProjectDir(projectDir).build();
        project.getPlugins().apply(TestPlugin.class);
        TiaBaseTaskExtension ext = project.getExtensions().getByType(TiaBaseTaskExtension.class);
        ext.setEnabled(false);
        ext.setDbUrl("jdbc:h2:tcp://h2host:9092/tiadb");
        ext.setRunId("run-1");
        ext.setDistributedGroupCount(4);

        // when the task runs
        TiaDistPlanTask task = (TiaDistPlanTask) project.getTasks().getByName("tia-dist-plan");

        // then the disabled-Tia rule fired, not the multi-project rule
        GradleException e = assertThrows(GradleException.class, task::run);
        assertTrue(e.getMessage().contains("tiaEnabled"), e.getMessage());
    }

    /**
     * Verify that running the task with a server-mode URL configured but no {@code runId} fails
     * with a {@link GradleException} naming the missing property, still before any datastore is
     * opened - {@code DistributedRunConfig.validated} runs immediately after the shared-database
     * check. Doubles as the proof that a single-project build (no subprojects are created here)
     * genuinely gets past every {@code DistributedRunPreconditions.check} rule, including the
     * multi-project rule: if the multi-project rule had rejected this single-project build, the
     * failure would name the project count, not {@code tiaRunId}.
     */
    @Test
    void rejectsMissingRunIdBeforeOpeningAnything(@TempDir File projectDir) {
        // given a project on a server-mode database but with no runId configured
        Project project = ProjectBuilder.builder().withProjectDir(projectDir).build();
        project.getPlugins().apply(TestPlugin.class);
        TiaBaseTaskExtension ext = project.getExtensions().getByType(TiaBaseTaskExtension.class);
        ext.setEnabled(true);
        ext.setDbUrl("jdbc:h2:tcp://h2host:9092/tiadb");
        ext.setDistributedGroupCount(4);

        // when the task runs
        TiaDistPlanTask task = (TiaDistPlanTask) project.getTasks().getByName("tia-dist-plan");

        // then it fails with the missing-runId validation message
        GradleException e = assertThrows(GradleException.class, task::run);
        assertTrue(e.getMessage().contains("tiaRunId"));
    }

    /**
     * Stub VCS reader: fixed branch and head commit, no diffs - sufficient for the precondition
     * failure paths above, which never reach VCS-dependent selection logic.
     */
    private static final class StubVCSReader implements VCSReader {

        /**
         * @return the fixed branch name {@code "main"}, sufficient for the precondition failure
         *         paths above, which never reach VCS-dependent selection logic
         */
        @Override public String getBranchName() { return "main"; }

        /**
         * @return the fixed head commit {@code "head-1"}
         */
        @Override public String getHeadCommit() { return "head-1"; }

        /**
         * Report no diffs at all, since the precondition failure paths above never reach the
         * selection logic that would consume them.
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
         * @param baseChangeNum unused by this stub
         * @param checkLocalChanges unused by this stub
         * @return an empty set - no changed file paths
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
