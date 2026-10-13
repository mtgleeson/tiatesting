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
 * Owns the Tia relational schema: creates the tables on first contact and runs the idempotent
 * migrations that bring a database created by an earlier Tia up to date. {@link #ensureSchema}
 * is the single bootstrap entry point every repository calls before its first statement; the
 * per-table {@code ensure...Exists} guards cover writes that can run before a full bootstrap.
 */
class SchemaManager {
    private final Logger log = LoggerFactory.getLogger(SchemaManager.class);
    private final SqlDialect dialect;
    private final Supplier<Connection> connections;
    private final String jdbcUrl;
    // Memoizes ensureSchema: once the DB is known to exist with migrations applied, later
    // calls on this instance skip the existence-check and DDL round trips.
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
     * Create every Tia table and index on a database that has none yet, over a fresh connection.
     * Tables added by later releases are created by the {@code ensure...} migrations that
     * {@link #ensureSchema} runs straight after, so a new database ends up with the full schema.
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
        // MUST sit on name. It was historically (mistakenly) created on the vestigial, never-written
        // source_filename column; H2's lenient MERGE KEY masked that, Postgres's ON CONFLICT did not.
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
        // filename lookup and methods-to-covering-suites method-id lookup). Created here for new
        // DBs; existing DBs gain them via
        // ensureTargetedQueryIndexesExist on first contact.
        String createSourceClassFilenameIndexSql = buildCreateSourceClassFilenameIndexSql();
        String createSourceClassMethodMethodIdIndexSql = buildCreateSourceClassMethodMethodIdIndexSql();

        String createLibraryTableSql = buildCreateLibraryTableSql();
        String createPendingLibraryMethodTableSql = buildCreatePendingLibraryImpactedMethodTableSql();
        String createPendingLibraryForcedSelectionTableSql = buildCreatePendingLibraryForcedSelectionTableSql();
        String createLibraryPublishTableSql = buildCreateLibraryPublishTableSql();
        String createTestRunHistoryTableSql = buildCreateTestRunHistoryTableSql();
        String createTestRunHistoryIndexSql = buildCreateTestRunHistoryIndexSql();

        try {
            Connection connection = connections.get();
            Statement statement = connection.createStatement();
            statement.executeUpdate(createCoreTableSql);
            statement.executeUpdate(createSourceMethodTableSql);
            statement.executeUpdate(createTestSuitesFailedTableSql);
            statement.executeUpdate(createTestSuiteTableSql);
            statement.executeUpdate(createTestSuiteNameIndexSql);
            statement.executeUpdate(createSourceClassTableSql);
            statement.executeUpdate(createSourceClassTestSuiteIndexSql);
            statement.executeUpdate(createSourceClassMethodTableSql);
            statement.executeUpdate(createSourceClassFilenameIndexSql);
            statement.executeUpdate(createSourceClassMethodMethodIdIndexSql);
            statement.executeUpdate(createLibraryTableSql);
            statement.executeUpdate(createPendingLibraryMethodTableSql);
            statement.executeUpdate(createPendingLibraryForcedSelectionTableSql);
            statement.executeUpdate(createLibraryPublishTableSql);
            statement.executeUpdate(createTestRunHistoryTableSql);
            statement.executeUpdate(createTestRunHistoryIndexSql);
            connection.close();
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
     * Ensure the {@code tia_library} table exists, creating it if necessary.
     * Called before insert/merge operations on the library table to handle
     * databases that were created before this feature was added.
     */
    void ensureLibraryTableExists(Connection connection) throws SQLException {
        if (!checkTableExists(connection, TABLE_TIA_LIBRARY)) {
            Statement statement = connection.createStatement();
            statement.executeUpdate(buildCreateLibraryTableSql());
            log.debug("Created {} table in existing Tia DB", TABLE_TIA_LIBRARY);
        }
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
     * Ensure the {@code tia_pending_library_impacted_method} table exists, creating
     * it and its parent {@code tia_library} table if necessary.
     */
    void ensurePendingLibraryImpactedMethodTableExists(Connection connection) throws SQLException {
        ensureLibraryTableExists(connection);
        if (!checkTableExists(connection, TABLE_TIA_PENDING_LIBRARY_IMPACTED_METHOD)) {
            Statement statement = connection.createStatement();
            statement.executeUpdate(buildCreatePendingLibraryImpactedMethodTableSql());
            log.debug("Created {} table in existing Tia DB", TABLE_TIA_PENDING_LIBRARY_IMPACTED_METHOD);
        }
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
     * Ensure the {@code tia_pending_library_forced_selection} table exists, creating it and its
     * parent {@code tia_library} table if necessary.
     *
     * @param connection the open JDBC connection.
     * @throws SQLException if the table cannot be created.
     */
    void ensurePendingLibraryForcedSelectionTableExists(Connection connection) throws SQLException {
        ensureLibraryTableExists(connection);
        if (!checkTableExists(connection, TABLE_TIA_PENDING_LIBRARY_FORCED_SELECTION)) {
            Statement statement = connection.createStatement();
            statement.executeUpdate(buildCreatePendingLibraryForcedSelectionTableSql());
            log.debug("Created {} table in existing Tia DB", TABLE_TIA_PENDING_LIBRARY_FORCED_SELECTION);
        }
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
     * Ensure the {@code tia_library_publish} table exists, creating it and its parent
     * {@code tia_library} table (plus the pending-stamp table the atomic publish write also
     * inserts into) if necessary. Mirrors the on-demand creation guard the sibling library
     * tables use so a publish persisted before the schema bootstrap ran still succeeds.
     *
     * @param connection the H2 connection to issue the DDL on
     * @throws SQLException if the DDL statement fails
     */
    void ensureLibraryPublishTableExists(Connection connection) throws SQLException {
        ensurePendingLibraryImpactedMethodTableExists(connection);
        if (!checkTableExists(connection, TABLE_TIA_LIBRARY_PUBLISH)) {
            Statement statement = connection.createStatement();
            statement.executeUpdate(buildCreateLibraryPublishTableSql());
            log.debug("Created {} table in existing Tia DB", TABLE_TIA_LIBRARY_PUBLISH);
        }
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
                // Distributed builds only; null on a single-host run, which is what makes such a
                // row indistinguishable from one written before distributed runs existed.
                + COL_RUN_ID + " VARCHAR(255), "
                + COL_WALL_CLOCK_MS + " BIGINT, "
                + COL_GROUP_COUNT + " INT, "
                + COL_GROUPS_AVAILABLE + " INT, "
                // Where the run came from. Nullable throughout: null means "not known", which is
                // what a row written before these columns existed, a run whose hostname would not
                // resolve, and a distributed build (no single host ran it) all genuinely are.
                + COL_RUN_SOURCE + " VARCHAR(32), "
                + COL_HOST_NAME + " VARCHAR(255), "
                // The selection-breakdown counters. Nullable with no DEFAULT: a row written before
                // this feature, or an all-tests run, reads back null - "not recorded" - rather than
                // a made-up zero.
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
     * Ensure the {@code tia_test_run_history} table and its timestamp index exist on an
     * already-populated DB. Idempotent via {@code CREATE TABLE/INDEX IF NOT EXISTS}; called
     * on every load and before each insert so DBs created before this feature gain the
     * table on first contact.
     *
     * @param connection the H2 connection to issue the DDL on
     * @throws SQLException if either DDL statement fails
     */
    void ensureTestRunHistoryTableExists(Connection connection) throws SQLException {
        Statement statement = connection.createStatement();
        statement.executeUpdate(buildCreateTestRunHistoryTableSql());
        statement.executeUpdate(buildCreateTestRunHistoryIndexSql());
        // Migration: add the per-run savings columns to DBs created before this feature. Old rows
        // default to 0 - their original baselines are unrecoverable.
        statement.executeUpdate("ALTER TABLE " + TABLE_TIA_TEST_RUN_HISTORY + " ADD COLUMN IF NOT EXISTS "
                + COL_TIME_SAVINGS + " BIGINT DEFAULT 0");
        statement.executeUpdate("ALTER TABLE " + TABLE_TIA_TEST_RUN_HISTORY + " ADD COLUMN IF NOT EXISTS "
                + COL_SAVINGS_PERCENT + " INT DEFAULT 0");
        // Migration: add the distributed-build columns to DBs created before distributed runs
        // existed. No DEFAULT, so old rows read back null - which is correct, they were not
        // distributed - rather than as a build split across zero groups.
        statement.executeUpdate("ALTER TABLE " + TABLE_TIA_TEST_RUN_HISTORY + " ADD COLUMN IF NOT EXISTS "
                + COL_RUN_ID + " VARCHAR(255)");
        statement.executeUpdate("ALTER TABLE " + TABLE_TIA_TEST_RUN_HISTORY + " ADD COLUMN IF NOT EXISTS "
                + COL_WALL_CLOCK_MS + " BIGINT");
        statement.executeUpdate("ALTER TABLE " + TABLE_TIA_TEST_RUN_HISTORY + " ADD COLUMN IF NOT EXISTS "
                + COL_GROUP_COUNT + " INT");
        // Migration: add the wall-clock savings columns and the groups available they are
        // measured against.
        statement.executeUpdate("ALTER TABLE " + TABLE_TIA_TEST_RUN_HISTORY + " ADD COLUMN IF NOT EXISTS "
                + COL_WALL_CLOCK_SAVINGS + " BIGINT DEFAULT 0");
        statement.executeUpdate("ALTER TABLE " + TABLE_TIA_TEST_RUN_HISTORY + " ADD COLUMN IF NOT EXISTS "
                + COL_WALL_CLOCK_SAVINGS_PERCENT + " INT DEFAULT 0");
        statement.executeUpdate("ALTER TABLE " + TABLE_TIA_TEST_RUN_HISTORY + " ADD COLUMN IF NOT EXISTS "
                + COL_GROUPS_AVAILABLE + " INT");
        // Migration: add the run-origin columns to DBs created before them. No DEFAULT, so old rows
        // read back null rather than being retro-labelled as something nobody actually recorded.
        statement.executeUpdate("ALTER TABLE " + TABLE_TIA_TEST_RUN_HISTORY + " ADD COLUMN IF NOT EXISTS "
                + COL_RUN_SOURCE + " VARCHAR(32)");
        statement.executeUpdate("ALTER TABLE " + TABLE_TIA_TEST_RUN_HISTORY + " ADD COLUMN IF NOT EXISTS "
                + COL_HOST_NAME + " VARCHAR(255)");
        // Migration: add the selection-breakdown counters to DBs created before this feature. No
        // DEFAULT, so old rows read back null - "not recorded" - rather than a made-up zero.
        statement.executeUpdate("ALTER TABLE " + TABLE_TIA_TEST_RUN_HISTORY + " ADD COLUMN IF NOT EXISTS "
                + COL_NUM_MODIFIED_TEST_FILES + " INT");
        statement.executeUpdate("ALTER TABLE " + TABLE_TIA_TEST_RUN_HISTORY + " ADD COLUMN IF NOT EXISTS "
                + COL_NUM_NEW_TEST_FILES + " INT");
        statement.executeUpdate("ALTER TABLE " + TABLE_TIA_TEST_RUN_HISTORY + " ADD COLUMN IF NOT EXISTS "
                + COL_NUM_PREVIOUSLY_FAILED + " INT");
        statement.executeUpdate("ALTER TABLE " + TABLE_TIA_TEST_RUN_HISTORY + " ADD COLUMN IF NOT EXISTS "
                + COL_NUM_UNSEALED_MAPPING + " INT");
        statement.executeUpdate("ALTER TABLE " + TABLE_TIA_TEST_RUN_HISTORY + " ADD COLUMN IF NOT EXISTS "
                + COL_NUM_PENDING_LIBRARY + " INT");
        // Migration: add the rerun flag. Old rows default to false - reruns were not told apart
        // before this column, so each old row is read as the run it was recorded as.
        statement.executeUpdate("ALTER TABLE " + TABLE_TIA_TEST_RUN_HISTORY + " ADD COLUMN IF NOT EXISTS "
                + COL_RERUN + " BOOLEAN DEFAULT FALSE");
        // Migration: add the selection mode. No DEFAULT, so old rows read back null - the mode
        // was not recorded for them - rather than being retro-labelled selective.
        statement.executeUpdate("ALTER TABLE " + TABLE_TIA_TEST_RUN_HISTORY + " ADD COLUMN IF NOT EXISTS "
                + COL_SELECTION_MODE + " VARCHAR(16)");
        ensureTestRunHistoryTriggerTableExists(connection);
    }

    /**
     * Create the run-history trigger child table if it does not exist. One row per changed method
     * and per fired static rule, keyed by the history row it belongs to, cascade-deleted with it.
     *
     * @param connection the open connection to create the table on
     * @throws SQLException if the DDL fails
     */
    private void ensureTestRunHistoryTriggerTableExists(Connection connection) throws SQLException {
        Statement statement = connection.createStatement();
        statement.executeUpdate(buildCreateTestRunHistoryTriggerTableSql());
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
     * DDL for the index on {@code tia_source_class.tia_test_suite_id}. Uses
     * {@code CREATE INDEX IF NOT EXISTS} so it's safe to call repeatedly - both for new DBs
     * (called from {@code createTiaDB}) and for migrating existing DBs missing the index.
     */
    private static String buildCreateSourceClassTestSuiteIndexSql() {
        return "CREATE INDEX IF NOT EXISTS " + COL_TIA_TEST_SUITE_ID + "_idx ON "
                + TABLE_TIA_SOURCE_CLASS + " (" + COL_TIA_TEST_SUITE_ID + ")";
    }

    /**
     * Migration: ensure the {@code tia_source_class.tia_test_suite_id} index exists on
     * an already-populated DB. Without this index, the suite-scoped
     * {@code DELETE FROM tia_source_class WHERE tia_test_suite_id = ?} degrades to a full scan of
     * {@code tia_source_class}.
     */
    private void ensureSourceClassTestSuiteIndexExists(Connection connection) throws SQLException {
        Statement statement = connection.createStatement();
        statement.executeUpdate(buildCreateSourceClassTestSuiteIndexSql());
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
     * Migration: ensure the two indexes backing the targeted select-tests queries exist on an
     * already-populated DB. Idempotent via {@code CREATE INDEX IF NOT EXISTS}. Note for large
     * existing DBs: the first contact after an upgrade builds these indexes over the full
     * edge table (millions of rows on large projects), which blocks that first caller for the
     * build duration.
     *
     * @param connection the H2 connection to issue the DDL on
     * @throws SQLException if either DDL statement fails
     */
    private void ensureTargetedQueryIndexesExist(Connection connection) throws SQLException {
        Statement statement = connection.createStatement();
        statement.executeUpdate(buildCreateSourceClassFilenameIndexSql());
        statement.executeUpdate(buildCreateSourceClassMethodMethodIdIndexSql());
    }

    /**
     * Migration: ensure the {@code tia_test_suite.developer_disabled} column exists on an
     * already-populated DB created before the flag was added. Idempotent via
     * {@code ADD COLUMN IF NOT EXISTS}; pre-existing rows default to {@code FALSE}.
     *
     * @param connection the connection to issue the DDL on
     * @throws SQLException if the DDL statement fails
     */
    private void ensureTestSuiteDeveloperDisabledColumnExists(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("ALTER TABLE " + TABLE_TIA_TEST_SUITE + " ADD COLUMN IF NOT EXISTS " +
                    COL_DEVELOPER_DISABLED + " BOOLEAN DEFAULT FALSE");
        }
    }

    /**
     * Migration: ensure the {@code tia_test_suite.unsealed} column exists on an already-populated
     * DB created before the flag was added. Idempotent via {@code ADD COLUMN IF NOT EXISTS};
     * pre-existing rows default to {@code FALSE}, which is correct - their mapping rows were
     * written by runs that did seal.
     *
     * @param connection the connection to issue the DDL on
     * @throws SQLException if the DDL statement fails
     */
    private void ensureTestSuiteUnsealedColumnExists(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("ALTER TABLE " + TABLE_TIA_TEST_SUITE + " ADD COLUMN IF NOT EXISTS " +
                    COL_UNSEALED + " BOOLEAN DEFAULT FALSE");
        }
    }

    /**
     * Migration: ensure the {@code tia_source_method.line_ranges} column exists on a DB created
     * before constructors and static initializers carried exact line ranges. Idempotent via
     * {@code ADD COLUMN IF NOT EXISTS}; pre-existing rows stay {@code NULL}, so their methods keep
     * matching by start-end line until the next mapping update rewrites the catalogue. See the
     * "Constructor and static initializer line ranges" chapter in {@code WIKI.md}.
     *
     * @param connection the connection to issue the DDL on
     * @throws SQLException if the DDL statement fails
     */
    private void ensureSourceMethodLineRangesColumnExists(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("ALTER TABLE " + TABLE_TIA_SOURCE_METHOD + " ADD COLUMN IF NOT EXISTS " +
                    COL_LINE_RANGES + " VARCHAR");
        }
    }

    /**
     * Migration: ensure the {@code tia_source_method.executed_run_count} and
     * {@code tia_source_method.triggered_run_count} columns exist on a DB created before the
     * per-method run stats were recorded. Idempotent via {@code ADD COLUMN IF NOT EXISTS};
     * pre-existing rows default to {@code 0} and start counting from the next mapping-update run.
     * See the "Method run stats" chapter in {@code WIKI.md}.
     *
     * @param connection the connection to issue the DDL on
     * @throws SQLException if the DDL statement fails
     */
    private void ensureSourceMethodRunCountColumnsExist(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("ALTER TABLE " + TABLE_TIA_SOURCE_METHOD + " ADD COLUMN IF NOT EXISTS " +
                    COL_EXECUTED_RUN_COUNT + " BIGINT DEFAULT 0");
            statement.executeUpdate("ALTER TABLE " + TABLE_TIA_SOURCE_METHOD + " ADD COLUMN IF NOT EXISTS " +
                    COL_TRIGGERED_RUN_COUNT + " BIGINT DEFAULT 0");
        }
    }

    /**
     * Migration: ensure the {@code tia_id_block} table exists. It holds one row per
     * application-assigned id space, recording the next unallocated value, so concurrent writers
     * can reserve disjoint id blocks instead of each reading {@code MAX(id)} and colliding.
     * Idempotent via {@code CREATE TABLE IF NOT EXISTS}.
     *
     * @param connection the connection to issue the DDL on
     * @throws SQLException if the DDL statement fails
     */
    private void ensureIdBlockTableExists(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + TABLE_TIA_ID_BLOCK + " ("
                    + COL_BLOCK_NAME + " VARCHAR(100) PRIMARY KEY, "
                    + COL_NEXT_VALUE + " BIGINT NOT NULL)");
        }
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
     * Created alongside the other distributed-run tables so the schema is a single migration.
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
     * Build the migration adding {@code tia_distributed_run.selection_mode} to a run table created
     * before modes were recorded. Idempotent via {@code ADD COLUMN IF NOT EXISTS}, and a no-op on a
     * table {@link #buildCreateDistributedRunTableSql} just created. Pre-existing rows read
     * {@code SELECTIVE}, the safe way round: a run wrongly read as selective reports every tracked
     * suite it was not assigned as ignored and so cannot become an all-tests run. Tia is pre-release,
     * so the {@code seed_run} flag it replaces is not backfilled; a seed run left open across the
     * upgrade must be re-planned.
     *
     * @return the {@code ALTER TABLE ... ADD COLUMN IF NOT EXISTS} statement for the column
     */
    private String buildAddSelectionModeColumnSql() {
        return "ALTER TABLE " + TABLE_TIA_DISTRIBUTED_RUN + " ADD COLUMN IF NOT EXISTS "
                + COL_SELECTION_MODE + " VARCHAR(16) DEFAULT 'SELECTIVE'";
    }

    /**
     * Build the migration dropping the {@code seed_run} column that {@code selection_mode}
     * replaced. Idempotent via {@code DROP COLUMN IF EXISTS}.
     *
     * @return the {@code ALTER TABLE ... DROP COLUMN IF EXISTS} statement
     */
    private String buildDropSeedRunColumnSql() {
        return "ALTER TABLE " + TABLE_TIA_DISTRIBUTED_RUN + " DROP COLUMN IF EXISTS seed_run";
    }

    /**
     * Build the migration that backfills the {@code tia_distributed_run.groups_available} column
     * onto a run table created before the column existed. Idempotent via {@code ADD COLUMN IF NOT
     * EXISTS}, and a no-op on a table {@link #buildCreateDistributedRunTableSql} just created.
     *
     * @return the {@code ALTER TABLE ... ADD COLUMN IF NOT EXISTS} statement for the column
     */
    private String buildAddGroupsAvailableColumnSql() {
        return "ALTER TABLE " + TABLE_TIA_DISTRIBUTED_RUN + " ADD COLUMN IF NOT EXISTS "
                + COL_GROUPS_AVAILABLE + " INT";
    }

    /**
     * Build the migration that backfills the {@code tia_distributed_run.run_source} column onto a
     * run table created before the column existed. Idempotent via {@code ADD COLUMN IF NOT
     * EXISTS}, and a no-op on a table {@link #buildCreateDistributedRunTableSql} just created.
     * Pre-existing rows read back null, which the sealer treats as "not recorded" and falls back to
     * resolving the source from its own environment - the behaviour before the column existed.
     *
     * @return the {@code ALTER TABLE ... ADD COLUMN IF NOT EXISTS} statement for the column
     */
    private String buildAddDistributedRunSourceColumnSql() {
        return "ALTER TABLE " + TABLE_TIA_DISTRIBUTED_RUN + " ADD COLUMN IF NOT EXISTS "
                + COL_RUN_SOURCE + " VARCHAR(32)";
    }

    /**
     * Build the migration that backfills the {@code tia_distributed_run_method_stage.line_ranges}
     * column onto a staging table created before constructors and static initializers carried
     * exact line ranges. Idempotent via {@code ADD COLUMN IF NOT EXISTS}; the staging write names
     * the column, so a store without it would fail the next runner's stage.
     *
     * @return the {@code ALTER TABLE ... ADD COLUMN IF NOT EXISTS} statement for the column
     */
    private String buildAddMethodStageLineRangesColumnSql() {
        return "ALTER TABLE " + TABLE_TIA_DISTRIBUTED_RUN_METHOD_STAGE + " ADD COLUMN IF NOT EXISTS "
                + COL_LINE_RANGES + " VARCHAR";
    }

    /**
     * Ensure the four distributed-run tables, the group-status index, the additive {@code
     * selection_mode}, {@code groups_available} and {@code run_source} columns, and the method
     * staging table's {@code line_ranges} column exist, and drop the {@code seed_run} column
     * {@code selection_mode} replaced. Idempotent via {@code CREATE TABLE/INDEX IF NOT EXISTS},
     * {@code ADD COLUMN IF NOT EXISTS} and {@code DROP COLUMN IF EXISTS}, so it both creates
     * everything on a new database and migrates a database created before these were recorded.
     *
     * <p>The group table's {@code suites_observed} and {@code suites_duration_ms} columns carry no
     * such migration: {@link #buildCreateDistributedRunGroupTableSql} names them both, and Tia is
     * pre-release with no external databases to preserve, so a database is simply created with
     * them. {@code selection_mode}, {@code groups_available} and {@code run_source} are added
     * because the plan write names them, so a store without any of them would fail the next plan.
     *
     * <p>All ten DDL statements are batched onto one {@link Statement} and sent with a single
     * {@code executeBatch} call. {@code ensureSchema} runs on every read path, so on a server-mode
     * or Postgres connection this collapses what would otherwise be ten wire round trips - paid on
     * every build whether or not distributed runs are in use - into one.
     *
     * <p>Also ensures the two run-id-keyed selection-breakdown tables via {@link
     * #ensureDistributedRunSelectionTablesExist}, since they belong to the same distributed-run
     * schema even though {@link JdbcDataStore#persistDistributedRunPlan} does not write them itself.
     *
     * @param connection the connection to issue the DDL on
     * @throws SQLException if any DDL statement fails
     */
    private void ensureDistributedRunTablesExist(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.addBatch(buildCreateDistributedRunTableSql());
            statement.addBatch(buildCreateDistributedRunGroupTableSql());
            statement.addBatch(buildCreateDistributedRunGroupSuiteTableSql());
            statement.addBatch(buildCreateDistributedRunMethodStageTableSql());
            statement.addBatch(buildCreateDistributedRunCoveredMethodTableSql());
            statement.addBatch(buildCreateDistributedRunGroupStatusIndexSql());
            statement.addBatch(buildAddSelectionModeColumnSql());
            statement.addBatch(buildDropSeedRunColumnSql());
            statement.addBatch(buildAddGroupsAvailableColumnSql());
            statement.addBatch(buildAddDistributedRunSourceColumnSql());
            statement.addBatch(buildAddMethodStageLineRangesColumnSql());
            statement.executeBatch();
        }
        ensureDistributedRunSelectionTablesExist(connection);
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
     * tables are cleared explicitly by {@link JdbcDataStore#persistDistributedRunSelectionDetails} and by
     * {@link JdbcDataStore#persistDistributedRunPlan}'s {@code tablesToClear}, not by a cascade.
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

    /**
     * Ensure the two run-id-keyed selection-breakdown tables exist: {@code
     * tia_distributed_run_selection} (the counters row) and {@code tia_distributed_run_trigger}
     * (the per-trigger rows), and that the trigger table carries the {@code trigger_method_id}
     * column the seal reads to count each changed method's triggered runs. Idempotent via
     * {@code CREATE TABLE IF NOT EXISTS} and {@code ADD COLUMN IF NOT EXISTS}.
     *
     * @param connection the connection to issue the DDL on
     * @throws SQLException if any DDL statement fails
     */
    private void ensureDistributedRunSelectionTablesExist(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.addBatch(buildCreateDistributedRunSelectionTableSql());
            statement.addBatch(buildCreateDistributedRunTriggerTableSql());
            // Migration for a trigger table created before triggers carried the changed method's
            // id. Pre-existing rows belong to an earlier build's plan and read back with no id.
            statement.addBatch("ALTER TABLE " + TABLE_TIA_DISTRIBUTED_RUN_TRIGGER
                    + " ADD COLUMN IF NOT EXISTS " + COL_TRIGGER_METHOD_ID + " INT");
            statement.executeBatch();
        }
    }

    /**
     * Migration: ensure the {@code tia_core.all_tests_run_time} and {@code tia_core.num_all_tests_runs}
     * columns exist on an already-populated DB created before the all-tests-run stats were added.
     * Idempotent via {@code ADD COLUMN IF NOT EXISTS}; pre-existing rows default to 0.
     *
     * @param connection the connection to issue the DDL on
     * @throws SQLException if either DDL statement fails
     */
    private void ensureTiaCoreAllTestsStatsColumnsExist(Connection connection) throws SQLException {
        Statement statement = connection.createStatement();
        statement.executeUpdate("ALTER TABLE " + TABLE_TIA_CORE + " ADD COLUMN IF NOT EXISTS " +
                COL_ALL_TESTS_RUN_TIME + " BIGINT DEFAULT 0");
        statement.executeUpdate("ALTER TABLE " + TABLE_TIA_CORE + " ADD COLUMN IF NOT EXISTS " +
                COL_NUM_ALL_TESTS_RUNS + " BIGINT DEFAULT 0");
    }

    /**
     * Migration: ensure the three {@code tia_core} columns carrying the two-part overhead model
     * exist on a DB created before it was added. Idempotent via {@code ADD COLUMN IF NOT EXISTS};
     * pre-existing rows default to 0, which is exactly the "never measured" state the estimate falls
     * back from, so an existing project keeps its current behaviour until its first distributed run
     * supplies the measurement.
     *
     * @param connection the connection to issue the DDL on
     * @throws SQLException if any DDL statement fails
     */
    private void ensureTiaCoreOverheadModelColumnsExist(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.addBatch("ALTER TABLE " + TABLE_TIA_CORE + " ADD COLUMN IF NOT EXISTS " +
                    COL_FIXED_OVERHEAD_MS + " BIGINT DEFAULT 0");
            statement.addBatch("ALTER TABLE " + TABLE_TIA_CORE + " ADD COLUMN IF NOT EXISTS " +
                    COL_CAPTURE_OVERHEAD_PER_SUITE_MS + " BIGINT DEFAULT 0");
            statement.addBatch("ALTER TABLE " + TABLE_TIA_CORE + " ADD COLUMN IF NOT EXISTS " +
                    COL_NUM_OVERHEAD_MEASUREMENTS + " BIGINT DEFAULT 0");
            statement.executeBatch();
        }
    }

    /**
     * Ensure the Tia schema is ready for use on this connection: create the DB on first
     * contact and run the idempotent migrations (missing tables and indexes) on existing DBs.
     * This is the single schema-bootstrap entry point - both the full-load path
     * ({@code readTiaDataFromDB}) and the targeted query methods call it, so neither depends
     * on the other having run first. Memoized per datastore instance: the schema can't
     * un-migrate within a JVM, so repeat calls skip the DDL round trips.
     *
     * @param connection the connection to check and migrate on
     * @return {@code true} if the Tia DB already existed before this call (or an earlier call
     *         on this instance bootstrapped it), {@code false} if it was just created empty
     * @throws SQLException if the existence check or any migration DDL fails
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
        ensureSourceClassTestSuiteIndexExists(connection);
        ensureTestRunHistoryTableExists(connection);
        ensureTargetedQueryIndexesExist(connection);
        ensureTestSuiteDeveloperDisabledColumnExists(connection);
        ensureTestSuiteUnsealedColumnExists(connection);
        ensureSourceMethodLineRangesColumnExists(connection);
        ensureSourceMethodRunCountColumnsExist(connection);
        ensureTiaCoreAllTestsStatsColumnsExist(connection);
        ensureTiaCoreOverheadModelColumnsExist(connection);
        ensureIdBlockTableExists(connection);
        ensureDistributedRunTablesExist(connection);

        schemaEnsured = true;
        return dbExisted;
    }
}
