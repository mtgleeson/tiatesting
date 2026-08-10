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
 * Tests for {@link TiaDistPlanTask} and its registration by {@link TiaBasePlugin}. Both
 * preconditions checked before {@link org.tiatesting.core.distributed.DistributedRunPreconditions#check}
 * and {@link org.tiatesting.core.distributed.DistributedRunConfig#validated} run before any
 * datastore is opened, so both failure paths are exercised here without a real database - the same
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
        ext.setRunId("run-1");
        ext.setDistributedGroupCount(4);

        // when the task runs
        TiaDistPlanTask task = (TiaDistPlanTask) project.getTasks().getByName("tia-dist-plan");

        // then it fails with the shared-database precondition message
        GradleException e = assertThrows(GradleException.class, task::run);
        assertTrue(e.getMessage().contains("shared database"));
    }

    /**
     * Verify that running the task with a server-mode URL configured but no {@code runId} fails
     * with a {@link GradleException} naming the missing property, still before any datastore is
     * opened - {@code DistributedRunConfig.validated} runs immediately after the shared-database
     * check.
     */
    @Test
    void rejectsMissingRunIdBeforeOpeningAnything(@TempDir File projectDir) {
        // given a project on a server-mode database but with no runId configured
        Project project = ProjectBuilder.builder().withProjectDir(projectDir).build();
        project.getPlugins().apply(TestPlugin.class);
        TiaBaseTaskExtension ext = project.getExtensions().getByType(TiaBaseTaskExtension.class);
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
        @Override public String getBranchName() { return "main"; }
        @Override public String getHeadCommit() { return "head-1"; }

        @Override
        public Set<SourceFileDiffContext> getDiffFiles(String baseChangeNum, List<String> sourceFilesDirs,
                                                        List<String> testFilesDirs, boolean checkLocalChanges) {
            return new HashSet<>();
        }

        @Override
        public void loadContentForDiffs(Collection<SourceFileDiffContext> diffs, String baseChangeNum,
                                         boolean checkLocalChanges) {
            // no-op: this stub returns no diffs
        }

        @Override
        public Set<String> getChangedFilePaths(String baseChangeNum, boolean checkLocalChanges) {
            return Collections.emptySet();
        }

        @Override public void close() { }
    }
}
