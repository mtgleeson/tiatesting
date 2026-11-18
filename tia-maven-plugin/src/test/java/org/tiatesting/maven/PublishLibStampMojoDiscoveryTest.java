package org.tiatesting.maven;

import org.apache.maven.model.Build;
import org.apache.maven.model.Model;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tiatesting.core.diff.SourceFileDiffContext;
import org.tiatesting.core.model.TrackedLibrary;
import org.tiatesting.core.persistence.BranchSchema;
import org.tiatesting.core.persistence.DataStore;
import org.tiatesting.core.persistence.DataStoreFactory;
import org.tiatesting.core.vcs.FileContentPair;
import org.tiatesting.core.vcs.MappingDrift;
import org.tiatesting.core.vcs.VCSReader;

import java.io.File;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Cover the wiring only Maven has in {@link PublishLibStampMojo}: the goal discovers the schemas
 * consuming the module's coordinate and stamps each, with the module's own branch naming no schema.
 * The discovery and the per-schema stamp loop themselves are covered in {@code tia-core}
 * ({@code ConsumerSchemaDiscoveryTest}, {@code LibraryPublishFanOutTest}), which both build tools
 * share.
 */
class PublishLibStampMojoDiscoveryTest {

    private static final String LIB = "com.example:mylib";

    @Test
    void stampsEveryDiscoveredConsumerSchema(@TempDir File dbDir) throws Exception {
        // given - consumers on two branches (one in a suffixed schema) tracking the module, and a
        // publishing workspace on a third branch
        String mainline = seedTrackedLibrary(dbDir, "mainline", null);
        String future = seedTrackedLibrary(dbDir, "future-development", "integration");
        TestMojo mojo = new TestMojo(dbDir);

        // when
        mojo.execute();

        // then - both consuming schemas record the publish, and no schema was made for the
        // publishing branch
        assertEquals(1, publishesIn(dbDir, mainline));
        assertEquals(1, publishesIn(dbDir, future));
        assertEquals("libraries-automation", lineOfFirstPublish(dbDir, mainline));
        assertEquals(Arrays.asList(future, mainline),
                DataStoreFactory.findSchemasTrackingLibrary(dbDir.getAbsolutePath(), null, null, null, null, LIB));
    }

    @Test
    void completesWithoutWritingWhenNoSchemaTracksTheModule(@TempDir File dbDir) throws Exception {
        // given - a database whose only schema tracks a different library
        try (DataStore other = DataStoreFactory.fromConfig(dbDir.getAbsolutePath(), null, null, null, null,
                "mainline", null)) {
            other.persistTrackedLibrary(new TrackedLibrary("com.example:other", "/projects/other", null));
        }
        TestMojo mojo = new TestMojo(dbDir);

        // when
        mojo.execute();

        // then - the goal completed and recorded nothing
        assertEquals(0, publishesIn(dbDir, BranchSchema.schemaName("mainline", null)));
    }

    /**
     * Seed a consumer schema with the module tracked.
     *
     * @param dbDir the embedded database directory
     * @param branch the consumer branch the schema belongs to
     * @param schemaSuffix the schema suffix, or null for the unsuffixed schema
     * @return the seeded schema's name
     */
    private static String seedTrackedLibrary(final File dbDir, final String branch, final String schemaSuffix) {
        try (DataStore seed = DataStoreFactory.fromConfig(dbDir.getAbsolutePath(), null, null, null, null,
                branch, schemaSuffix)) {
            seed.persistTrackedLibrary(new TrackedLibrary(LIB, "/projects/mylib", null));
        }
        return BranchSchema.schemaName(branch, schemaSuffix);
    }

    /**
     * Count the publish-ledger rows recorded for the module in one schema.
     *
     * @param dbDir the embedded database directory
     * @param schemaName the schema to read
     * @return the number of ledger rows
     */
    private static int publishesIn(final File dbDir, final String schemaName) {
        try (DataStore verify = DataStoreFactory.forSchema(dbDir.getAbsolutePath(), null, null, null, null,
                schemaName)) {
            return verify.readLibraryPublishes(LIB).size();
        }
    }

    /**
     * Read the source line recorded on the module's first publish in one schema.
     *
     * @param dbDir the embedded database directory
     * @param schemaName the schema to read
     * @return the recorded source line
     */
    private static String lineOfFirstPublish(final File dbDir, final String schemaName) {
        try (DataStore verify = DataStoreFactory.forSchema(dbDir.getAbsolutePath(), null, null, null, null,
                schemaName)) {
            return verify.readLibraryPublishes(LIB).get(0).getSourceLine();
        }
    }

    /** The goal under test, with the members Maven would inject pointed at this test's fixtures. */
    private static final class TestMojo extends PublishLibStampMojo {

        private final MavenProject mavenProject;

        /**
         * @param dbDir the embedded database directory the goal stamps into
         */
        private TestMojo(final File dbDir) {
            Model model = new Model();
            model.setGroupId("com.example");
            model.setArtifactId("mylib");
            model.setVersion("1.0.0");
            model.setBuild(new Build());
            this.mavenProject = new MavenProject(model);
            this.tiaEnabled = true;
            this.tiaUpdateDBMapping = true;
            this.tiaDBFilePath = dbDir.getAbsolutePath();
            this.tiaBranch = "libraries-automation";
        }

        /**
         * @return a stub VCS reader at a fixed head with nothing changed
         */
        @Override
        public VCSReader getVCSReader() {
            return new StubVCSReader();
        }

        /**
         * @return a bare Maven project carrying the module's coordinate
         */
        @Override
        public MavenProject getProject() {
            return mavenProject;
        }
    }

    /** Fixed-value VCS reader, so the goal reads a head commit without a repository on disk. */
    private static final class StubVCSReader implements VCSReader {
        @Override public String getBranchName() { return "libraries-automation"; }
        @Override public String getHeadCommit() { return "commit-1"; }
        @Override public boolean isHeadBehind(String commit) { return false; }

        @Override
        public Set<SourceFileDiffContext> getDiffFiles(String baseChangeNum, List<String> sourceFilesDirs,
                                                       List<String> testFilesDirs, boolean checkLocalChanges) {
            return Collections.emptySet();
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

        @Override
        public Map<String, FileContentPair> loadFileContents(final Set<String> paths, final String baseChangeNum,
                                                             final boolean checkLocalChanges) {
            return Collections.emptyMap();
        }

        @Override
        public MappingDrift findMappingDrift(String baseChangeNum, Collection<SourceFileDiffContext> localDiffs) {
            return null;
        }

        @Override public void close() { }
    }
}
