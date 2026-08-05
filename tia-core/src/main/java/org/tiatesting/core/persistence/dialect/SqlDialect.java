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
     * Build the statement that removes every row from a table while still rolling back cleanly
     * if the enclosing transaction is later rolled back. Callers use this for clear-and-reinsert
     * writes, where the clear-out and the reinsert must commit or roll back together - the two
     * vendors need different SQL to give that guarantee (see {@link H2Dialect} and
     * {@link PostgresDialect}).
     * @param table the table to clear
     * @return the vendor-specific SQL that clears the table transactionally
     */
    String clearTableTransactionallySql(String table);

    /**
     * Whether a table exists, accounting for the vendor's identifier case folding. The check is
     * scoped to the connection's currently-selected schema (the caller must select the schema
     * before calling this method), so a table of the same name in a different schema is not
     * reported as existing - including a sibling schema whose name merely matches under JDBC
     * {@code _}/{@code %} wildcard expansion (e.g. schema {@code tia_v1_2} vs. {@code tia_v1x2}).
     * Implementations must match the schema and table name exactly, not as a LIKE pattern.
     * @param connection an open connection, with the schema to check already selected
     * @param tableName the unquoted table name as written in the DDL
     * @return true if the table exists in the connection's current schema
     * @throws SQLException on metadata access failure
     */
    boolean tableExists(Connection connection, String tableName) throws SQLException;

    /**
     * The stable dialect id used for the {@code tiaDBDialect} override and error messages.
     * @return the dialect id (e.g. "h2", "postgres")
     */
    String id();

    /**
     * DDL that creates the given schema if it does not already exist.
     * @param schema the (already-sanitised) schema name
     * @return the vendor-specific CREATE SCHEMA IF NOT EXISTS statement
     */
    String createSchemaIfNotExistsSql(String schema);

    /**
     * Statement that makes the given schema the default for subsequent unqualified statements.
     * @param schema the (already-sanitised) schema name
     * @return the vendor-specific schema-selection statement
     */
    String selectSchemaSql(String schema);
}
