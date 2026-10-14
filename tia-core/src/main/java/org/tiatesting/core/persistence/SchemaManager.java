package org.tiatesting.core.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tiatesting.core.persistence.dialect.SqlDialect;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.function.Supplier;

import static org.tiatesting.core.persistence.TiaSchema.*;

/**
 * Owns the Tia relational schema: creates every table and index on a database's first contact.
 * {@link #ensureSchema} is the single bootstrap entry point every repository calls before its
 * first statement. There are no migrations - Tia is pre-release, so a database created by an
 * earlier Tia whose schema no longer matches is dropped and re-created rather than upgraded.
 */
class SchemaManager {
    private final Logger log = LoggerFactory.getLogger(SchemaManager.class);
    private final SqlDialect dialect;
    private final Supplier<Connection> connections;
    private final String jdbcUrl;
    // Memoizes ensureSchema: once the DB is known to exist, later calls on this instance skip
    // the existence-check round trip.
    private boolean schemaEnsured;

    /**
     * Construct a schema manager for one datastore.
     *
     * @param dialect the SQL dialect rendering the vendor-specific DDL (identity and binary
     *                columns, table-existence lookup)
     * @param connections opens a connection with the per-branch schema selected; used to create
     *                    the tables on a database's first contact
     * @param jdbcUrl the database URL, for logging only
     */
    SchemaManager(SqlDialect dialect, Supplier<Connection> connections, String jdbcUrl) {
        this.dialect = dialect;
        this.connections = connections;
        this.jdbcUrl = jdbcUrl;
    }

    /**
     * Ensure the Tia schema is ready for use on this connection, creating every table and index
     * on first contact. This is the single schema-bootstrap entry point - both the full-load path
     * ({@code JdbcDataStore.getTiaData}) and the targeted query and write methods call it, so none
     * depends on another having run first. An existing DB costs one existence check and no DDL.
     * Memoized per datastore instance: the schema can't disappear within a JVM, so repeat calls
     * skip even that check.
     *
     * @param connection the connection to check on
     * @return {@code true} if the Tia DB already existed before this call (or an earlier call
     *         on this instance bootstrapped it), {@code false} if it was just created empty
     * @throws SQLException if the existence check fails
     */
    boolean ensureSchema(Connection connection) throws SQLException {
        if (schemaEnsured) {
            return true;
        }

        boolean dbExisted = checkTiaDBExists(connection);
        if (!dbExisted) {
            log.debug("The Tia DB doesn't currently exist at {}", jdbcUrl);
            createTiaDB();
        }

        schemaEnsured = true;
        return dbExisted;
    }

