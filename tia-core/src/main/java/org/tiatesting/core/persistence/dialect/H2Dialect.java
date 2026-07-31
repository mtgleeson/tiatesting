package org.tiatesting.core.persistence.dialect;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/** H2 dialect - reproduces the SQL H2DataStore emitted before the dialect extraction. */
public final class H2Dialect implements SqlDialect {

    /** {@inheritDoc} H2 uses {@code AUTO_INCREMENT}. */
    @Override public String identityColumnDefinition() { return "BIGINT AUTO_INCREMENT PRIMARY KEY"; }

    /**
     * {@inheritDoc} H2 uses {@code MERGE INTO ... KEY(...) VALUES (...)}.
     */
    @Override
    public String upsert(String table, List<String> columns, List<String> keyColumns) {
        StringBuilder placeholders = new StringBuilder();
        for (int i = 0; i < columns.size(); i++) { placeholders.append(i == 0 ? "?" : ", ?"); }
        return "MERGE INTO " + table + " (" + String.join(", ", columns) + ") KEY("
                + String.join(", ", keyColumns) + ") VALUES (" + placeholders + ")";
    }

    /** {@inheritDoc} H2 folds unquoted identifiers to upper case. */
    @Override
    public boolean tableExists(Connection connection, String tableName) throws SQLException {
        try (ResultSet rs = connection.getMetaData()
                .getTables(null, null, tableName.toUpperCase(), new String[]{"TABLE"})) {
            return rs.next();
        }
    }

    /** {@inheritDoc} */
    @Override public String id() { return "h2"; }

    @Override public String createSchemaIfNotExistsSql(String schema) {
        return "CREATE SCHEMA IF NOT EXISTS \"" + schema + "\"";
    }

    @Override public String selectSchemaSql(String schema) {
        return "SET SCHEMA \"" + schema + "\"";
    }
}
