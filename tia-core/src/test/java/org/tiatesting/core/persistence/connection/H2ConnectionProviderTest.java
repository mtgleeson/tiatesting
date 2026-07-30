package org.tiatesting.core.persistence.connection;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tiatesting.core.persistence.h2.H2ConnectionSettings;

import java.nio.file.Path;
import java.sql.Connection;

import static org.junit.jupiter.api.Assertions.*;

class H2ConnectionProviderTest {

    @Test
    void opensEmbeddedH2Connection(@TempDir Path dir) throws Exception {
        // given
        H2ConnectionProvider provider = new H2ConnectionProvider(
                H2ConnectionSettings.embedded(dir.toString(), "main"));
        // when
        try (Connection c = provider.get()) {
            // then
            assertTrue(c.isValid(2));
            assertTrue(provider.jdbcUrl().startsWith("jdbc:h2:"));
        }
    }
}