    /**
     * Create every Tia table and index on a database that has none yet, over a fresh connection.
     * Every statement is {@code IF NOT EXISTS}, so re-running it over a partly created database
     * is harmless. All statements are batched onto one
     * {@link Statement} and sent with a single {@code executeBatch} call to keep a server-mode or
     * Postgres bootstrap to one wire round trip.
     *
     * @throws TiaPersistenceException if any DDL statement fails
     */
    private void createTiaDB(){
        log.info("Creating the Tia DB");
        String createCoreTableSql = "CREATE TABLE IF NOT EXISTS " + TABLE_TIA_CORE + " (" +
                COL_COMMIT_VALUE + " VARCHAR(255) PRIMARY KEY, " +
                COL_BRANCH + " VARCHAR(255), " +
                COL_LAST_UPDATED + " TIMESTAMP WITH TIME ZONE, " +
                COL_NUM_RUNS + " BIGINT, " +
                COL_AVG_RUN_TIME + " BIGINT, " +
                COL_NUM_SUCCESS_RUNS + " BIGINT," +
                COL_NUM_FAIL_RUNS + " BIGINT, " +
                COL_ALL_TESTS_RUN_TIME + " BIGINT DEFAULT 0, " +
                COL_NUM_ALL_TESTS_RUNS + " BIGINT DEFAULT 0, " +
                COL_FIXED_OVERHEAD_MS + " BIGINT DEFAULT 0, " +
                COL_CAPTURE_OVERHEAD_PER_SUITE_MS + " BIGINT DEFAULT 0, " +
                COL_NUM_OVERHEAD_MEASUREMENTS + " BIGINT DEFAULT 0)";

        String createTestSuitesFailedTableSql = "CREATE TABLE IF NOT EXISTS " + TABLE_TIA_TEST_SUITES_FAILED + " " +
                "(" + COL_TEST_SUITE_NAME + " VARCHAR(255) PRIMARY KEY)";

        // method_name is declared unbounded (VARCHAR with no length) because it stores a method's
        // full descriptor including its complete parameter-type list, which can exceed any fixed
        // width for high-arity methods and constructors.
        String createSourceMethodTableSql = "CREATE TABLE IF NOT EXISTS " + TABLE_TIA_SOURCE_METHOD + " " +
                "(" + COL_ID + " INT PRIMARY KEY, " +
                COL_METHOD_NAME + " VARCHAR, " +
                COL_LINE_NUMBER_START + " INT, " +
                COL_LINE_NUMBER_END + " INT, " +
                COL_LINE_RANGES + " VARCHAR, " +
                COL_EXECUTED_RUN_COUNT + " BIGINT DEFAULT 0, " +
                COL_TRIGGERED_RUN_COUNT + " BIGINT DEFAULT 0)";

        String createTestSuiteTableSql = "CREATE TABLE IF NOT EXISTS " + TABLE_TIA_TEST_SUITE + " " +
                "(" + COL_ID + " " + dialect.identityColumnDefinition() + ", " +
                COL_NAME + " VARCHAR(500), " +
                COL_SOURCE_FILENAME + " VARCHAR(500), " +
                COL_NUM_RUNS + " BIGINT, " +
                COL_AVG_RUN_TIME + " BIGINT, " +
                COL_NUM_SUCCESS_RUNS + " BIGINT, " +
                COL_NUM_FAIL_RUNS + " BIGINT, " +
                COL_DEVELOPER_DISABLED + " BOOLEAN DEFAULT FALSE, " +
                COL_UNSEALED + " BOOLEAN DEFAULT FALSE)";

        // Unique index on tia_test_suite.name. This is the conflict target for the suite upsert
        // (H2 MERGE ... KEY(name) / Postgres INSERT ... ON CONFLICT (name)). Postgres requires the
        // ON CONFLICT target to be backed by a real unique constraint or index, so the constraint
        // MUST sit on name.
        String createTestSuiteNameIndexSql = "CREATE UNIQUE INDEX IF NOT EXISTS " + TABLE_TIA_TEST_SUITE + "_" + COL_NAME + "_idx ON " +
                TABLE_TIA_TEST_SUITE + " (" + COL_NAME + ")";

        String createSourceClassTableSql = "CREATE TABLE IF NOT EXISTS " + TABLE_TIA_SOURCE_CLASS + " " +
                "(" + COL_ID + " " + dialect.identityColumnDefinition() + ", " +
                COL_TIA_TEST_SUITE_ID + " BIGINT, " +
                COL_SOURCE_FILENAME + " VARCHAR(500))";

        // Index on tia_source_class.tia_test_suite_id is essential for the suite-scoped
        // "DELETE FROM tia_source_class WHERE tia_test_suite_id = ?" run on every suite persist:
        // without it, each delete is a full scan of all tia_source_class rows - observed at ~30%
        // of CPU on a 940K-row tia_source_class table.
        String createSourceClassTestSuiteIndexSql = buildCreateSourceClassTestSuiteIndexSql();

        String createSourceClassMethodTableSql = "CREATE TABLE IF NOT EXISTS " + TABLE_TIA_SOURCE_CLASS_METHOD +
                " (" + COL_TIA_SOURCE_CLASS_ID + " BIGINT, " +
                COL_TIA_SOURCE_METHOD_ID + " INT, " +
                "PRIMARY KEY (" + COL_TIA_SOURCE_CLASS_ID + ", " + COL_TIA_SOURCE_METHOD_ID + "))";

        // Indexes backing the targeted select-tests queries (changed-files-to-tracked-methods
        // filename lookup and methods-to-covering-suites method-id lookup).
        String createSourceClassFilenameIndexSql = buildCreateSourceClassFilenameIndexSql();
        String createSourceClassMethodMethodIdIndexSql = buildCreateSourceClassMethodMethodIdIndexSql();

        String createLibraryTableSql = buildCreateLibraryTableSql();
        String createPendingLibraryMethodTableSql = buildCreatePendingLibraryImpactedMethodTableSql();
        String createPendingLibraryForcedSelectionTableSql = buildCreatePendingLibraryForcedSelectionTableSql();
        String createLibraryPublishTableSql = buildCreateLibraryPublishTableSql();
        String createTestRunHistoryTableSql = buildCreateTestRunHistoryTableSql();
        String createTestRunHistoryIndexSql = buildCreateTestRunHistoryIndexSql();
        String createIdBlockTableSql = buildCreateIdBlockTableSql();

        try (Connection connection = connections.get();
             Statement statement = connection.createStatement()) {
            statement.addBatch(createCoreTableSql);
            statement.addBatch(createSourceMethodTableSql);
            statement.addBatch(createTestSuitesFailedTableSql);
            statement.addBatch(createTestSuiteTableSql);
            statement.addBatch(createTestSuiteNameIndexSql);
            statement.addBatch(createSourceClassTableSql);
            statement.addBatch(createSourceClassTestSuiteIndexSql);
            statement.addBatch(createSourceClassMethodTableSql);
            statement.addBatch(createSourceClassFilenameIndexSql);
            statement.addBatch(createSourceClassMethodMethodIdIndexSql);
            statement.addBatch(createIdBlockTableSql);
            statement.addBatch(createLibraryTableSql);
            statement.addBatch(createPendingLibraryMethodTableSql);
            statement.addBatch(createPendingLibraryForcedSelectionTableSql);
            statement.addBatch(createLibraryPublishTableSql);
            statement.addBatch(createTestRunHistoryTableSql);
            statement.addBatch(createTestRunHistoryIndexSql);
            statement.addBatch(buildCreateTestRunHistoryTriggerTableSql());
            statement.addBatch(buildCreateDistributedRunTableSql());
            statement.addBatch(buildCreateDistributedRunGroupTableSql());
            statement.addBatch(buildCreateDistributedRunGroupSuiteTableSql());
            statement.addBatch(buildCreateDistributedRunMethodStageTableSql());
            statement.addBatch(buildCreateDistributedRunCoveredMethodTableSql());
            statement.addBatch(buildCreateDistributedRunGroupStatusIndexSql());
            statement.addBatch(buildCreateDistributedRunSelectionTableSql());
            statement.addBatch(buildCreateDistributedRunTriggerTableSql());
            statement.executeBatch();
        } catch (SQLException e) {
            throw new TiaPersistenceException(e);
        }
        log.info("Finished creating the Tia DB");
    }

