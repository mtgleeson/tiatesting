package org.tiatesting.gradle.plugin;

import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tiatesting.core.diff.SourceFileDiffContext;
import org.tiatesting.core.model.PendingLibraryForcedSelection;
import org.tiatesting.core.model.TrackedLibrary;
import org.tiatesting.core.persistence.h2.H2ConnectionSettings;
import org.tiatesting.core.persistence.BranchSchema;
import org.tiatesting.core.persistence.DataStore;
import org.tiatesting.core.persistence.JdbcDataStore;
import org.tiatesting.core.persistence.connection.H2ConnectionProvider;
import org.tiatesting.core.persistence.dialect.H2Dialect;
import org.tiatesting.core.staticselection.StaticTestSelectionRuleMode;
import org.tiatesting.core.vcs.VCSReader;

import java.io.File;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the publish stamp hook {@link TiaBasePlugin} attaches to a library project's Maven
 * publish tasks: the hook attaches only to the publish-named tasks, a primary build's publish
 * records a ledger row for the tracked library, a non-primary build
 * ({@code updateDBMapping=false}) writes nothing, and a publish following one with a configured
 * static test selection rule that matches a changed file records a forced-selection batch (proving
 * the hook passes the plugin's real {@code buildStaticTestSelectionConfig()} result into the
 * stamper). Uses {@link ProjectBuilder} and executes the attached task actions directly
 * (ProjectBuilder does not run a real task graph).
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

        try (JdbcDataStore seed = new JdbcDataStore(new H2Dialect(), new H2ConnectionProvider(H2ConnectionSettings.embedded(dbDir.getAbsolutePath())), BranchSchema.schemaName("main", null))) {
            seed.getTiaData(true);
            seed.persistTrackedLibrary(new TrackedLibrary("com.example:mylib", projectDir.getAbsolutePath(), null));
        }

        // when the publishToMavenLocal task's stamp action executes
        Task publishLocal = project.task("publishToMavenLocal");
        publishLocal.getActions().forEach(action -> action.execute(publishLocal));

        // then the publish is recorded in the library's ledger
        try (JdbcDataStore verify = new JdbcDataStore(new H2Dialect(), new H2ConnectionProvider(H2ConnectionSettings.embedded(dbDir.getAbsolutePath())), BranchSchema.schemaName("main", null))) {
            assertEquals(1, verify.readLibraryPublishes("com.example:mylib").size());
            assertEquals("1.0.0-SNAPSHOT",
                    verify.readLibraryPublishes("com.example:mylib").get(0).getPublishedVersion());
        }
    }

    /**
     * A library whose consumers isolate their test tasks into suffixed schemas must have the stamp
     * written into every one of them. The consuming app is a separate build, so this project cannot
     * see those schemas - the list is declared, and a schema left out never drains the change and
     * never re-runs the suites it affects.
     */
    @Test
    void theStampIsWrittenToEveryDeclaredConsumingSchema(@TempDir File projectDir, @TempDir File dbDir) {
        // given - two consuming schemas declared, and the library tracked in each
        Project project = ProjectBuilder.builder().withProjectDir(projectDir).withName("mylib").build();
        project.setGroup("com.example");
        project.setVersion("2.0.0");
        project.getPlugins().apply(TestPlugin.class);
        TiaBaseTaskExtension ext = project.getExtensions().getByType(TiaBaseTaskExtension.class);
        ext.setEnabled(true);
        ext.setUpdateDBMapping(true);
        ext.setDbFilePath(dbDir.getAbsolutePath());
        ext.setLibraryStampSchemas("unit, integration");

        seedTrackedLibrary(dbDir, projectDir, "unit");
        seedTrackedLibrary(dbDir, projectDir, "integration");

        // when
        Task publishLocal = project.task("publishToMavenLocal");
        publishLocal.getActions().forEach(action -> action.execute(publishLocal));

        // then - both consuming schemas record the publish
        assertEquals(1, publishesIn(dbDir, "unit"), "the unit schema must record the publish");
        assertEquals(1, publishesIn(dbDir, "integration"),
                "the integration schema must record the publish too");
    }

    /**
     * Whitespace around a declared entry is the natural way to write the list, and must not produce
     * a schema named with a leading space.
     */
    @Test
    void declaredSchemasAreTrimmed(@TempDir File projectDir, @TempDir File dbDir) {
        // given
        Project project = ProjectBuilder.builder().withProjectDir(projectDir).withName("mylib").build();
        project.setGroup("com.example");
        project.setVersion("2.0.0");
        project.getPlugins().apply(TestPlugin.class);
        TiaBaseTaskExtension ext = project.getExtensions().getByType(TiaBaseTaskExtension.class);
        ext.setEnabled(true);
        ext.setUpdateDBMapping(true);
        ext.setDbFilePath(dbDir.getAbsolutePath());
        ext.setLibraryStampSchemas("  unit  ");

        seedTrackedLibrary(dbDir, projectDir, "unit");

        // when
        Task publishLocal = project.task("publishToMavenLocal");
        publishLocal.getActions().forEach(action -> action.execute(publishLocal));

        // then
        assertEquals(1, publishesIn(dbDir, "unit"));
    }

    /**
     * With no list declared the stamp goes where it always did - the schema this project itself
     * resolves to - so a library whose consumers use the plain {@code tia_<branch>} schema needs no
     * configuration at all.
     */
    @Test
    void withNoDeclaredSchemasTheStampGoesToTheProjectsOwnSchema(@TempDir File projectDir,
                                                                 @TempDir File dbDir) {
        // given
        Project project = ProjectBuilder.builder().withProjectDir(projectDir).withName("mylib").build();
        project.setGroup("com.example");
        project.setVersion("3.0.0");
        project.getPlugins().apply(TestPlugin.class);
        TiaBaseTaskExtension ext = project.getExtensions().getByType(TiaBaseTaskExtension.class);
        ext.setEnabled(true);
        ext.setUpdateDBMapping(true);
        ext.setDbFilePath(dbDir.getAbsolutePath());

        seedTrackedLibrary(dbDir, projectDir, null);

        // when
        Task publishLocal = project.task("publishToMavenLocal");
        publishLocal.getActions().forEach(action -> action.execute(publishLocal));

        // then
        assertEquals(1, publishesIn(dbDir, null));
    }

    /**
     * Stamping several schemas is not atomic, and the publish has already happened by the time this
     * runs. A schema that fails must not be shrugged off: it will never drain the change, and the
     * suites it affects will never be re-run. Every schema is attempted, so the damage is minimal,
     * and the build then fails naming which hold the stamp and which do not.
     */
    @Test
    void aPartialStampAttemptsEverySchemaThenFailsLoudly(@TempDir File projectDir,
                                                         @TempDir File dbDir) {
        // given - one consuming schema whose datastore cannot be opened
        Project project = ProjectBuilder.builder().withProjectDir(projectDir).withName("mylib").build();
        project.setGroup("com.example");
        project.setVersion("4.0.0");
        project.getPlugins().apply(FailingSchemaPlugin.class);
        TiaBaseTaskExtension ext = project.getExtensions().getByType(TiaBaseTaskExtension.class);
        ext.setEnabled(true);
        ext.setUpdateDBMapping(true);
        ext.setDbFilePath(dbDir.getAbsolutePath());
        ext.setLibraryStampSchemas("unit, broken");

        seedTrackedLibrary(dbDir, projectDir, "unit");

        // when
        Task publishLocal = project.task("publishToMavenLocal");
        GradleException thrown = assertThrows(GradleException.class,
                () -> publishLocal.getActions().forEach(action -> action.execute(publishLocal)));

        // then - the healthy schema was still stamped, and the failure names both sides
        assertEquals(1, publishesIn(dbDir, "unit"),
                "every schema is attempted, so a failure must not cost the healthy ones their stamp");
        assertTrue(thrown.getMessage().contains("unit"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("broken"), thrown.getMessage());
    }

    /**
     * Seed a schema with the library tracked, so a publish stamp into it has something to record
     * against.
     *
     * @param dbDir the embedded database directory
     * @param projectDir the library project's directory, recorded as its source root
     * @param schemaSuffix the schema suffix to seed, or null for the unsuffixed schema
     */
    private static void seedTrackedLibrary(final File dbDir, final File projectDir,
                                           final String schemaSuffix) {
        try (JdbcDataStore seed = new JdbcDataStore(new H2Dialect(),
                new H2ConnectionProvider(H2ConnectionSettings.embedded(dbDir.getAbsolutePath())),
                BranchSchema.schemaName("main", schemaSuffix))) {
            seed.getTiaData(true);
            seed.persistTrackedLibrary(
                    new TrackedLibrary("com.example:mylib", projectDir.getAbsolutePath(), null));
        }
    }

    /**
     * Count the publish-ledger rows recorded for the library in one schema.
     *
     * @param dbDir the embedded database directory
     * @param schemaSuffix the schema suffix to read, or null for the unsuffixed schema
     * @return the number of ledger rows
     */
    private static int publishesIn(final File dbDir, final String schemaSuffix) {
        try (JdbcDataStore verify = new JdbcDataStore(new H2Dialect(),
                new H2ConnectionProvider(H2ConnectionSettings.embedded(dbDir.getAbsolutePath())),
                BranchSchema.schemaName("main", schemaSuffix))) {
            return verify.readLibraryPublishes("com.example:mylib").size();
        }
    }

    /** Plugin whose datastore cannot be opened for one named schema, to force a partial stamp. */
    static class FailingSchemaPlugin extends TestPlugin {
        @Override
        public DataStore buildDataStore(final String branch, final String schemaSuffix) {
            if ("broken".equals(schemaSuffix)) {
                throw new RuntimeException("simulated datastore failure for schema 'broken'");
            }
            return super.buildDataStore(branch, schemaSuffix);
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

        try (JdbcDataStore seed = new JdbcDataStore(new H2Dialect(), new H2ConnectionProvider(H2ConnectionSettings.embedded(dbDir.getAbsolutePath())), BranchSchema.schemaName("main", null))) {
            seed.getTiaData(true);
            seed.persistTrackedLibrary(new TrackedLibrary("com.example:mylib", projectDir.getAbsolutePath(), null));
        }

        // when the publishToMavenLocal task's stamp action executes
        Task publishLocal = project.task("publishToMavenLocal");
        publishLocal.getActions().forEach(action -> action.execute(publishLocal));

        // then no ledger row was written
        try (JdbcDataStore verify = new JdbcDataStore(new H2Dialect(), new H2ConnectionProvider(H2ConnectionSettings.embedded(dbDir.getAbsolutePath())), BranchSchema.schemaName("main", null))) {
            assertTrue(verify.readLibraryPublishes("com.example:mylib").isEmpty());
        }
    }

    /**
     * A tracked library with a configured static test selection rule whose file-path pattern
     * matches a file changed since the library's mapping baseline gets a forced-selection batch
     * recorded on the publish that follows the seed - proving the plugin passes its own real
     * {@code buildStaticTestSelectionConfig()} result into the stamper rather than the
     * {@code StaticTestSelectionConfig.EMPTY} placeholder.
     */
    @Test
    void primaryBuildPublishWithMatchingStaticRuleRecordsForcedSelection(@TempDir File projectDir, @TempDir File dbDir) {
        // given a tracked library project with a static rule matching a changed sql file
        Project project = ProjectBuilder.builder().withProjectDir(projectDir).withName("mylib").build();
        project.setGroup("com.example");
        project.setVersion("1.0.0-SNAPSHOT");
        project.getPlugins().apply(ForcedSelectionTestPlugin.class);
        TiaBaseTaskExtension ext = project.getExtensions().getByType(TiaBaseTaskExtension.class);
        ext.setEnabled(true);
        ext.setUpdateDBMapping(true);
        ext.setDbFilePath(dbDir.getAbsolutePath());
        ext.setStaticTestSelectionRules(Collections.singletonList(
                gradleRule("sql-run-all", "\\.sql$", "RUN_ALL", null)));

        try (JdbcDataStore seed = new JdbcDataStore(new H2Dialect(), new H2ConnectionProvider(H2ConnectionSettings.embedded(dbDir.getAbsolutePath())), BranchSchema.schemaName("main", null))) {
            seed.getTiaData(true);
            seed.persistTrackedLibrary(new TrackedLibrary("com.example:mylib", "/repo/mylib", null));
        }

        // when the publishToMavenLocal stamp action runs twice: the first publish seeds the
        // mapping baseline (no static rules are evaluated yet), the second evaluates the
        // configured rule against the file changed since that baseline
        Task publishLocal = project.task("publishToMavenLocal");
        publishLocal.getActions().forEach(action -> action.execute(publishLocal));
        publishLocal.getActions().forEach(action -> action.execute(publishLocal));

        // then a forced-selection batch is recorded for the matching rule
        try (JdbcDataStore verify = new JdbcDataStore(new H2Dialect(), new H2ConnectionProvider(H2ConnectionSettings.embedded(dbDir.getAbsolutePath())), BranchSchema.schemaName("main", null))) {
            List<PendingLibraryForcedSelection> forced = verify.readPendingLibraryForcedSelections("com.example:mylib");
            assertEquals(1, forced.size());
            assertEquals("sql-run-all", forced.get(0).getRuleName());
            assertEquals(StaticTestSelectionRuleMode.RUN_ALL, forced.get(0).getMode());
        }
    }

    /**
     * Build a {@link GradleStaticTestSelectionRule} POJO the way the Gradle {@code tia} extension
     * DSL would populate one, for use as test fixture data.
     *
     * @param name the rule's display name.
     * @param filePathPattern the regex matched against changed file paths.
     * @param mode the rule mode, as the raw DSL string (e.g. {@code "RUN_ALL"}).
     * @param suiteNamePatterns the suite-name regex patterns; {@code null} for modes that don't use them.
     * @return the populated rule POJO.
     */
    private static GradleStaticTestSelectionRule gradleRule(String name, String filePathPattern, String mode,
                                                             List<String> suiteNamePatterns) {
        GradleStaticTestSelectionRule rule = new GradleStaticTestSelectionRule();
        rule.setName(name);
        rule.setFilePathPattern(filePathPattern);
        rule.setMode(mode);
        rule.setSuiteNamePatterns(suiteNamePatterns);
        return rule;
    }

    /** Concrete plugin whose VCS reader reports a changed file matching the test's static rule. */
    static class ForcedSelectionTestPlugin extends TiaBasePlugin {
        @Override
        public VCSReader getVCSReader() {
            return new ForcedSelectionStubVCSReader();
        }
    }

    /**
     * Stub VCS reader for the static-rule forced-selection test: fixed branch and head commit,
     * no coverage-relevant diffs, but one changed file path (under the fictitious library module
     * dir {@code /repo/mylib} used by the test's {@link TrackedLibrary}) that matches the test's
     * {@code \.sql$} rule.
     */
    private static final class ForcedSelectionStubVCSReader implements VCSReader {
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
            Set<String> changed = new HashSet<>();
            changed.add("mylib/db/migration.sql");
            return changed;
        }

        @Override public void close() { }
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
