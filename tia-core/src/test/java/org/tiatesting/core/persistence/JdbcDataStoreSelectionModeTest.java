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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cover the {@code tia_distributed_run.selection_mode} column: every mode round-trips through a
 * plan write. See the "Forced runs and re-seed" chapter in {@code WIKI.md}.
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
        dataStore.getTiaData();
        persistPlan("run-1", SelectionMode.RESEED);

        // when
        DistributedRun read = dataStore.readDistributedRun("run-1");

        // then
        assertEquals(SelectionMode.RESEED, read.getSelectionMode());
        assertTrue(read.isFullRun());
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
}
