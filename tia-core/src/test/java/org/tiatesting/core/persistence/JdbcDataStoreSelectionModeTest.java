package org.tiatesting.core.persistence;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tiatesting.core.model.DistributedRun;
import org.tiatesting.core.model.DistributedRunGroup;
import org.tiatesting.core.model.DistributedRunPlan;
import org.tiatesting.core.model.SelectionMode;
import org.tiatesting.core.persistence.connection.H2ConnectionProvider;
import org.tiatesting.core.persistence.dialect.H2Dialect;
import org.tiatesting.core.persistence.h2.H2ConnectionSettings;

import java.io.File;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cover the {@code tia_distributed_run.selection_mode} column: every mode round-trips through a
 * plan write, and a run table created with the {@code seed_run} column it replaced is migrated in
 * place. See the "Forced runs and re-seed" chapter in {@code WIKI.md}.
 */
class JdbcDataStoreSelectionModeTest {

    private static final String SCHEMA = BranchSchema.schemaName("test", null);

    private File tempDir;
    private JdbcDataStore dataStore;

    /**
     * Create the temp directory the embedded database lives in; each test opens its own store.
     *
     * @throws Exception if the temp directory cannot be created
     */
    @BeforeEach
    void setUp() throws Exception {
        tempDir = File.createTempFile("tia-selection-mode-", "");
        tempDir.delete();
        tempDir.mkdirs();
    }

    /**
     * Close the store and remove the temp directory.
     */
    @AfterEach
    void tearDown() {
        if (dataStore != null) {
            dataStore.close();
        }
        for (File f : tempDir.listFiles()) {
            f.delete();
        }
        tempDir.delete();
    }

    /**
     * A plan's selection mode round-trips through the run row.
     */
    @Test
    void theRunRowRoundTripsItsSelectionMode() {
        // given
        dataStore = openStore();
        dataStore.getTiaData(true);
        persistPlan("run-1", SelectionMode.RESEED);

        // when
        DistributedRun read = dataStore.readDistributedRun("run-1");

        // then
        assertEquals(SelectionMode.RESEED, read.getSelectionMode());
        assertTrue(read.isFullRun());
    }

    /**
     * A run table created with the old seed_run column gains selection_mode and loses seed_run.
     */
    @Test
    void aRunTableWithTheOldSeedRunColumnIsMigrated() throws Exception {
        // given - a run table created before selection_mode existed
        try (Connection connection = new H2ConnectionProvider(
                H2ConnectionSettings.embedded(tempDir.getAbsolutePath())).get();
             Statement statement = connection.createStatement()) {
            statement.execute(new H2Dialect().createSchemaIfNotExistsSql(SCHEMA));
            statement.execute(new H2Dialect().selectSchemaSql(SCHEMA));
            statement.execute("CREATE TABLE tia_distributed_run (run_id VARCHAR(255) NOT NULL PRIMARY KEY, "
                    + "branch VARCHAR(255) NOT NULL, commit_value VARCHAR(255) NOT NULL, "
                    + "status VARCHAR(16) NOT NULL, group_count INT NOT NULL, target_run_time_ms BIGINT, "
                    + "estimated_total_ms BIGINT NOT NULL, created_at BIGINT NOT NULL, "
                    + "sealed_by VARCHAR(255), sealed_at BIGINT, drain_result BLOB, "
                    + "seed_run BOOLEAN DEFAULT FALSE, groups_available INT, run_source VARCHAR(32))");
        }
        dataStore = openStore();
        dataStore.getTiaData(true);

        // when
        persistPlan("run-2", SelectionMode.SELECT_ALL);
        DistributedRun read = dataStore.readDistributedRun("run-2");

        // then
        assertEquals(SelectionMode.SELECT_ALL, read.getSelectionMode());
        assertTrue(columnExists("SELECTION_MODE"));
        assertFalse(columnExists("SEED_RUN"));
    }

    /**
     * @return an embedded H2 store in this test's temp directory
     */
    private JdbcDataStore openStore() {
        return new JdbcDataStore(new H2Dialect(),
                new H2ConnectionProvider(H2ConnectionSettings.embedded(tempDir.getAbsolutePath())),
                SCHEMA);
    }

    /**
     * Persist a one-group plan with one suite under the given mode.
     *
     * @param runId the run identifier
     * @param mode the selection mode to record
     */
    private void persistPlan(String runId, SelectionMode mode) {
        List<DistributedRunGroup> groups = new ArrayList<>();
        groups.add(DistributedRunGroup.pending(runId, 0, 1000L));
        Map<Integer, List<String>> suites = new HashMap<>();
        suites.put(0, Collections.singletonList("com.example.ATest"));
        DistributedRun run = DistributedRun.open(runId, "main", "commit-1", 1, 1, null, 1000L, 1234L,
                mode, null);
        dataStore.persistDistributedRunPlan(new DistributedRunPlan(run, groups, suites, null));
    }

    /**
     * Check whether the run table still has a column, through the store's own connection settings.
     *
     * @param column the upper-case column name
     * @return true when the column exists
     * @throws Exception if the metadata read fails
     */
    private boolean columnExists(String column) throws Exception {
        try (Connection connection = new H2ConnectionProvider(
                H2ConnectionSettings.embedded(tempDir.getAbsolutePath())).get();
             ResultSet rs = connection.getMetaData().getColumns(null, SCHEMA,
                     "TIA_DISTRIBUTED_RUN", column)) {
            return rs.next();
        }
    }
}
