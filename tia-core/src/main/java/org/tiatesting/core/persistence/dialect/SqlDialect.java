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
     * The DDL type name for a variable-length binary column. The two vendors share no spelling:
     * H2 has {@code BLOB} and no {@code BYTEA}, Postgres has {@code BYTEA} and neither
     * {@code BLOB} nor {@code VARBINARY}.
     *
     * @return the vendor-specific binary column type name
     */
    String binaryColumnType();

    /**
     * Build a parameterised upsert (insert-or-update) statement.
     * @param table the target table
     * @param columns all inserted columns, in bind order
     * @param keyColumns the conflict/merge key columns (subset of columns)
     * @return the vendor-specific upsert SQL with one {@code ?} per column
     */
    String upsert(String table, List<String> columns, List<String> keyColumns);

    /**
     * Build a parameterised upsert for a test-suite row whose run stats <em>accumulate</em> onto
     * whatever the row already holds, rather than replacing it.
     *
     * <p>This exists because the ordinary {@link #upsert(String, List, List)} cannot express it on
     * either vendor: it writes the values the caller computed, so a caller that merged the new
     * figures onto a snapshot it read earlier overwrites any increment that landed in between. The
     * stats are a running total several builds contribute to, so the addition has to happen at write
     * time, against the row's current values. See the "Persist flow and crash safety" chapter in
     * {@code WIKI.md}.
     *
     * <p>The run time is a weighted mean rather than a sum - {@code (stored_runs * stored_avg +
     * new_runs * new_avg) / (stored_runs + new_runs)} - which is the same arithmetic
     * {@code TestStats.incrementStats} performs in memory. It is guarded for a zero denominator, so
     * a write that contributes no run (a retry, or a row written only because its
     * developer-disabled flag changed) leaves the average exactly as it was. Stored values are
     * coalesced from null, so a row predating a stats column cannot silently turn the whole
     * expression null.
     *
     * <p>{@code developerDisabled} is <em>replaced</em>, not accumulated: it is a fact the run
     * observed about the suite, not a running total, so the most recent observation is the correct
     * one.
     *
     * <p>The statement takes six bind parameters, in this order: name, num-runs, run-time,
     * num-success-runs, num-fail-runs, developer-disabled. Both vendors' forms return the row's
     * generated key, for the matched and the unmatched case alike, so the caller can write the
     * suite's coverage edges without a second lookup.
     *
     * @param table the test-suite table
     * @param nameColumn the suite-name column, which is the conflict/merge key
     * @param numRunsColumn the run-count column, accumulated
     * @param avgRunTimeColumn the average run-time column, folded as a weighted mean
     * @param numSuccessRunsColumn the successful-run-count column, accumulated
     * @param numFailRunsColumn the failed-run-count column, accumulated
     * @param developerDisabledColumn the developer-disabled flag column, replaced
     * @return the vendor-specific accumulating upsert SQL
     */
    String accumulatingTestSuiteUpsert(String table, String nameColumn, String numRunsColumn,
                                       String avgRunTimeColumn, String numSuccessRunsColumn,
                                       String numFailRunsColumn, String developerDisabledColumn);

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
