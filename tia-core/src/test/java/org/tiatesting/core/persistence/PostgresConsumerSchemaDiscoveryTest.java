package org.tiatesting.core.persistence;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tiatesting.core.model.TrackedLibrary;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Consumer schema discovery against Postgres, whose schema listing and identifier folding differ
 * from H2's. The database is shared with other tests, so the library coordinate is unique to this
 * class and only the schemas created here can match it.
 *
 * <p>Skipped (not failed) when no Postgres is reachable on {@code localhost:5432}, exactly like
 * {@link PostgresClaimTest}.
 */
class PostgresConsumerSchemaDiscoveryTest {

    private static final String POSTGRES_URL = "jdbc:postgresql://localhost:5432/tiaperf";
    private static final String POSTGRES_USER = "tia";
    private static final String POSTGRES_PASSWORD = "tia";
    private static final String LIB = "com.example:discovery-lib";
    private static final String[] BRANCHES = {"discovery-mainline", "discovery-future", "discovery-other"};

    /**
     * Skip the test when the local Postgres instance is not reachable.
     */
    private static void assumePg() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", 5432), 500);
        } catch (IOException e) {
            assumeTrue(false, "spike Postgres not running");
        }
    }

    /**
     * Drop the schemas this class creates.
     *
     * @throws SQLException if a drop fails
     */
    private static void dropSchemas() throws SQLException {
        try (Connection connection = DriverManager.getConnection(POSTGRES_URL, POSTGRES_USER, POSTGRES_PASSWORD);
             Statement statement = connection.createStatement()) {
            for (String branch : BRANCHES) {
                statement.executeUpdate("DROP SCHEMA IF EXISTS " + BranchSchema.schemaName(branch, null) + " CASCADE");
            }
            statement.executeUpdate("DROP SCHEMA IF EXISTS tia_discovery_empty CASCADE");
        }
    }

    /**
     * Skip when Postgres is unreachable, then start from no schemas of this class's own.
     *
     * @throws Exception if the guard or a drop fails
     */
    @BeforeEach
    void setUp() throws Exception {
        assumePg();
        dropSchemas();
    }

    /**
     * Leave the shared database as it was found.
     *
     * @throws Exception if a drop fails
     */
    @AfterEach
    void tearDown() throws Exception {
        dropSchemas();
    }

    @Test
    void findsOnlySchemasTrackingTheCoordinateOnPostgres() throws Exception {
        // given two schemas tracking the library, one tracking another and one with no Tia tables
        trackIn(BRANCHES[0], LIB);
        trackIn(BRANCHES[1], LIB);
        trackIn(BRANCHES[2], "com.example:discovery-other-lib");
        try (Connection connection = DriverManager.getConnection(POSTGRES_URL, POSTGRES_USER, POSTGRES_PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE SCHEMA tia_discovery_empty");
        }

        // when the schemas tracking the library are discovered
        List<String> schemas = DataStoreFactory.findSchemasTrackingLibrary(null, POSTGRES_URL, POSTGRES_USER,
                POSTGRES_PASSWORD, null, LIB);

        // then exactly the two tracking schemas are returned, sorted, and each opens by name
        assertEquals(Arrays.asList(BranchSchema.schemaName(BRANCHES[1], null),
                BranchSchema.schemaName(BRANCHES[0], null)), schemas);
        try (DataStore dataStore = DataStoreFactory.forSchema(null, POSTGRES_URL, POSTGRES_USER,
                POSTGRES_PASSWORD, null, schemas.get(0))) {
            assertEquals("/projects/lib", dataStore.readTrackedLibraries().get(LIB).getProjectDir());
        }
    }

    /**
     * Create a branch schema and track a library in it.
     *
     * @param branch the branch whose schema to create
     * @param groupArtifact the coordinate to track
     */
    private static void trackIn(String branch, String groupArtifact) {
        try (DataStore dataStore = DataStoreFactory.fromConfig(null, POSTGRES_URL, POSTGRES_USER,
                POSTGRES_PASSWORD, null, branch, null)) {
            dataStore.persistTrackedLibrary(new TrackedLibrary(groupArtifact, "/projects/lib", null));
        }
    }
}