    /**
     * Check whether the Tia DB exists, using the {@code tia_core} table as its marker.
     *
     * @param connection the open connection to query metadata on
     * @return true if the Tia tables have been created
     * @throws SQLException if metadata access fails
     */
    private boolean checkTiaDBExists(Connection connection) throws SQLException {
        return checkTableExists(connection, TABLE_TIA_CORE);
    }

    /**
     * Check whether a table exists, delegating to the dialect so vendor identifier case-folding is
     * handled correctly (H2 folds unquoted names to upper case; other vendors differ).
     *
     * @param connection the open connection to query metadata on
     * @param tableName the unquoted table name as written in the DDL
     * @return true if the table exists
     * @throws SQLException if metadata access fails
     */
    boolean checkTableExists(Connection connection, String tableName) throws SQLException {
        return dialect.tableExists(connection, tableName);
    }

    /**
     * DDL for the index on {@code tia_source_class.tia_test_suite_id}. Backs the suite-scoped
     * {@code DELETE FROM tia_source_class WHERE tia_test_suite_id = ?} run on every suite persist,
     * which would otherwise scan every source-class row.
     *
     * @return the {@code CREATE INDEX IF NOT EXISTS} statement for the suite-id index
     */
    private static String buildCreateSourceClassTestSuiteIndexSql() {
        return "CREATE INDEX IF NOT EXISTS " + COL_TIA_TEST_SUITE_ID + "_idx ON "
                + TABLE_TIA_SOURCE_CLASS + " (" + COL_TIA_TEST_SUITE_ID + ")";
    }

