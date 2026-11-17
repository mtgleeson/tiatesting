package org.tiatesting.core.library;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tiatesting.core.diff.SourceFileDiffContext;
import org.tiatesting.core.library.LibraryPublishFanOut.FanOutResult;
import org.tiatesting.core.library.LibraryPublishStamper.PublishStampResult;
import org.tiatesting.core.model.TrackedLibrary;
import org.tiatesting.core.persistence.DataStore;
import org.tiatesting.core.persistence.DataStoreFactory;
import org.tiatesting.core.persistence.TiaPersistenceException;
import org.tiatesting.core.staticselection.StaticTestSelectionConfig;
import org.tiatesting.core.vcs.FileContentPair;
import org.tiatesting.core.vcs.MappingDrift;
import org.tiatesting.core.vcs.VCSReader;

import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage for {@link LibraryPublishFanOut}: one publish written into every discovered consumer
 * schema, with a failing schema reported rather than stopping the rest.
 */
class LibraryPublishFanOutTest {

    private static final String LIB = "com.example:lib";

    @Test
    void stampsEverySchemaThatTracksTheLibrary(@TempDir Path dir) {
        // given two consumer schemas tracking the library, discovered from a publishing workspace
        // on a third branch
        String db = dir.toString();
        trackIn(db, "mainline", null);
        trackIn(db, "future-development", null);
        List<String> schemas = DataStoreFactory.findSchemasTrackingLibrary(db, null, null, null, null, LIB);

        // when the publish is fanned out
        FanOutResult result = fanOut(db, schemas, schema -> open(db, schema), "libraries-automation");

        // then both schemas hold the ledger row and nothing failed
        assertEquals(2, result.getStamped().size());
        assertTrue(result.getFailed().isEmpty());
        assertNull(result.failureMessage(LIB, "1.0"));
        for (String schema : schemas) {
            try (DataStore dataStore = open(db, schema)) {
                assertEquals(1, dataStore.readLibraryPublishes(LIB).size());
                assertEquals("libraries-automation", dataStore.readLibraryPublishes(LIB).get(0).getSourceLine());
            }
        }
    }

    @Test
    void aFailingSchemaDoesNotStopTheOthers(@TempDir Path dir) {
        // given two consumer schemas, the first of which cannot be opened
        String db = dir.toString();
        trackIn(db, "mainline", null);
        trackIn(db, "future-development", null);
        List<String> schemas = DataStoreFactory.findSchemasTrackingLibrary(db, null, null, null, null, LIB);
        String broken = schemas.get(0);
        String healthy = schemas.get(1);

        // when the publish is fanned out
        FanOutResult result = fanOut(db, schemas, schema -> {
            if (schema.equals(broken)) {
                throw new TiaPersistenceException(new java.sql.SQLException("boom"));
            }
            return open(db, schema);
        }, "libraries-automation");

        // then the healthy schema is stamped and the failure message names both
        assertEquals(Collections.singleton(healthy), result.getStamped().keySet());
        assertEquals(Collections.singleton(broken), result.getFailed().keySet());
        String message = result.failureMessage(LIB, "1.0");
        assertTrue(message.contains(healthy));
        assertTrue(message.contains(broken));
        try (DataStore dataStore = open(db, healthy)) {
            assertEquals(1, dataStore.readLibraryPublishes(LIB).size());
        }
    }

    @Test
    void schemasFollowingAnotherLineAreStampedToo(@TempDir Path dir) {
        // given one consumer schema that follows line A
        String db = dir.toString();
        trackIn(db, "mainline", "lineA");
        List<String> schemas = DataStoreFactory.findSchemasTrackingLibrary(db, null, null, null, null, LIB);

        // when a publish from line B is fanned out
        FanOutResult result = fanOut(db, schemas, schema -> open(db, schema), "lineB");

        // then the schema is stamped: its drain decides whether the batch applies
        assertEquals(PublishStampResult.Outcome.STAMPED,
                result.getStamped().get(schemas.get(0)).getOutcome());
        try (DataStore dataStore = open(db, schemas.get(0))) {
            assertEquals("lineB", dataStore.readLibraryPublishes(LIB).get(0).getSourceLine());
        }
    }

    /**
     * Fan a publish of version 1.0 out to the given schemas.
     *
     * @param db the embedded database directory
     * @param schemas the schemas to stamp
     * @param dataStores opens a datastore per schema
     * @param publishingBranch the publishing workspace's branch, which the stub reader reports as the line
     * @return the fan-out result
     */
    private static FanOutResult fanOut(String db, List<String> schemas,
                                       LibraryPublishFanOut.SchemaDataStores dataStores, String publishingBranch) {
        return new LibraryPublishFanOut().stampEachSchema(schemas, dataStores, new NoChangeVCSReader(), LIB,
                "1.0", null, StaticTestSelectionConfig.EMPTY, Collections.<String>emptyList(), publishingBranch);
    }

    /**
     * Open a datastore on a discovered schema.
     *
     * @param db the embedded database directory
     * @param schema the schema name
     * @return the datastore
     */
    private static DataStore open(String db, String schema) {
        return DataStoreFactory.forSchema(db, null, null, null, null, schema);
    }

    /**
     * Create a branch schema tracking the library with a baseline, optionally following a line.
     *
     * @param db the embedded database directory
     * @param branch the consumer branch whose schema to create
     * @param followedLine the line the schema follows, or null for none
     */
    private static void trackIn(String db, String branch, String followedLine) {
        try (DataStore dataStore = DataStoreFactory.fromConfig(db, null, null, null, null, branch, null)) {
            TrackedLibrary library = new TrackedLibrary(LIB, "/projects/lib", "/projects/lib/src/main/java");
            library.setMappingBaselineCommit("c0");
            library.setFollowedLine(followedLine);
            dataStore.persistTrackedLibrary(library);
        }
    }

    /** A reader at a fixed head with nothing changed; its line is the workspace branch. */
    private static final class NoChangeVCSReader implements VCSReader {
        @Override public String getBranchName() { return "test"; }
        @Override public String getHeadCommit() { return "c1"; }
        @Override public boolean isHeadBehind(String commit) { return false; }

        @Override
        public Set<SourceFileDiffContext> getDiffFiles(String baseChangeNum, List<String> sourceFilesDirs,
                                                       List<String> testFilesDirs, boolean checkLocalChanges) {
            return new HashSet<>();
        }

        @Override
        public void loadContentForDiffs(Collection<SourceFileDiffContext> diffs, String baseChangeNum,
                                        boolean checkLocalChanges) {
        }

        @Override
        public Set<String> getChangedFilePaths(String baseChangeNum, boolean checkLocalChanges) {
            return new HashSet<>();
        }

        @Override
        public Map<String, FileContentPair> loadFileContents(Set<String> paths, String baseChangeNum,
                                                             boolean checkLocalChanges) {
            return Collections.emptyMap();
        }

        @Override
        public MappingDrift findMappingDrift(String baseChangeNum, Collection<SourceFileDiffContext> localDiffs) {
            return null;
        }

        @Override public void close() { }
    }
}
