package org.tiatesting.core.persistence;

import org.tiatesting.core.library.LibraryImpactDrainResult;
import org.tiatesting.core.library.LibraryImpactDrainResultSerializer;
import org.tiatesting.core.model.LineRanges;
import org.tiatesting.core.model.MethodImpactTracker;
import org.tiatesting.core.model.SelectionMode;

import java.io.IOException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;

import static org.tiatesting.core.persistence.TiaSchema.*;

/**
 * Stateless JDBC helpers shared by the Tia repositories: binding and reading nullable columns,
 * mapping rows that more than one table shares the shape of, and building multi-row inserts.
 */
final class JdbcRows {

    private JdbcRows() {
    }

    /**
     * Build a multi-row {@code INSERT ... VALUES (?,..),(?,..),...} statement with {@code rows}
     * value tuples, each carrying {@code paramsPerRow} placeholders.
     *
     * @param table the target table
     * @param columnsCsv the comma-separated column list
     * @param paramsPerRow the number of columns (placeholders) per row
     * @param rows the number of value tuples
     * @return the parameterised multi-row insert SQL
     */
    static String buildMultiRowInsertSql(String table, String columnsCsv, int paramsPerRow, int rows) {
        StringBuilder tuple = new StringBuilder("(");
        for (int p = 0; p < paramsPerRow; p++) { tuple.append(p > 0 ? ", ?" : "?"); }
        tuple.append(')');
        StringBuilder sb = new StringBuilder("INSERT INTO ").append(table).append(" (").append(columnsCsv).append(") VALUES ");
        for (int r = 0; r < rows; r++) { if (r > 0) { sb.append(','); } sb.append(tuple); }
        return sb.toString();
    }

    /**
     * Read a history row's selection mode, keeping null for a row recorded before modes were
     * rather than reading it as selective.
     *
     * @param resultSet the history result set, positioned on a row
     * @return the row's mode, or null when none was recorded
     * @throws SQLException if the column cannot be read
     */
    static SelectionMode readSelectionMode(ResultSet resultSet) throws SQLException {
        String mode = resultSet.getString(COL_SELECTION_MODE);
        return mode == null ? null : SelectionMode.fromStoredName(mode);
    }

