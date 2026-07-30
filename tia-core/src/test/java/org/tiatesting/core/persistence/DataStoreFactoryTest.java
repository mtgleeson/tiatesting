package org.tiatesting.core.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests {@link DataStoreFactory}: the H2-only happy path (null URL resolves to H2 embedded mode)
 * and the unsupported-dialect error path, whose message must name the supported dialect so a
 * misconfigured JDBC URL fails with an actionable error.
 */
class DataStoreFactoryTest {

    @Test
    void buildsH2StoreForNullUrl(@TempDir Path dir) {
        // given / when
        DataStore store = DataStoreFactory.fromConfig(dir.toString(), null, "sa", "", null, "main");
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
}
