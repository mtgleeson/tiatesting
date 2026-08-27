package org.tiatesting.core.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests {@link DataStoreFactory}: the H2-only happy path (null URL resolves to H2 embedded mode),
 * the unsupported-dialect error path, whose message must name the supported dialect so a
 * misconfigured JDBC URL fails with an actionable error, and {@link
 * DataStoreFactory#isSharedDatabase(String, String)}, which distributed test runs rely on to
 * reject an embedded-mode datastore no other runner could see.
 */
class DataStoreFactoryTest {

    @Test
    void buildsH2StoreForNullUrl(@TempDir Path dir) {
        // given / when
        DataStore store = DataStoreFactory.fromConfig(dir.toString(), null, "tia", "", null, "main");
        // then
        assertNotNull(store);
        assertTrue(store instanceof JdbcDataStore);
    }

    @Test
    void unknownUrlSchemeThrowsWithSupportedList() {
        // given / when / then
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> DataStoreFactory.fromConfig(null, "jdbc:oracle:thin:@x", "u", "p", null, "main"));
        assertTrue(ex.getMessage().toLowerCase().contains("h2"));
    }

    /**
     * Verifies that a blank {@code dbUrl} with no dialect override - the embedded-H2 configuration
     * - is reported as not shared, since embedded H2 gives each runner its own private file on
     * disk that no other runner can see.
     */
    @Test
    void blankUrlWithNoDialectOverrideIsNotShared() {
        // given / when
        boolean shared = DataStoreFactory.isSharedDatabase(null, null);
        // then
        assertFalse(shared);
    }

    /**
     * Verifies that a blank (whitespace-only) {@code dbUrl} with no dialect override is also
     * reported as not shared, matching the blank-checking already used by {@link
     * DataStoreFactory#fromConfig}.
     */
    @Test
    void blankWhitespaceUrlWithNoDialectOverrideIsNotShared() {
        // given / when
        boolean shared = DataStoreFactory.isSharedDatabase("   ", null);
        // then
        assertFalse(shared);
    }

    /**
     * Verifies that an H2 server-mode URL (a {@code jdbc:h2:tcp://} URL, not blank) is reported as
     * shared, since server-mode H2 is a database every runner can reach concurrently.
     */
    @Test
    void h2ServerUrlIsShared() {
        // given / when
        boolean shared = DataStoreFactory.isSharedDatabase("jdbc:h2:tcp://h2host:9092/tiadb", null);
        // then
        assertTrue(shared);
    }

    /**
     * Verifies that a Postgres URL is reported as shared, since Postgres is always a networked
     * database every runner can reach concurrently.
     */
    @Test
    void postgresUrlIsShared() {
        // given / when
        boolean shared = DataStoreFactory.isSharedDatabase("jdbc:postgresql://localhost:5432/tiaperf", null);
        // then
        assertTrue(shared);
    }

    /**
     * Verifies that a blank {@code dbUrl} combined with an explicit non-H2 dialect override is
     * still reported as shared: the resolved dialect is not H2, so the embedded-H2 exception does
     * not apply even though the URL itself is blank.
     */
    @Test
    void blankUrlWithNonH2DialectOverrideIsShared() {
        // given / when
        boolean shared = DataStoreFactory.isSharedDatabase(null, "postgres");
        // then
        assertTrue(shared);
    }
}