    /**
     * DDL for the index on {@code tia_source_class.source_filename}. Backs the targeted
     * select-tests query that resolves changed source files to their tracked methods
     * (the changed-files-to-tracked-methods lookup) - without it the filename {@code IN} predicate scans every source-class row.
     * Named with the {@code idx_} prefix to keep it distinct from the other schema-global H2 index
     * names ({@code source_filename} is also a column on {@code tia_source_class}).
     *
     * @return the {@code CREATE INDEX IF NOT EXISTS} statement for the filename index
     */
    private static String buildCreateSourceClassFilenameIndexSql() {
        return "CREATE INDEX IF NOT EXISTS idx_source_class_filename ON "
                + TABLE_TIA_SOURCE_CLASS + " (" + COL_SOURCE_FILENAME + ")";
    }

    /**
     * DDL for the index on {@code tia_source_class_method.tia_source_method_id}. Backs the
     * targeted select-tests query that resolves impacted method ids to their covering test
     * suites (the methods-to-covering-suites lookup). The table's composite primary key leads with
     * {@code tia_source_class_id}, so a method-id-leading lookup cannot use it.
     *
     * @return the {@code CREATE INDEX IF NOT EXISTS} statement for the method-id index
     */
    private static String buildCreateSourceClassMethodMethodIdIndexSql() {
        return "CREATE INDEX IF NOT EXISTS idx_source_class_method_method_id ON "
                + TABLE_TIA_SOURCE_CLASS_METHOD + " (" + COL_TIA_SOURCE_METHOD_ID + ")";
    }

    /**
     * Build the DDL for the {@code tia_id_block} table. It holds one row per application-assigned
     * id space, recording the next unallocated value, so concurrent writers can reserve disjoint
     * id blocks instead of each reading {@code MAX(id)} and colliding.
     *
     * @return the {@code CREATE TABLE IF NOT EXISTS} statement for the id-block table
     */
    private String buildCreateIdBlockTableSql() {
        return "CREATE TABLE IF NOT EXISTS " + TABLE_TIA_ID_BLOCK + " ("
                + COL_BLOCK_NAME + " VARCHAR(100) PRIMARY KEY, "
                + COL_NEXT_VALUE + " BIGINT NOT NULL)";
    }

    /**
     * Build the DDL for the {@code tia_library} table.
     */
    private String buildCreateLibraryTableSql() {
        return "CREATE TABLE IF NOT EXISTS " + TABLE_TIA_LIBRARY + " ("
                + COL_GROUP_ARTIFACT + " VARCHAR(512) PRIMARY KEY, "
                + COL_PROJECT_DIR + " VARCHAR(1000), "
                + COL_SOURCE_DIRS_CSV + " VARCHAR(2000), "
                + COL_MAPPING_BASELINE_COMMIT + " VARCHAR(128), "
                + COL_LAST_APPLIED_SEQ + " BIGINT)";
    }

    /**
     * Build the DDL for the {@code tia_pending_library_impacted_method} table.
     */
    private String buildCreatePendingLibraryImpactedMethodTableSql() {
        return "CREATE TABLE IF NOT EXISTS " + TABLE_TIA_PENDING_LIBRARY_IMPACTED_METHOD + " ("
                + COL_GROUP_ARTIFACT + " VARCHAR(512) NOT NULL, "
                + COL_STAMP_VERSION + " VARCHAR(128) NOT NULL, "
                + COL_PUBLISH_SEQ + " BIGINT NOT NULL, "
                + COL_TIA_SOURCE_METHOD_ID + " INT NOT NULL, "
                + "PRIMARY KEY (" + COL_GROUP_ARTIFACT + ", " + COL_PUBLISH_SEQ + ", " + COL_TIA_SOURCE_METHOD_ID + "), "
                + "FOREIGN KEY (" + COL_GROUP_ARTIFACT + ") REFERENCES " + TABLE_TIA_LIBRARY + "(" + COL_GROUP_ARTIFACT + ") ON DELETE CASCADE)";
    }

