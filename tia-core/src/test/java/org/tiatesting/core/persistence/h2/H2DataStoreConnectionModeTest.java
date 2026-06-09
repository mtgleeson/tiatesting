package org.tiatesting.core.persistence.h2;

import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests how {@link H2DataStore} resolves its JDBC URL and shutdown behaviour per connection
 * mode: embedded composes an engine-option URL and shuts the database down on close, while
 * server mode uses the supplied URL verbatim and never issues a shutdown.
 */
class H2DataStoreConnectionModeTest {

    @Test
    void embeddedModeComposesUrlWithEngineOptionsAndBranchSuffix() {
        // given
        H2ConnectionSettings settings = H2ConnectionSettings.embedded("/var/tia", "main");

        // when
        H2DataStore dataStore = new H2DataStore(settings);

        // then
        String url = dataStore.getJdbcUrl();
        assertTrue(url.startsWith("jdbc:h2:/var/tia/tiadb-main"), url);
        assertTrue(url.contains(";PAGE_SIZE="), url);
        assertTrue(url.contains(";CACHE_SIZE="), url);
        assertTrue(url.contains(";DB_CLOSE_DELAY=-1"), url);
        assertTrue(url.contains(";DB_CLOSE_ON_EXIT=FALSE"), url);
    }

    @Test
    void serverModeUsesSuppliedUrlVerbatimWithNoEngineOptions() {
        // given
        String serverUrl = "jdbc:h2:tcp://h2host:9092/tiadb";
        H2ConnectionSettings settings = H2ConnectionSettings.server(serverUrl, "tia", "secret");

        // when
        H2DataStore dataStore = new H2DataStore(settings);

        // then
        String url = dataStore.getJdbcUrl();
        assertEquals(serverUrl, url);
        assertFalse(url.contains("PAGE_SIZE"), url);
        assertFalse(url.contains("DB_CLOSE_DELAY"), url);
        assertFalse(url.contains("tiadb-"), url);
    }

    @Test
    void serverModeCloseIsNoOpAndDoesNotConnect() {
        // given
        // an unreachable server URL: if close() tried to open a connection and SHUTDOWN, it
        // would attempt (and fail) a network connect. A no-op close returns without touching it.
        H2ConnectionSettings settings = H2ConnectionSettings.server(
                "jdbc:h2:tcp://127.0.0.1:1/tiadb", "tia", "secret");
        H2DataStore dataStore = new H2DataStore(settings);

        // when / then
        assertDoesNotThrow(dataStore::close);
    }

    @Test
    void embeddedModeCloseShutsDownWithoutThrowing() throws Exception {
        // given
        File tempDir = File.createTempFile("tia-test-", "");
        tempDir.delete();
        tempDir.mkdirs();
        try {
            H2DataStore dataStore = new H2DataStore(
                    H2ConnectionSettings.embedded(tempDir.getAbsolutePath(), "test"));
            dataStore.getTiaData(true); // force schema creation / open the DB

            // when / then
            assertDoesNotThrow(dataStore::close);
        } finally {
            if (tempDir.listFiles() != null) {
                for (File f : tempDir.listFiles()) {
                    f.delete();
                }
            }
            tempDir.delete();
        }
    }
}
