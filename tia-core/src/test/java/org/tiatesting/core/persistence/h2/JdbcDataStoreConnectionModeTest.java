package org.tiatesting.core.persistence.h2;

import org.junit.jupiter.api.Test;
import org.tiatesting.core.persistence.BranchSchema;
import org.tiatesting.core.persistence.JdbcDataStore;
import org.tiatesting.core.persistence.connection.H2ConnectionProvider;
import org.tiatesting.core.persistence.dialect.H2Dialect;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests how the H2 connection stack resolves its JDBC URL and shutdown behaviour per connection
 * mode: embedded composes an engine-option URL (built by {@link H2ConnectionProvider}) against a
 * single fixed {@code tiadb} database and shuts the database down on {@link JdbcDataStore#close()},
 * while server mode uses the supplied URL verbatim and never issues a shutdown. Per-branch
 * isolation is provided by a per-branch schema (see {@link JdbcDataStore} / {@link BranchSchema}),
 * not by a per-branch database name, so neither mode's URL varies with the branch. Since the URL
 * building and the shutdown lifecycle moved out of the datastore into the connection provider, the
 * URL assertions exercise {@link H2ConnectionProvider#jdbcUrl()} directly and the shutdown
 * assertions go through {@link JdbcDataStore#close()}, which delegates to the provider.
 */
class JdbcDataStoreConnectionModeTest {

    @Test
    void embeddedModeComposesUrlWithEngineOptionsAgainstFixedTiadb() {
        // given
        H2ConnectionSettings settings = H2ConnectionSettings.embedded("/var/tia");

        // when
        String url = new H2ConnectionProvider(settings).jdbcUrl();

        // then
        // a single fixed tiadb database - no per-branch file suffix
        assertTrue(url.startsWith("jdbc:h2:/var/tia/tiadb;"), url);
        assertFalse(url.contains("tiadb-"), url);
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
        String url = new H2ConnectionProvider(settings).jdbcUrl();

        // then
        // the URL is used exactly as supplied - no engine options, no branch substitution
        assertEquals(serverUrl, url);
        assertFalse(url.contains("PAGE_SIZE"), url);
        assertFalse(url.contains("DB_CLOSE_DELAY"), url);
    }

    @Test
    void serverModeCloseIsNoOpAndDoesNotConnect() {
        // given
        // an unreachable server URL: if close() tried to open a connection and SHUTDOWN, it
        // would attempt (and fail) a network connect. A no-op close returns without touching it.
        H2ConnectionSettings settings = H2ConnectionSettings.server(
                "jdbc:h2:tcp://127.0.0.1:1/tiadb", "tia", "secret");
        JdbcDataStore dataStore = new JdbcDataStore(new H2Dialect(), new H2ConnectionProvider(settings),
                BranchSchema.schemaName("main"));

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
            JdbcDataStore dataStore = new JdbcDataStore(new H2Dialect(),
                    new H2ConnectionProvider(H2ConnectionSettings.embedded(tempDir.getAbsolutePath())),
                    BranchSchema.schemaName("test"));
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
