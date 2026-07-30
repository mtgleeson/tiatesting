package org.tiatesting.core.persistence.dialect;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/** Vendor-specific SQL for the Tia JDBC datastore. See the pluggable-datastore WIKI chapter. */
public interface SqlDialect {

    /**
     * The DDL fragment for an auto-incrementing BIGINT primary-key column.
     * @return the vendor-specific identity column definition
     */
    String identityColumnDefinition();

    /**
     * Build a parameterised upsert (insert-or-update) statement.
     * @param table the target table
     * @param columns all inserted columns, in bind order
     * @param keyColumns the conflict/merge key columns (subset of columns)
     * @return the vendor-specific upsert SQL with one {@code ?} per column
     */
    String upsert(String table, List<String> columns, List<String> keyColumns);

    /**
     * Whether a table exists, accounting for the vendor's identifier case folding.
     * @param connection an open connection
     * @param tableName the unquoted table name as written in the DDL
     * @return true if the table exists
     * @throws SQLException on metadata access failure
     */
    boolean tableExists(Connection connection, String tableName) throws SQLException;

    /**
     * The stable dialect id used for the {@code tiaDBDialect} override and error messages.
     * @return the dialect id (e.g. "h2", "postgres")
     */
    String id();
}
