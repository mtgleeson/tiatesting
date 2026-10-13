package org.tiatesting.core.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tiatesting.core.persistence.connection.H2ConnectionProvider;
import org.tiatesting.core.persistence.h2.H2ConnectionSettings;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the stateless {@link JdbcRows} helpers.
 */
class JdbcRowsTest {

    @Test
    void buildMultiRowInsertSqlRepeatsOneTuplePerRow() {
        // given a two-column table and three rows

        // when the multi-row insert is built
        String sql = JdbcRows.buildMultiRowInsertSql("t", "a, b", 2, 3);

        // then it carries one placeholder tuple per row
        assertEquals("INSERT INTO t (a, b) VALUES (?, ?),(?, ?),(?, ?)", sql);
    }

    @Test
    void nullableBindersAndReadersRoundTripNullAndValues(@TempDir Path dir) throws Exception {
        // given a table with nullable long, int and string columns
        H2ConnectionProvider provider = new H2ConnectionProvider(H2ConnectionSettings.embedded(dir.toString()));
        try (Connection connection = provider.get()) {
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("CREATE TABLE t (k INT, l BIGINT, i INT, s VARCHAR)");
            }

            // when one row binds nulls and another binds values through the nullable binders
            try (PreparedStatement ps = connection.prepareStatement("INSERT INTO t VALUES (?, ?, ?, ?)")) {
                ps.setInt(1, 1);
                JdbcRows.setNullableLong(ps, 2, null);
                JdbcRows.setNullableInt(ps, 3, null);
                JdbcRows.setNullableString(ps, 4, null);
                ps.executeUpdate();
                ps.setInt(1, 2);
                JdbcRows.setNullableLong(ps, 2, 0L);
                JdbcRows.setNullableInt(ps, 3, 7);
                JdbcRows.setNullableString(ps, 4, "x");
                ps.executeUpdate();
            }

            // then the readers tell SQL NULL apart from zero
            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery("SELECT * FROM t ORDER BY k")) {
                assertTrue(rs.next());
                assertNull(JdbcRows.getNullableLong(rs, "l"));
                assertNull(JdbcRows.getNullableInt(rs, "i"));
                assertNull(rs.getString("s"));
                assertTrue(rs.next());
                assertEquals(0L, JdbcRows.getNullableLong(rs, "l"));
                assertEquals(7, JdbcRows.getNullableInt(rs, "i"));
                assertEquals("x", rs.getString("s"));
            }
        } finally {
            provider.close();
        }
    }
}
