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
 * mode: embedded composes an engine-option URL (built by {@link H2ConnectionProvider}) and shuts
 * the database down on {@link JdbcDataStore#close()}, while server mode uses the supplied URL
 * verbatim and never issues a shutdown. Since the URL building and the shutdown lifecycle moved
 * out of the datastore into the connection provider, the URL assertions exercise
 * {@link H2ConnectionProvider#jdbcUrl()} directly and the shutdown assertions go through
 * {@link JdbcDataStore#close()}, which delegates to the provider.
 */
class JdbcDataStoreConnectionModeTest {

    @Test
    void embeddedModeComposesUrlWithEngineOptionsAndBranchSuffix() {
        // given
        H2ConnectionSettings settings = H2ConnectionSettings.embedded("/var/tia", "main");

        // when
        String url = new H2ConnectionProvider(settings).jdbcUrl();

        // then
        assertTrue(url.startsWith("jdbc:h2:/var/tia/tiadb-main"), url);
        assertTrue(url.contains(";PAGE_SIZE="), url);
        assertTrue(url.contains(";CACHE_SIZE="), url);
        assertTrue(url.contains(";DB_CLOSE_DELAY=-1"), url);
        assertTrue(url.contains(";DB_CLOSE_ON_EXIT=FALSE"), url);
    }

    @Test
    void embeddedModeSanitizesBranchSlashesInFileName() {
        // given
        // the branch name is now the short VCS name, so a nested branch keeps its slash; it must
        // not leak into the on-disk file name as a path separator
        H2ConnectionSettings settings = H2ConnectionSettings.embedded("/var/tia", "feature/foo");

        // when
        String url = new H2ConnectionProvider(settings).jdbcUrl();

        // then
        assertTrue(url.startsWith("jdbc:h2:/var/tia/tiadb-feature-foo"), url);
    }

    @Test
    void serverModeUsesSuppliedUrlVerbatimWithNoEngineOptions() {
        // given
        String serverUrl = "jdbc:h2:tcp://h2host:9092/tiadb";
        H2ConnectionSettings settings = H2ConnectionSettings.server(serverUrl, "tia", "secret", "main");

        // when
        String url = new H2ConnectionProvider(settings).jdbcUrl();

        // then
        // no {branch} token, so the URL is used exactly as supplied - the branch is ignored
        assertEquals(serverUrl, url);
        assertFalse(url.contains("PAGE_SIZE"), url);
        assertFalse(url.contains("DB_CLOSE_DELAY"), url);
        assertFalse(url.contains("tiadb-"), url);
    }

    @Test
    void serverModeExpandsBranchPlaceholderToBranchDbName() {
        // given
        String serverUrl = "jdbc:h2:tcp://h2host:9092/" + H2ConnectionSettings.BRANCH_PLACEHOLDER;
        H2ConnectionSettings settings = H2ConnectionSettings.server(serverUrl, "tia", "secret", "main");

        // when
        String url = new H2ConnectionProvider(settings).jdbcUrl();

        // then
        assertEquals("jdbc:h2:tcp://h2host:9092/tiadb-main", url);
        assertFalse(url.contains(H2ConnectionSettings.BRANCH_PLACEHOLDER), url);
    }

    @Test
    void serverModeReplacesOnlyTheTokenPreservingSurroundingText() {
        // given
        // only the {branch} token is replaced, so a user-supplied suffix (or prefix) is preserved
        String serverUrl = "jdbc:h2:tcp://h2host:9092/" + H2ConnectionSettings.BRANCH_PLACEHOLDER + "-myproject";
        H2ConnectionSettings settings = H2ConnectionSettings.server(serverUrl, "tia", "secret", "main");

        // when
        String url = new H2ConnectionProvider(settings).jdbcUrl();

        // then
        assertEquals("jdbc:h2:tcp://h2host:9092/tiadb-main-myproject", url);
    }

    @Test
    void serverModeSanitizesBranchSlashesInExpandedDbName() {
        // given
        // a branch like feature/foo would otherwise be read as a nested path in the H2 db name
        String serverUrl = "jdbc:h2:tcp://h2host:9092/" + H2ConnectionSettings.BRANCH_PLACEHOLDER;
        H2ConnectionSettings settings = H2ConnectionSettings.server(serverUrl, "tia", "secret", "feature/foo");

        // when
        String url = new H2ConnectionProvider(settings).jdbcUrl();

        // then
        assertEquals("jdbc:h2:tcp://h2host:9092/tiadb-feature-foo", url);
    }

    @Test
    void serverModeCloseIsNoOpAndDoesNotConnect() {
        // given
        // an unreachable server URL: if close() tried to open a connection and SHUTDOWN, it
        // would attempt (and fail) a network connect. A no-op close returns without touching it.
        H2ConnectionSettings settings = H2ConnectionSettings.server(
                "jdbc:h2:tcp://127.0.0.1:1/tiadb", "tia", "secret", "main");
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
                    new H2ConnectionProvider(H2ConnectionSettings.embedded(tempDir.getAbsolutePath(), "test")),
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