    /**
     * Build the DDL for the {@code tia_pending_library_forced_selection} table - one row per
     * (library build, rule, suite-name pattern); RUN_ALL stores a single row with a NULL pattern.
     *
     * @return the {@code CREATE TABLE IF NOT EXISTS} statement for the forced-selection table.
     */
    private String buildCreatePendingLibraryForcedSelectionTableSql() {
        return "CREATE TABLE IF NOT EXISTS " + TABLE_TIA_PENDING_LIBRARY_FORCED_SELECTION + " ("
                + COL_GROUP_ARTIFACT + " VARCHAR(512) NOT NULL, "
                + COL_STAMP_VERSION + " VARCHAR(128) NOT NULL, "
                + COL_PUBLISH_SEQ + " BIGINT NOT NULL, "
                + COL_RULE_NAME + " VARCHAR(256) NOT NULL, "
                + COL_MODE + " VARCHAR(32) NOT NULL, "
                + COL_SUITE_NAME_PATTERN + " VARCHAR(512), "
                + "FOREIGN KEY (" + COL_GROUP_ARTIFACT + ") REFERENCES " + TABLE_TIA_LIBRARY
                + "(" + COL_GROUP_ARTIFACT + ") ON DELETE CASCADE)";
    }

    /**
     * Build the DDL for the {@code tia_library_publish} table - the publish ledger, one row per
     * published build of a tracked library, ordered per library by {@code publish_seq}.
     * See the publish-ledger section of the library publish-time stamping chapter in {@code WIKI.md}.
     *
     * @return the {@code CREATE TABLE IF NOT EXISTS} statement for the publish ledger
     */
    private String buildCreateLibraryPublishTableSql() {
        return "CREATE TABLE IF NOT EXISTS " + TABLE_TIA_LIBRARY_PUBLISH + " ("
                + COL_GROUP_ARTIFACT + " VARCHAR(512) NOT NULL, "
                + COL_PUBLISH_SEQ + " BIGINT NOT NULL, "
                + COL_PUBLISHED_VERSION + " VARCHAR(128) NOT NULL, "
                + COL_JAR_HASH + " VARCHAR(128), "
                + COL_COMMIT_VALUE + " VARCHAR(128), "
                + COL_PUBLISHED_AT + " BIGINT NOT NULL, "
                + "PRIMARY KEY (" + COL_GROUP_ARTIFACT + ", " + COL_PUBLISH_SEQ + "), "
                + "FOREIGN KEY (" + COL_GROUP_ARTIFACT + ") REFERENCES " + TABLE_TIA_LIBRARY + "(" + COL_GROUP_ARTIFACT + ") ON DELETE CASCADE)";
    }

