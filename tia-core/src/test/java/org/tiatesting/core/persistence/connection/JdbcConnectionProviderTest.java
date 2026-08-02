package org.tiatesting.core.persistence.connection;

import org.junit.jupiter.api.Test;

import java.sql.Connection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the generic {@link JdbcConnectionProvider} (used by Postgres and any future non-H2
 * dialect) opens a working connection and produces the same password-free datastore/connection
 * summary {@link H2ConnectionProvider} does, which {@code JdbcDataStore} logs at INFO. The
 * connection path is exercised against an in-memory H2 database (H2's JDBC driver is on the test
 * classpath) so no external server is needed.
 */
class JdbcConnectionProviderTest {

    @Test
    void opensConnectionAndReportsUrl() throws Exception {
        // given
        String url = "jdbc:h2:mem:jdbcprov" + System.nanoTime();
        JdbcConnectionProvider provider = new JdbcConnectionProvider("Postgres", url, "sa", "");

        // when
        try (Connection connection = provider.get()) {
            // then
            assertTrue(connection.isValid(2));
            assertEquals(url, provider.jdbcUrl());
        }
    }

    @Test
    void connectionSummaryReportsDatastoreAndUrlWithoutPassword() {
        // given
        String url = "jdbc:postgresql://localhost:5432/tiadb";
        String password = "s3cr3t-should-not-appear";
        JdbcConnectionProvider provider = new JdbcConnectionProvider("Postgres", url, "tia", password);

        // when
        String summary = provider.connectionSummary();

        // then - names the datastore and the connection URL, and never carries the password
        assertEquals("Postgres as the Tia datastore with the connection: " + url, summary);
        assertFalse(summary.contains(password), "the password must not appear in the summary");
    }
}
