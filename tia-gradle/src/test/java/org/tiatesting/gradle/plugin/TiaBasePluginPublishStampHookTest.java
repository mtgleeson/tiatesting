package org.tiatesting.gradle.plugin;

import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tiatesting.core.diff.SourceFileDiffContext;
import org.tiatesting.core.model.TrackedLibrary;
import org.tiatesting.core.persistence.h2.H2ConnectionSettings;
import org.tiatesting.core.persistence.h2.H2DataStore;
import org.tiatesting.core.vcs.VCSReader;

import java.io.File;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the publish stamp hook {@link TiaBasePlugin} attaches to a library project's Maven
 * publish tasks: the hook attaches only to the publish-named tasks, a primary build's publish
 * records a ledger row for the tracked library, and a non-primary build
 * ({@code updateDBMapping=false}) writes nothing. Uses {@link ProjectBuilder} and executes the
 * attached task actions directly (ProjectBuilder does not run a real task graph).
 */
class TiaBasePluginPublishStampHookTest {

    /** Concrete plugin with a stub VCS reader so the stamp path can run without a real repo. */
    static class TestPlugin extends TiaBasePlugin {
        @Override
        public VCSReader getVCSReader() {
            return new StubVCSReader();
        }
    }

    /**
     * The hook must attach an action to the {@code publish} and {@code publishToMavenLocal}
     * tasks (whenever they are created) and leave unrelated tasks alone.
     */
    @Test
    void hookAttachesOnlyToPublishTasks(@TempDir File projectDir) {
        // given a project with the Tia plugin applied
        Project project = ProjectBuilder.builder().withProjectDir(projectDir).build();
        project.getPlugins().apply(TestPlugin.class);

        // when publish-named and unrelated tasks are created after the plugin applied
        Task publishLocal = project.task("publishToMavenLocal");
        Task publish = project.task("publish");
        Task unrelated = project.task("someOtherTask");

        // then the stamp action is attached to the publish tasks only
        assertEquals(1, publishLocal.getActions().size());
        assertEquals(1, publish.getActions().size());
        assertTrue(unrelated.getActions().isEmpty());
    }

    /**
     * A primary build ({@code updateDBMapping=true}) publishing a tracked library records a
     * ledger row: the first publish seeds the library's ledger (and its mapping baseline).
     */
    @Test
    void primaryBuildPublishRecordsLedgerRow(@TempDir File projectDir, @TempDir File dbDir) {
        // given a tracked library project with Tia configured as the mapping owner
        Project project = ProjectBuilder.builder().withProjectDir(projectDir).withName("mylib").build();
        project.setGroup("com.example");
        project.setVersion("1.0.0-SNAPSHOT");
        project.getPlugins().apply(TestPlugin.class);
        TiaBaseTaskExtension ext = project.getExtensions().getByType(TiaBaseTaskExtension.class);
        ext.setEnabled(true);
        ext.setUpdateDBMapping(true);
        ext.setDbFilePath(dbDir.getAbsolutePath());

        try (H2DataStore seed = new H2DataStore(H2ConnectionSettings.embedded(dbDir.getAbsolutePath(), "main"))) {
            seed.getTiaData(true);
            seed.persistTrackedLibrary(new TrackedLibrary("com.example:mylib", projectDir.getAbsolutePath(), null));
        }

        // when the publishToMavenLocal task's stamp action executes
        Task publishLocal = project.task("publishToMavenLocal");
        publishLocal.getActions().forEach(action -> action.execute(publishLocal));

        // then the publish is recorded in the library's ledger
        try (H2DataStore verify = new H2DataStore(H2ConnectionSettings.embedded(dbDir.getAbsolutePath(), "main"))) {
            assertEquals(1, verify.readLibraryPublishes("com.example:mylib").size());
            assertEquals("1.0.0-SNAPSHOT",
                    verify.readLibraryPublishes("com.example:mylib").get(0).getPublishedVersion());
        }
    }

    /**
     * A non-primary build ({@code updateDBMapping=false}) must not write anything on publish -
     * the shared-DB ownership gate.
     */
    @Test
    void nonPrimaryBuildPublishWritesNothing(@TempDir File projectDir, @TempDir File dbDir) {
        // given a tracked library project on a build that does not own mapping writes
        Project project = ProjectBuilder.builder().withProjectDir(projectDir).withName("mylib").build();
        project.setGroup("com.example");
        project.setVersion("1.0.0-SNAPSHOT");
        project.getPlugins().apply(TestPlugin.class);
        TiaBaseTaskExtension ext = project.getExtensions().getByType(TiaBaseTaskExtension.class);
        ext.setEnabled(true);
        ext.setUpdateDBMapping(false);
        ext.setDbFilePath(dbDir.getAbsolutePath());

        try (H2DataStore seed = new H2DataStore(H2ConnectionSettings.embedded(dbDir.getAbsolutePath(), "main"))) {
            seed.getTiaData(true);
            seed.persistTrackedLibrary(new TrackedLibrary("com.example:mylib", projectDir.getAbsolutePath(), null));
        }

        // when the publishToMavenLocal task's stamp action executes
        Task publishLocal = project.task("publishToMavenLocal");
        publishLocal.getActions().forEach(action -> action.execute(publishLocal));

        // then no ledger row was written
        try (H2DataStore verify = new H2DataStore(H2ConnectionSettings.embedded(dbDir.getAbsolutePath(), "main"))) {
            assertTrue(verify.readLibraryPublishes("com.example:mylib").isEmpty());
        }
    }

    /**
     * Stub VCS reader: fixed branch and head commit, no diffs (the first publish seeds the
     * baseline and diffs nothing anyway).
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
            return new HashSet<>();
        }

        @Override public void close() { }
    }
}