    /**
     * Build the DDL for the {@code tia_test_run_history} table.
     *
     * @return the {@code CREATE TABLE IF NOT EXISTS} statement for the history table
     */
    private String buildCreateTestRunHistoryTableSql() {
        return "CREATE TABLE IF NOT EXISTS " + TABLE_TIA_TEST_RUN_HISTORY + " ("
                + COL_ID + " VARCHAR(64) NOT NULL PRIMARY KEY, "
                + COL_RUN_TIMESTAMP + " BIGINT NOT NULL, "
                + COL_BRANCH + " VARCHAR(255), "
                + COL_COMMIT_VALUE + " VARCHAR(255), "
                + COL_NUM_SUITES_RAN + " INT, "
                + COL_NUM_SUITES_IGNORED + " INT, "
                + COL_NUM_SUITES_FAILED + " INT, "
                + COL_DURATION_MS + " BIGINT, "
                + COL_UPDATED_DB_MAPPING + " BOOLEAN, "
                + COL_TIME_SAVINGS + " BIGINT DEFAULT 0, "
                + COL_SAVINGS_PERCENT + " INT DEFAULT 0, "
                + COL_WALL_CLOCK_SAVINGS + " BIGINT DEFAULT 0, "
                + COL_WALL_CLOCK_SAVINGS_PERCENT + " INT DEFAULT 0, "
                // Distributed builds only; null on a single-host run.
                + COL_RUN_ID + " VARCHAR(255), "
                + COL_WALL_CLOCK_MS + " BIGINT, "
                + COL_GROUP_COUNT + " INT, "
                + COL_GROUPS_AVAILABLE + " INT, "
                // Where the run came from. Nullable throughout: null means "not known", which is
                // what a run whose hostname would not resolve and a distributed build (no single
                // host ran it) genuinely are.
                + COL_RUN_SOURCE + " VARCHAR(32), "
                + COL_HOST_NAME + " VARCHAR(255), "
                // The selection-breakdown counters. Nullable with no DEFAULT: an all-tests run
                // reads back null - "not recorded" - rather than a made-up zero.
                + COL_NUM_MODIFIED_TEST_FILES + " INT, "
                + COL_NUM_NEW_TEST_FILES + " INT, "
                + COL_NUM_PREVIOUSLY_FAILED + " INT, "
                + COL_NUM_UNSEALED_MAPPING + " INT, "
                + COL_NUM_PENDING_LIBRARY + " INT, "
                // True for a retry of failed tests (a Surefire rerun or a Gradle test-retry round),
                // which is credited no savings.
                + COL_RERUN + " BOOLEAN DEFAULT FALSE, "
                // How the run's selection was decided (SELECTIVE, SEED, SELECT_ALL or RESEED).
                + COL_SELECTION_MODE + " VARCHAR(16))";
    }

    /**
     * Build the DDL for the index on {@code tia_test_run_history.run_timestamp}. Backs the
     * default sort by recency in the report read path ({@code ORDER BY run_timestamp DESC}).
     *
     * @return the {@code CREATE INDEX IF NOT EXISTS} statement for the timestamp index
     */
    private String buildCreateTestRunHistoryIndexSql() {
        return "CREATE INDEX IF NOT EXISTS " + IDX_TEST_RUN_HISTORY_TS + " ON "
                + TABLE_TIA_TEST_RUN_HISTORY + " (" + COL_RUN_TIMESTAMP + ")";
    }

    /**
     * Build the DDL for the {@code tia_test_run_history_trigger} table: one row per selection
     * trigger (a changed source method or a fired static rule) attributed to a history row,
     * foreign-keyed to it so the trigger rows are cascade-deleted with their parent.
     *
     * @return the {@code CREATE TABLE IF NOT EXISTS} statement for the trigger table
     */
    private String buildCreateTestRunHistoryTriggerTableSql() {
        return "CREATE TABLE IF NOT EXISTS " + TABLE_TIA_TEST_RUN_HISTORY_TRIGGER + " ("
                + COL_HISTORY_ID + " VARCHAR(64) NOT NULL, "
                + COL_TRIGGER_TYPE + " VARCHAR(16) NOT NULL, "
                + COL_TRIGGER_NAME + " VARCHAR(1024) NOT NULL, "
                + COL_TEST_COUNT + " INT NOT NULL, "
                + "FOREIGN KEY (" + COL_HISTORY_ID + ") REFERENCES " + TABLE_TIA_TEST_RUN_HISTORY
                + "(" + COL_ID + ") ON DELETE CASCADE)";
    }

