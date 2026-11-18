package org.tiatesting.core.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tiatesting.core.model.TrackedLibrary;
import org.tiatesting.core.persistence.dialect.H2Dialect;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Coverage for consumer schema discovery: the schemas a library publish stamps are those holding
 * a {@code tia_library} row for its coordinate.
 */
class ConsumerSchemaDiscoveryTest {

    private static final String LIB = "com.example:lib";

    @Test
    void findsOnlySchemasTrackingTheCoordinate(@TempDir Path dir) throws Exception {
        // given two schemas tracking the library (one of them suffixed), one tracking another
        // library, one with no Tia tables and one non-Tia schema
        String db = dir.toString();
        trackIn(db, "mainline", null, LIB);
        trackIn(db, "future-development", "integration", LIB);
        trackIn(db, "other-app", null, "com.example:other");
        createEmptySchemas(db, "tia_empty", "reporting");

        // when the schemas tracking the library are discovered
        List<String> schemas = DataStoreFactory.findSchemasTrackingLibrary(db, null, null, null, null, LIB);

        // then exactly the two tracking schemas are returned, sorted
        assertEquals(Arrays.asList(BranchSchema.schemaName("future-development", "integration"),
                BranchSchema.schemaName("mainline", null)), schemas);
    }

    @Test
    void discoveryCreatesNothing(@TempDir Path dir) throws Exception {
        // given a tracking schema and an empty tia_ schema with no tables
        String db = dir.toString();
        trackIn(db, "mainline", null, LIB);
        createEmptySchemas(db, "tia_empty");

        // when discovery runs
        List<String> schemas = DataStoreFactory.findSchemasTrackingLibrary(db, null, null, null, null, LIB);

        // then the empty schema was not bootstrapped into a Tia schema
        assertEquals(1, schemas.size());
        try (DataStore empty = DataStoreFactory.forSchema(db, null, null, null, null, "tia_empty");
             Connection connection = ((JdbcDataStore) empty).getConnection()) {
            assertFalse(new H2Dialect().tableExists(connection, TiaSchema.TABLE_TIA_CORE));
        }
    }

    @Test
    void aDiscoveredSchemaOpensByName(@TempDir Path dir) {
        // given a schema tracking the library, found by discovery
        String db = dir.toString();
        trackIn(db, "mainline", null, LIB);
        String schema = DataStoreFactory.findSchemasTrackingLibrary(db, null, null, null, null, LIB).get(0);

        // when a datastore is opened on the discovered name
        try (DataStore dataStore = DataStoreFactory.forSchema(db, null, null, null, null, schema)) {

            // then it reads the schema the branch created
            assertEquals("/projects/lib", dataStore.readTrackedLibraries().get(LIB).getProjectDir());
        }
    }

    /**
     * Create a branch schema and track a library in it.
     *
     * @param db the embedded database directory
     * @param branch the branch whose schema to create
     * @param suffix the schema suffix, or null
     * @param groupArtifact the coordinate to track
     */
    private static void trackIn(String db, String branch, String suffix, String groupArtifact) {
        try (DataStore dataStore = DataStoreFactory.fromConfig(db, null, null, null, null, branch, suffix)) {
            dataStore.persistTrackedLibrary(new TrackedLibrary(groupArtifact, "/projects/lib", null));
        }
    }

    /**
     * Create schemas holding no tables.
     *
     * @param db the embedded database directory
     * @param schemaNames the schemas to create
     * @throws Exception if a schema cannot be created
     */
    private static void createEmptySchemas(String db, String... schemaNames) throws Exception {
        try (DataStore any = DataStoreFactory.fromConfig(db, null, null, null, null, "mainline", null);
             Connection connection = ((JdbcDataStore) any).getConnection();
             Statement statement = connection.createStatement()) {
            for (String schemaName : schemaNames) {
                statement.execute("CREATE SCHEMA \"" + schemaName + "\"");
            }
        }
    }
}
