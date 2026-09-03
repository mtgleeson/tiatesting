package org.tiatesting.core.persistence.dialect;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/** H2 dialect - reproduces the SQL H2DataStore emitted before the dialect extraction. */
public final class H2Dialect implements SqlDialect {

    /** {@inheritDoc} H2 uses {@code AUTO_INCREMENT}. */
    @Override public String identityColumnDefinition() { return "BIGINT AUTO_INCREMENT PRIMARY KEY"; }

    /** {@inheritDoc} H2 uses {@code BLOB}, which needs no declared length. */
    @Override public String binaryColumnType() { return "BLOB"; }

    /**
     * {@inheritDoc}
     *
     * <p>H2's {@code MERGE INTO ... KEY(...) VALUES (...)} form cannot do this - its right-hand
     * side is a value list, so a column cannot reference its own stored value ({@code Column
     * "NUM_RUNS" not found}). The SQL-standard {@code MERGE ... USING ... WHEN MATCHED} form can,
     * and H2 2.x supports it.
     *
     * <p>The parameters are cast explicitly in the {@code USING} select list because H2 cannot
     * infer a bare parameter's type there and fails to parse the statement without them.
     */
    @Override
    public String accumulatingTestSuiteUpsert(String table, String nameColumn, String numRunsColumn,
                                              String avgRunTimeColumn, String numSuccessRunsColumn,
                                              String numFailRunsColumn, String developerDisabledColumn) {
        String storedRuns = "COALESCE(" + table + "." + numRunsColumn + ", 0)";
        String storedAvg = "COALESCE(" + table + "." + avgRunTimeColumn + ", 0)";
        return "MERGE INTO " + table + " USING (SELECT "
                + "CAST(? AS VARCHAR(500)) AS s_name, "
                + "CAST(? AS BIGINT) AS s_num_runs, "
                + "CAST(? AS BIGINT) AS s_avg_run_time, "
                + "CAST(? AS BIGINT) AS s_num_success_runs, "
                + "CAST(? AS BIGINT) AS s_num_fail_runs, "
                + "CAST(? AS BOOLEAN) AS s_developer_disabled) s"
                + " ON " + table + "." + nameColumn + " = s.s_name"
                + " WHEN MATCHED THEN UPDATE SET "
                + table + "." + avgRunTimeColumn + " = CASE WHEN " + storedRuns + " + s.s_num_runs = 0"
                + " THEN " + storedAvg
                + " ELSE (" + storedRuns + " * " + storedAvg + " + s.s_num_runs * s.s_avg_run_time)"
                + " / (" + storedRuns + " + s.s_num_runs) END, "
                + table + "." + numRunsColumn + " = " + storedRuns + " + s.s_num_runs, "
                + table + "." + numSuccessRunsColumn + " = COALESCE(" + table + "." + numSuccessRunsColumn
                + ", 0) + s.s_num_success_runs, "
                + table + "." + numFailRunsColumn + " = COALESCE(" + table + "." + numFailRunsColumn
                + ", 0) + s.s_num_fail_runs, "
                + table + "." + developerDisabledColumn + " = s.s_developer_disabled"
                + " WHEN NOT MATCHED THEN INSERT (" + nameColumn + ", " + numRunsColumn + ", "
                + avgRunTimeColumn + ", " + numSuccessRunsColumn + ", " + numFailRunsColumn + ", "
                + developerDisabledColumn + ")"
                + " VALUES (s.s_name, s.s_num_runs, s.s_avg_run_time, s.s_num_success_runs, "
                + "s.s_num_fail_runs, s.s_developer_disabled)";
    }

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

    /**
     * {@inheritDoc} Implemented as {@code DELETE FROM <table>} rather than {@code TRUNCATE TABLE}:
     * H2 implements {@code TRUNCATE} as DDL ({@code DefineCommand}), which implicitly commits on
     * execution regardless of the connection's auto-commit state or any later {@code rollback()}
     * - so a {@code TRUNCATE} here would silently escape the enclosing transaction and could not
     * be undone if a later step in the same transaction failed. {@code DELETE FROM} is a normal
     * DML statement and rolls back correctly with the rest of the transaction.
     * @param table the table to clear
     * @return the {@code DELETE FROM} statement for the table
     */
    @Override public String clearTableTransactionallySql(String table) { return "DELETE FROM " + table; }

    /**
     * {@inheritDoc} H2 folds unquoted identifiers to upper case, and preserves the case of a
     * quoted identifier (schema names here are always quoted and always lower case, since
     * {@code BranchSchema.schemaName} lower-cases them). The lookup is scoped to
     * {@link Connection#getSchema()} (the schema {@code JdbcDataStore.getConnection()} just
     * selected) via {@code DatabaseMetaData.getTables}, but {@code schemaPattern} and
     * {@code tableNamePattern} are JDBC LIKE patterns in which {@code _} and {@code %} are
     * wildcards - and every per-branch schema name is {@code tia_<sanitised-branch>}, which
     * always contains {@code _}. Passing the schema/table name straight through as a pattern can
     * therefore false-match a sibling schema (e.g. schema {@code tia_v1_2} used as a pattern
     * matches the literal schema {@code tia_v1x2}), wrongly reporting a fresh branch schema as
     * already migrated. To avoid that, the result set is iterated and each row's
     * {@code TABLE_SCHEM}/{@code TABLE_NAME} is checked for an exact (non-pattern) match rather
     * than trusting {@code rs.next()} from the pattern query.
     */
    @Override
    public boolean tableExists(Connection connection, String tableName) throws SQLException {
        String schema = connection.getSchema();
        String upperTableName = tableName.toUpperCase();
        try (ResultSet rs = connection.getMetaData()
                .getTables(null, schema, upperTableName, new String[]{"TABLE"})) {
            while (rs.next()) {
                if (schema.equals(rs.getString("TABLE_SCHEM")) && upperTableName.equals(rs.getString("TABLE_NAME"))) {
                    return true;
                }
            }
        }
        return false;
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