    /**
     * Build the DDL for the {@code tia_distributed_run} table, which holds one row per logical
     * distributed build. The row is inserted {@code OPEN} in the same transaction as its groups,
     * so a runner never observes a half-written plan.
     *
     * @return the {@code CREATE TABLE IF NOT EXISTS} statement for the run table
     */
    private String buildCreateDistributedRunTableSql() {
        return "CREATE TABLE IF NOT EXISTS " + TABLE_TIA_DISTRIBUTED_RUN + " ("
                + COL_RUN_ID + " VARCHAR(255) NOT NULL PRIMARY KEY, "
                + COL_BRANCH + " VARCHAR(255) NOT NULL, "
                + COL_COMMIT_VALUE + " VARCHAR(255) NOT NULL, "
                + COL_STATUS + " VARCHAR(16) NOT NULL, "
                + COL_GROUP_COUNT + " INT NOT NULL, "
                + COL_TARGET_RUN_TIME_MS + " BIGINT, "
                + COL_ESTIMATED_TOTAL_MS + " BIGINT NOT NULL, "
                + COL_CREATED_AT + " BIGINT NOT NULL, "
                + COL_SEALED_BY + " VARCHAR(255), "
                + COL_SEALED_AT + " BIGINT, "
                + COL_DRAIN_RESULT + " " + dialect.binaryColumnType() + ", "
                + COL_SELECTION_MODE + " VARCHAR(16) DEFAULT 'SELECTIVE', "
                + COL_GROUPS_AVAILABLE + " INT, "
                + COL_RUN_SOURCE + " VARCHAR(32))";
    }

    /**
     * Build the DDL for the {@code tia_distributed_run_group} table, one row per runner slice.
     *
     * @return the {@code CREATE TABLE IF NOT EXISTS} statement for the group table
     */
    private String buildCreateDistributedRunGroupTableSql() {
        return "CREATE TABLE IF NOT EXISTS " + TABLE_TIA_DISTRIBUTED_RUN_GROUP + " ("
                + COL_RUN_ID + " VARCHAR(255) NOT NULL, "
                + COL_GROUP_NUMBER + " INT NOT NULL, "
                + COL_STATUS + " VARCHAR(16) NOT NULL, "
                + COL_RUNNER_KEY + " VARCHAR(255), "
                + COL_CLAIMED_AT + " BIGINT, "
                + COL_COMPLETED_AT + " BIGINT, "
                + COL_ESTIMATED_MS + " BIGINT NOT NULL, "
                + COL_ACTUAL_DURATION_MS + " BIGINT, "
                + COL_SUITES_RAN + " INT DEFAULT 0, "
                + COL_SUITES_FAILED + " INT DEFAULT 0, "
                + COL_SUITES_OBSERVED + " INT DEFAULT 0, "
                + COL_SUITES_DURATION_MS + " BIGINT DEFAULT 0, "
                + "PRIMARY KEY (" + COL_RUN_ID + ", " + COL_GROUP_NUMBER + "))";
    }

    /**
     * Build the DDL for the {@code tia_distributed_run_group_suite} table, which records which
     * suites belong to which group.
     *
     * @return the {@code CREATE TABLE IF NOT EXISTS} statement for the group-suite table
     */
    private String buildCreateDistributedRunGroupSuiteTableSql() {
        return "CREATE TABLE IF NOT EXISTS " + TABLE_TIA_DISTRIBUTED_RUN_GROUP_SUITE + " ("
                + COL_RUN_ID + " VARCHAR(255) NOT NULL, "
                + COL_GROUP_NUMBER + " INT NOT NULL, "
                + COL_TEST_SUITE_NAME + " VARCHAR(500) NOT NULL, "
                + "PRIMARY KEY (" + COL_RUN_ID + ", " + COL_GROUP_NUMBER + ", " + COL_TEST_SUITE_NAME + "))";
    }

    /**
     * Build the DDL for the {@code tia_distributed_run_method_stage} table. Runners stage their
     * method trackers here so the sealer can write the method catalogue once, after the barrier.
     * The {@code method_name} column is declared unbounded (VARCHAR with no length) because a
     * method descriptor includes its full parameter-type list and can exceed any fixed width for
     * high-arity methods and constructors.
     *
     * @return the {@code CREATE TABLE IF NOT EXISTS} statement for the method staging table
     */
    private String buildCreateDistributedRunMethodStageTableSql() {
        return "CREATE TABLE IF NOT EXISTS " + TABLE_TIA_DISTRIBUTED_RUN_METHOD_STAGE + " ("
                + COL_RUN_ID + " VARCHAR(255) NOT NULL, "
                + COL_ID + " INT NOT NULL, "
                + COL_METHOD_NAME + " VARCHAR, "
                + COL_LINE_NUMBER_START + " INT, "
                + COL_LINE_NUMBER_END + " INT, "
                + COL_LINE_RANGES + " VARCHAR, "
                + "PRIMARY KEY (" + COL_RUN_ID + ", " + COL_ID + "))";
    }