    /**
     * Bind a nullable long parameter, using {@code setNull} with {@link Types#BIGINT} when the
     * value is absent so both vendors store a real SQL NULL rather than zero.
     *
     * @param statement the statement to bind on
     * @param index the one-based parameter index
     * @param value the value to bind, or null
     * @throws SQLException if binding fails
     */
    static void setNullableLong(PreparedStatement statement, int index, Long value)
            throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.BIGINT);
        } else {
            statement.setLong(index, value);
        }
    }

    /**
     * Bind a nullable int parameter, using {@code setNull} with {@link Types#INTEGER} when the
     * value is absent so both vendors store a real SQL NULL rather than zero.
     *
     * @param statement the statement to bind on
     * @param index the one-based parameter index
     * @param value the value to bind, or null
     * @throws SQLException if binding fails
     */
    static void setNullableInt(PreparedStatement statement, int index, Integer value)
            throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.INTEGER);
        } else {
            statement.setInt(index, value);
        }
    }

    /**
     * Bind a nullable string parameter, using {@code setNull} with {@link Types#VARCHAR} when the
     * value is absent. {@code setString(index, null)} is accepted by both drivers in practice, but
     * binding the type explicitly keeps the null path unambiguous for the driver's parameter
     * inference, which Postgres is stricter about.
     *
     * @param statement the statement to bind on
     * @param index the one-based parameter index
     * @param value the value to bind, or null
     * @throws SQLException if binding fails
     */
    static void setNullableString(PreparedStatement statement, int index, String value)
            throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.VARCHAR);
        } else {
            statement.setString(index, value);
        }
    }

    /**
     * Bind the run's library-impact drain result as a binary parameter, serialized with the same
     * {@link LibraryImpactDrainResultSerializer} encoding the Maven plugin-to-fork handoff file
     * uses, so the drain a plan already performed is stored exactly once and in one form. A run
     * that drained nothing binds SQL NULL rather than a serialized empty object, so the read side
     * can treat null as "nothing to clean up".
     *
     * @param statement the statement to bind on
     * @param index the one-based parameter index
     * @param drainResult the drain result to store, or null if nothing was drained
     * @throws SQLException if binding fails
     * @throws IOException if the drain result cannot be serialized - deliberately not swallowed,
     *                     since the drain cannot be repeated and storing nothing would lose its
     *                     cleanup permanently
     */
    static void setDrainResult(PreparedStatement statement, int index,
                                       LibraryImpactDrainResult drainResult)
            throws SQLException, IOException {
        if (drainResult == null) {
            statement.setNull(index, Types.VARBINARY);
        } else {
            statement.setBytes(index, LibraryImpactDrainResultSerializer.toBytes(drainResult));
        }
    }

    /**
     * Read the library-impact drain result stored on a run row, deserializing the binary column
     * written by {@link #setDrainResult}.
     *
     * @param resultSet the result set positioned on the run row
     * @return the stored drain result, or null if the column was SQL NULL
     * @throws SQLException if the column cannot be read
     * @throws TiaPersistenceException if the stored bytes cannot be deserialized, since a caller
     *                                 silently given no drain result would skip cleanup that
     *                                 nothing else can reconstruct
     */
    static LibraryImpactDrainResult getDrainResult(ResultSet resultSet) throws SQLException {
        byte[] bytes = resultSet.getBytes(COL_DRAIN_RESULT);
        try {
            return LibraryImpactDrainResultSerializer.fromBytes(bytes);
        } catch (IOException | ClassNotFoundException e) {
            throw new TiaPersistenceException(e);
        }
    }

    /**
     * Read a nullable long column, distinguishing SQL NULL from zero via
     * {@link ResultSet#wasNull()}.
     *
     * @param resultSet the result set positioned on the row
     * @param column the column name
     * @return the value, or null if the column was SQL NULL
     * @throws SQLException if the column cannot be read
     */
    static Long getNullableLong(ResultSet resultSet, String column) throws SQLException {
        long value = resultSet.getLong(column);
        return resultSet.wasNull() ? null : value;
    }

    /**
     * Read a nullable int column, distinguishing SQL NULL from zero via
     * {@link ResultSet#wasNull()}. The distinction matters for the history row's group count: null
     * means "this run was not distributed", zero would mean "distributed across no groups".
     *
     * @param resultSet the result set positioned on the row
     * @param column the column name
     * @return the value, or null if the column was SQL NULL
     * @throws SQLException if the column cannot be read
     */
    static Integer getNullableInt(ResultSet resultSet, String column) throws SQLException {
        int value = resultSet.getInt(column);
        return resultSet.wasNull() ? null : value;
    }

    /**
     * Build a method tracker from the current row of a query over {@code tia_source_method} or
     * {@code tia_distributed_run_method_stage}. The row must include the method name, the start/end
     * line numbers and the (nullable) exact line ranges.
     *
     * @param resultSet the result set positioned on the row to read
     * @return the tracker for the row
     * @throws SQLException if a column can't be read
     */
    static MethodImpactTracker readMethodTracker(ResultSet resultSet) throws SQLException {
        return new MethodImpactTracker(
                resultSet.getString(COL_METHOD_NAME),
                resultSet.getInt(COL_LINE_NUMBER_START),
                resultSet.getInt(COL_LINE_NUMBER_END),
                LineRanges.parse(resultSet.getString(COL_LINE_RANGES)));
    }

    /**
     * Build a method tracker from the current row of a full {@code tia_source_method} query,
     * including the per-method run stats. Kept apart from {@link #readMethodTracker(ResultSet)}
     * because the distributed staging table and the targeted select-tests query don't carry the
     * stats columns. See the "Method run stats" chapter in {@code WIKI.md}.
     *
     * @param resultSet the result set positioned on a {@code tia_source_method} row selected with
     *                  every column
     * @return the tracker for the row, carrying its executed and triggered run counts
     * @throws SQLException if a column can't be read
     */
    static MethodImpactTracker readCatalogueMethodTracker(ResultSet resultSet) throws SQLException {
        MethodImpactTracker tracker = readMethodTracker(resultSet);
        tracker.setExecutedRunCount(resultSet.getLong(COL_EXECUTED_RUN_COUNT));
        tracker.setTriggeredRunCount(resultSet.getLong(COL_TRIGGERED_RUN_COUNT));
        return tracker;
    }
}
