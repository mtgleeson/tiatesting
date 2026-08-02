package org.tiatesting.core.persistence;

import org.junit.jupiter.api.Test;
import org.tiatesting.core.persistence.connection.ConnectionProvider;
import org.tiatesting.core.persistence.dialect.H2Dialect;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that constructing a {@link JdbcDataStore} logs a single INFO line combining the
 * connection provider's password-free summary with the per-branch schema. slf4j-simple (the test
 * runtime) logs INFO to {@code System.err} by default, so the line is captured there. Uses a stub
 * connection provider so no real database is opened - the datastore only logs at construction.
 */
class JdbcDataStoreConnectionLogTest {

    /** Stub provider returning fixed logging inputs; {@link #get()} is never called by construction. */
    private static class StubConnectionProvider implements ConnectionProvider {
        private final String summary;

        StubConnectionProvider(String summary) {
            this.summary = summary;
        }

        @Override
        public Connection get() throws SQLException {
            throw new SQLException("not used in this test");
        }

        @Override
        public String jdbcUrl() {
            return "jdbc:stub://host/tiadb";
        }

        @Override
        public String connectionSummary() {
            return summary;
        }
    }

    @Test
    void logsConnectionSummaryAndSchemaOnConstruction() throws Exception {
        // given
        String summary = "Postgres as the Tia datastore with the connection: jdbc:postgresql://localhost:5432/tiadb";
        String schema = "tia_feature_x";
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8.name()));

        // when
        try {
            new JdbcDataStore(new H2Dialect(), new StubConnectionProvider(summary), schema);
        } finally {
            System.setErr(originalErr);
        }

        // then - one INFO line carries both the connection summary and the schema
        String log = new String(captured.toByteArray(), StandardCharsets.UTF_8);
        assertTrue(log.contains("Using " + summary + ", with schema '" + schema + "'."),
                "expected the datastore-connection INFO line with schema, got: " + log);
    }

    @Test
    void logsDefaultSchemaWhenSchemaBlank() throws Exception {
        // given
        String summary = "H2 as the Tia datastore in embedded mode with the connection: jdbc:h2:/tmp/tiadb";
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8.name()));

        // when
        try {
            new JdbcDataStore(new H2Dialect(), new StubConnectionProvider(summary), "   ");
        } finally {
            System.setErr(originalErr);
        }

        // then - a blank schema is reported as the connection's default schema, not "schema ''"
        String log = new String(captured.toByteArray(), StandardCharsets.UTF_8);
        assertTrue(log.contains("Using " + summary + ", with the connection's default schema."),
                "expected the default-schema INFO line, got: " + log);
        assertFalse(log.contains("schema ''"), "a blank schema must not render as empty quotes");
    }
}
