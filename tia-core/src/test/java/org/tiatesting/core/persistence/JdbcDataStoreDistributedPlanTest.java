package org.tiatesting.core.persistence;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tiatesting.core.persistence.connection.H2ConnectionProvider;
import org.tiatesting.core.persistence.dialect.H2Dialect;
import org.tiatesting.core.persistence.h2.H2ConnectionSettings;

import java.io.File;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cover the distributed-run schema and the plan read/write operations against embedded H2.
 * Stage 1 adds the tables and the plan store only - claiming, the barrier and sealing arrive in
 * later stages and are not exercised here.
 */
class JdbcDataStoreDistributedPlanTest {

    private JdbcDataStore dataStore;
    private File tempDir;

    /**
     * Create a fresh embedded H2 database in a new temp directory and bootstrap its schema, so
     * each test starts from an isolated store with no distributed runs planned.
     *
     * @throws Exception if the temp directory cannot be created or schema bootstrap fails
     */
    @BeforeEach
    void setUp() throws Exception {
        tempDir = File.createTempFile("tia-distributed-", "");
        tempDir.delete();
        tempDir.mkdirs();
        dataStore = new JdbcDataStore(new H2Dialect(),
                new H2ConnectionProvider(H2ConnectionSettings.embedded(tempDir.getAbsolutePath())),
                BranchSchema.schemaName("test"));
        dataStore.getTiaData(true);
    }

    /**
     * Close the data store so its embedded H2 database releases its file lock.
     */
    @AfterEach
    void tearDown() {
        if (dataStore != null) {
            dataStore.close();
        }
    }

    /**
     * Count the rows in a table, so a test can assert what schema bootstrap created without
     * depending on the plan operations that arrive in the next task.
     *
     * @param table the table to count
     * @return the number of rows
     * @throws Exception if the query fails
     */
    private long countRows(String table) throws Exception {
        try (Connection connection = dataStore.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            assertTrue(rs.next());
            return rs.getLong(1);
        }
    }

    @Test
    void shouldCreateAllFourDistributedTablesOnSchemaBootstrap() throws Exception {
        // given
        // setUp bootstrapped the schema

        // when
        long runs = countRows("tia_distributed_run");
        long groups = countRows("tia_distributed_run_group");
        long groupSuites = countRows("tia_distributed_run_group_suite");
        long methodStage = countRows("tia_distributed_run_method_stage");

        // then
        assertEquals(0L, runs);
        assertEquals(0L, groups);
        assertEquals(0L, groupSuites);
        assertEquals(0L, methodStage);
    }
}
