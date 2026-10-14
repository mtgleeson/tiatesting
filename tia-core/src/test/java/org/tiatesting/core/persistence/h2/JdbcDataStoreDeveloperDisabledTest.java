package org.tiatesting.core.persistence.h2;

import org.tiatesting.core.persistence.BranchSchema;
import org.tiatesting.core.persistence.JdbcDataStore;
import org.tiatesting.core.persistence.dialect.H2Dialect;
import org.tiatesting.core.persistence.connection.H2ConnectionProvider;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tiatesting.core.model.TestSuiteTracker;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the persistence of the per-suite {@code developerDisabled} flag in {@link JdbcDataStore}:
 * a round-trip through {@code persistTestSuites} / {@code getTestSuitesTracked}. Uses a temp-directory embedded H2 database per test.
 */
class JdbcDataStoreDeveloperDisabledTest {

    private JdbcDataStore dataStore;
    private H2ConnectionSettings settings;
    private File tempDir;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = File.createTempFile("tia-test-", "");
        tempDir.delete();
        tempDir.mkdirs();
        settings = H2ConnectionSettings.embedded(tempDir.getAbsolutePath());
        dataStore = new JdbcDataStore(new H2Dialect(), new H2ConnectionProvider(settings), BranchSchema.schemaName("test", null));
    }

    @AfterEach
    void tearDown() {
        dataStore.close();
        if (tempDir != null && tempDir.exists()) {
            for (File f : tempDir.listFiles()) {
                f.delete();
            }
            tempDir.delete();
        }
    }

    /**
     * A suite persisted with {@code developerDisabled = true} reads back disabled, and a suite
     * persisted without the flag reads back enabled.
     */
    @Test
    void persistAndLoad_developerDisabledFlag_roundTrips() {
        // given
        dataStore.getTiaData(); // bootstrap schema
        TestSuiteTracker disabled = new TestSuiteTracker("com.example.FooTest");
        disabled.setDeveloperDisabled(true);
        TestSuiteTracker enabled = new TestSuiteTracker("com.example.BarTest");

        Map<String, TestSuiteTracker> suites = new HashMap<>();
        suites.put(disabled.getName(), disabled);
        suites.put(enabled.getName(), enabled);

        // when
        dataStore.persistTestSuites(suites);
        Map<String, TestSuiteTracker> loaded = dataStore.getTestSuitesTracked();

        // then
        assertTrue(loaded.get("com.example.FooTest").isDeveloperDisabled());
        assertFalse(loaded.get("com.example.BarTest").isDeveloperDisabled());
    }
}
