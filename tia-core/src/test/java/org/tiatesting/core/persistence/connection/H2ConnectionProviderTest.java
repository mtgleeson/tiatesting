package org.tiatesting.core.persistence.connection;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tiatesting.core.persistence.h2.H2ConnectionSettings;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

class H2ConnectionProviderTest {

    @Test
    void opensEmbeddedH2Connection(@TempDir Path dir) throws Exception {
        // given
        H2ConnectionProvider provider = new H2ConnectionProvider(
                H2ConnectionSettings.embedded(dir.toString()));
        // when
        try (Connection c = provider.get()) {
            // then
            assertTrue(c.isValid(2));
            assertTrue(provider.jdbcUrl().startsWith("jdbc:h2:"));
        }
    }

    @Test
    void anEmbeddedDatabaseCompactsOnCloseByDefault(@TempDir Path dir) {
        // given
        H2ConnectionSettings settings = H2ConnectionSettings.embedded(dir.toString());

        // when
        String url = new H2ConnectionProvider(settings).jdbcUrl();

        // then
        assertTrue(settings.isCompactOnClose());
        assertFalse(url.contains("MAX_COMPACT_TIME"), url);
    }

    @Test
    void switchingOffCloseCompactionSetsTheEngineOption(@TempDir Path dir) {
        // given
        H2ConnectionSettings settings = H2ConnectionSettings.embedded(dir.toString()).withoutCloseCompaction();

        // when
        String url = new H2ConnectionProvider(settings).jdbcUrl();

        // then
        assertFalse(settings.isCompactOnClose());
        assertTrue(url.endsWith(";MAX_COMPACT_TIME=0"), url);
    }

    @Test
    void aServerUrlIsUsedVerbatimEvenWithoutCloseCompaction() {
        // given the server owns its engine settings
        String serverUrl = "jdbc:h2:tcp://localhost:9092/tiadb";
        H2ConnectionSettings settings = H2ConnectionSettings.server(serverUrl, "tia", "").withoutCloseCompaction();

        // when
        String url = new H2ConnectionProvider(settings).jdbcUrl();

        // then
        assertEquals(serverUrl, url);
    }

    @Test
    void aCloseWithoutCompactionStillFlushesEveryWrite(@TempDir Path dir) throws Exception {
        // given a write made by a JVM that skips close-time compaction, then closed
        H2ConnectionProvider writer = new H2ConnectionProvider(
                H2ConnectionSettings.embedded(dir.toString()).withoutCloseCompaction());
        try (Connection c = writer.get(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE t (v INT)");
            s.execute("INSERT INTO t VALUES (42)");
        }
        writer.close();

        // when the file is opened again
        H2ConnectionProvider reader = new H2ConnectionProvider(H2ConnectionSettings.embedded(dir.toString()));
        try (Connection c = reader.get(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT v FROM t")) {
            // then the write is there
            assertTrue(rs.next());
            assertEquals(42, rs.getInt(1));
        } finally {
            reader.close();
        }
    }
}