    /**
     * Build the DDL for the {@code tia_distributed_run_covered_method} table. Runners stage the ids
     * of the methods their suites executed here, one row per id per run, so the sealer can count
     * each method's executed runs from the union. See the "Method run stats" chapter in
     * {@code WIKI.md}.
     *
     * @return the {@code CREATE TABLE IF NOT EXISTS} statement for the covered method table
     */
    private String buildCreateDistributedRunCoveredMethodTableSql() {
        return "CREATE TABLE IF NOT EXISTS " + TABLE_TIA_DISTRIBUTED_RUN_COVERED_METHOD + " ("
                + COL_RUN_ID + " VARCHAR(255) NOT NULL, "
                + COL_ID + " INT NOT NULL, "
                + "PRIMARY KEY (" + COL_RUN_ID + ", " + COL_ID + "))";
    }

    /**
     * Build the DDL for the index backing group lookups by status within one run. Both the claim
     * query and the barrier check filter one run's groups by status, and the composite primary
     * key leads with {@code run_id} but has no status component.
     *
     * @return the {@code CREATE INDEX IF NOT EXISTS} statement for the group status index
     */
    private String buildCreateDistributedRunGroupStatusIndexSql() {
        return "CREATE INDEX IF NOT EXISTS " + IDX_DISTRIBUTED_RUN_GROUP_STATUS + " ON "
                + TABLE_TIA_DISTRIBUTED_RUN_GROUP + " (" + COL_RUN_ID + ", " + COL_STATUS + ")";
    }

    /**
     * Build the DDL for the {@code tia_distributed_run_selection} table: one row per distributed
     * run holding the same five scalar selection-source counters {@code
     * tia_test_run_history} stores, staged here by the planner so the sealer can later copy them
     * onto the build's single history row - see the "Run history details" chapter in {@code
     * WIKI.md}.
     *
     * @return the {@code CREATE TABLE IF NOT EXISTS} statement for the selection-counters table
     */
    private String buildCreateDistributedRunSelectionTableSql() {
        return "CREATE TABLE IF NOT EXISTS " + TABLE_TIA_DISTRIBUTED_RUN_SELECTION + " ("
                + COL_RUN_ID + " VARCHAR(255) NOT NULL PRIMARY KEY, "
                + COL_NUM_MODIFIED_TEST_FILES + " INT, "
                + COL_NUM_NEW_TEST_FILES + " INT, "
                + COL_NUM_PREVIOUSLY_FAILED + " INT, "
                + COL_NUM_UNSEALED_MAPPING + " INT, "
                + COL_NUM_PENDING_LIBRARY + " INT)";
    }

    /**
     * Build the DDL for the {@code tia_distributed_run_trigger} table: one row per selection
     * trigger staged for a distributed run, mirroring {@code tia_test_run_history_trigger}'s shape
     * but keyed by run id rather than history id, and with no foreign key - the run-id-keyed
     * tables are cleared explicitly by {@link DistributedRunRepository#persistDistributedRunSelectionDetails} and by
     * {@link DistributedRunRepository#persistDistributedRunPlan}'s {@code tablesToClear}, not by a cascade.
     *
     * @return the {@code CREATE TABLE IF NOT EXISTS} statement for the selection-trigger table
     */
    private String buildCreateDistributedRunTriggerTableSql() {
        return "CREATE TABLE IF NOT EXISTS " + TABLE_TIA_DISTRIBUTED_RUN_TRIGGER + " ("
                + COL_RUN_ID + " VARCHAR(255) NOT NULL, "
                + COL_TRIGGER_TYPE + " VARCHAR(16) NOT NULL, "
                + COL_TRIGGER_NAME + " VARCHAR(1024) NOT NULL, "
                + COL_TRIGGER_METHOD_ID + " INT, "
                + COL_TEST_COUNT + " INT NOT NULL)";
    }
}
