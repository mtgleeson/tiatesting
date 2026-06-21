package org.tiatesting.core.persistence.h2;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tiatesting.core.model.TestSuiteTracker;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the persistence of the per-suite {@code developerDisabled} flag in {@link H2DataStore}:
 * a round-trip through {@code persistTestSuites} / {@code getTestSuitesTracked}, and the
 * migration that adds the {@code developer_disabled} column to a DB created without it (old rows
 * read back as {@code false}). Uses a temp-directory embedded H2 database per test.
 */
class H2DataStoreDeveloperDisabledTest {

    private H2DataStore dataStore;
    private H2ConnectionSettings settings;
    private File tempDir;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = File.createTempFile("tia-test-", "");
        tempDir.delete();
        tempDir.mkdirs();
        settings = H2ConnectionSettings.embedded(tempDir.getAbsolutePath(), "test");
        dataStore = new H2DataStore(settings);
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
        dataStore.getTiaData(true); // bootstrap schema
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

    /**
     * A DB whose {@code tia_test_suite} table predates the {@code developer_disabled} column gains
     * it via migration on next contact, and the pre-existing row reads back as not disabled.
     */
    @Test
    void migration_addsDeveloperDisabledColumn_oldRowReadsFalse() throws Exception {
        // given - seed a suite, then drop the column to simulate a pre-migration DB. The engine
        // is kept alive for the JVM (DB_CLOSE_DELAY=-1), so the drop is visible to a fresh
        // datastore opened against the same file without closing this one.
        dataStore.getTiaData(true);
        TestSuiteTracker suite = new TestSuiteTracker("com.example.LegacyTest");
        Map<String, TestSuiteTracker> suites = new HashMap<>();
        suites.put(suite.getName(), suite);
        dataStore.persistTestSuites(suites);

        try (Connection connection = DriverManager.getConnection(dataStore.getJdbcUrl(),
                settings.getUsername(), settings.getPassword());
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("ALTER TABLE tia_test_suite DROP COLUMN developer_disabled");
        }

        // when - a fresh datastore re-runs ensureSchema (via getTiaData), which must re-add the column
        H2DataStore migrated = new H2DataStore(settings);
        Map<String, TestSuiteTracker> loaded = migrated.getTiaData(true).getTestSuitesTracked();
        migrated.close();

        // then
        assertFalse(loaded.get("com.example.LegacyTest").isDeveloperDisabled());
    }
}
