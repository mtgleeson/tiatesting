package org.tiatesting.core.persistence;

import org.tiatesting.core.persistence.connection.ConnectionProvider;
import org.tiatesting.core.persistence.dialect.SqlDialect;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import static org.tiatesting.core.persistence.TiaSchema.COL_GROUP_ARTIFACT;
import static org.tiatesting.core.persistence.TiaSchema.TABLE_TIA_LIBRARY;

/**
 * Finds the schemas that consume a library: those holding a {@code tia_library} row for its
 * coordinate. The row is written by the consumer's own reconcile, so the set is exactly the
 * consumers a publish stamp can reach - no declared list, and nothing to forget. See the
 * consumer-discovery section of the library publish-time stamping chapter in {@code WIKI.md}.
 *
 * <p>Read-only: it never creates a schema or a table, so a schema that merely shares Tia's name
 * prefix is left untouched.
 */
final class ConsumerSchemaDiscovery {

    private ConsumerSchemaDiscovery() {
    }

    /**
     * List the schemas tracking a library coordinate.
     *
     * @param dialect the SQL dialect of the datastore
     * @param connections opens a connection with no schema selected
     * @param groupArtifact the library's {@code groupId:artifactId}
     * @return the tracking schemas' names, sorted; empty when none tracks the coordinate
     * @throws TiaPersistenceException if the datastore cannot be read
     */
    static List<String> findSchemasTracking(final SqlDialect dialect, final ConnectionProvider connections,
                                            final String groupArtifact) {
        List<String> tracking = new ArrayList<>();
        try (Connection connection = connections.get()) {
            for (String schema : listTiaSchemas(connection)) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute(dialect.selectSchemaSql(schema));
                }
                if (dialect.tableExists(connection, TABLE_TIA_LIBRARY) && tracks(connection, groupArtifact)) {
                    tracking.add(schema);
                }
            }
        } catch (SQLException e) {
            throw new TiaPersistenceException(e);
        }
        Collections.sort(tracking);
        return tracking;
    }

    /**
     * List the schemas carrying Tia's name prefix.
     *
     * @param connection an open connection
     * @return the candidate schema names, as the database reports them
     * @throws SQLException if the metadata read fails
     */
    private static List<String> listTiaSchemas(final Connection connection) throws SQLException {
        List<String> schemas = new ArrayList<>();
        try (ResultSet resultSet = connection.getMetaData().getSchemas()) {
            while (resultSet.next()) {
                String schema = resultSet.getString("TABLE_SCHEM");
                if (schema != null && schema.toLowerCase(Locale.ROOT).startsWith(BranchSchema.SCHEMA_PREFIX)) {
                    schemas.add(schema);
                }
            }
        }
        return schemas;
    }

    /**
     * Report whether the connection's selected schema tracks the coordinate.
     *
     * @param connection a connection with the candidate schema selected
     * @param groupArtifact the library's {@code groupId:artifactId}
     * @return true when the schema's {@code tia_library} table holds the coordinate
     * @throws SQLException if the query fails
     */
    private static boolean tracks(final Connection connection, final String groupArtifact) throws SQLException {
        String sql = "SELECT 1 FROM " + TABLE_TIA_LIBRARY + " WHERE " + COL_GROUP_ARTIFACT + " = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, groupArtifact);
            try (ResultSet resultSet = ps.executeQuery()) {
                return resultSet.next();
            }
        }
    }
}
