# Seed / large mapping persist speedup — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Cut the seed / large mapping persist (currently ~30 min for ~979K `tia_source_class` + ~5.86M `tia_source_class_method` rows) to single-digit minutes with chunked multi-row inserts and (deferred) dropping/recreating secondary indexes on the seed.

**Architecture:** Two tiers inside `H2DataStore`. Tier 1 (always): replace the row-by-row `Statement` inserts with **chunked multi-row `INSERT ... VALUES (?,?),(?,?),...`** (1000 rows/statement) using application-assigned `tia_source_class` ids, keeping today's per-suite transaction. Tier 2 (seed only, detected by `tia_source_class` being empty): drop the three secondary read-indexes before the load and recreate them after.

> **Revision note (post-execution):** Task 2 below was first implemented with `addBatch`/`executeBatch` (committed `9272d9f`). Measurement showed H2's `executeBatch` does **not** pipeline over the wire - one round trip per row - so it only got server-mode persist from 30 min to 17.5 min. It was superseded by **chunked multi-row `INSERT`** (local-server benchmark: original row-by-row 38.3s -> multi-row 3.8s at 940K edges). The Task 2 code blocks below have been updated to the multi-row mechanism; the live implementation is in `H2DataStore` (`buildMultiRowInsertSql`, `insertRowsChunked`, `bindRows`, `persistTestSuiteClasses`).

**Tech Stack:** Java 8, H2 (embedded/server), JUnit 5, Gradle. All DB code is in `tia-core/src/main/java/org/tiatesting/core/persistence/h2/H2DataStore.java`.

## Global Constraints

- Java `sourceCompatibility = '1.8'` — no Java 9+ APIs.
- Unit tests use `// given` / `// when` / `// then` marker comments.
- Add a javadoc (purpose + `@param`/`@return`) to every new or modified method.
- ASCII hyphen `-` only; never the em-dash character.
- No backwards-compatibility shims: change signatures directly and update all callers in the same change.
- Staged delivery: stop for review after each Stage; print a commit-message summary. Do NOT `git commit` yourself unless the user asks — leave changes staged for the user to review and commit.
- Tia must run fast; this is the persist hot path.

---

## Stage 1 — Measurement harness + Tier 1 multi-row inserts

### Task 1: Seed-persist profiling harness

**Files:**
- Create: `tia-core/src/test/java/org/tiatesting/core/perf/ProfileSeedPersist.java`
- Reference (read for the pattern, do not modify): `tia-core/src/test/java/org/tiatesting/core/perf/ProfileSelectTests.java`

**Interfaces:**
- Consumes: `H2DataStore`, `H2ConnectionSettings.embedded(dir, branch)`, `TestSuiteTracker`, `ClassImpactTracker`, `MethodIdSet`, `MethodImpactTracker`, `TiaData`.
- Produces: a `main(String[])` that prints the wall-clock of a seed `persistTestSuites` against a synthetic large mapping. Used as a manual before/after measurement; not part of the automated suite.

- [ ] **Step 1: Write the harness**

```java
package org.tiatesting.core.perf;

import org.tiatesting.core.model.ClassImpactTracker;
import org.tiatesting.core.model.MethodIdSet;
import org.tiatesting.core.model.MethodImpactTracker;
import org.tiatesting.core.model.TestSuiteTracker;
import org.tiatesting.core.model.TiaData;
import org.tiatesting.core.persistence.h2.H2ConnectionSettings;
import org.tiatesting.core.persistence.h2.H2DataStore;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Manual profiler for the seed mapping persist. Builds a synthetic in-memory mapping of the rough
 * shape of a large project, then times a full {@code persistTestSuites} against an empty embedded
 * H2 DB. Run before and after a change to quantify the persist speedup. Not part of the automated
 * test suite (no assertions); invoke via {@code main}.
 *
 * <p>Defaults: 5000 suites x ~196 classes/suite x ~6 methods/class -> ~980K classes / ~5.9M edges.
 */
public final class ProfileSeedPersist {

    private ProfileSeedPersist() { }

    /**
     * Build the synthetic mapping, persist it once against a fresh DB, and print the elapsed time.
     *
     * @param args optional: [numSuites] [classesPerSuite] [methodsPerClass]
     * @throws Exception on any IO/DB failure
     */
    public static void main(String[] args) throws Exception {
        int numSuites = args.length > 0 ? Integer.parseInt(args[0]) : 5000;
        int classesPerSuite = args.length > 1 ? Integer.parseInt(args[1]) : 196;
        int methodsPerClass = args.length > 2 ? Integer.parseInt(args[2]) : 6;

        File dir = File.createTempFile("tia-seed-persist-", "");
        dir.delete();
        dir.mkdirs();
        H2DataStore dataStore = new H2DataStore(H2ConnectionSettings.embedded(dir.getAbsolutePath(), "perf"));
        dataStore.getTiaData(true); // bootstrap schema

        Map<String, TestSuiteTracker> suites = new HashMap<>();
        Map<Integer, MethodImpactTracker> methods = new HashMap<>();
        int methodId = 0;
        for (int s = 0; s < numSuites; s++) {
            TestSuiteTracker suite = new TestSuiteTracker("com.example.Suite" + s + "Test");
            List<ClassImpactTracker> classes = new ArrayList<>(classesPerSuite);
            for (int c = 0; c < classesPerSuite; c++) {
                MethodIdSet ids = new MethodIdSet();
                for (int m = 0; m < methodsPerClass; m++) {
                    int id = ++methodId;
                    ids.appendForBulkBuild(id);
                    methods.put(id, new MethodImpactTracker("com/example/Cls" + id + ".m.()V", 1, 5));
                }
                ids.finishBulkBuild();
                classes.add(new ClassImpactTracker("com/example/Cls" + s + "_" + c + ".java", ids));
            }
            suite.setClassesImpacted(classes);
            suites.put(suite.getName(), suite);
        }
        dataStore.persistSourceMethods(methods);

        System.out.println("Persisting " + numSuites + " suites (" + (long) numSuites * classesPerSuite
                + " classes, " + (long) numSuites * classesPerSuite * methodsPerClass + " edges)...");
        long start = System.currentTimeMillis();
        dataStore.persistTestSuites(suites);
        long elapsed = System.currentTimeMillis() - start;
        System.out.println("persistTestSuites took " + (elapsed / 1000.0) + "s");

        dataStore.close();
        for (File f : dir.listFiles()) { f.delete(); }
        dir.delete();
    }
}
```

- [ ] **Step 2: Compile**

Run: `./gradlew :tia-core:compileTestJava`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Capture the baseline number**

Run (smaller size keeps it quick while still representative of the per-row cost; scale up if you want the full number):
`./gradlew :tia-core:test -PmainClass --offline` is not how this runs — instead run the class directly, e.g. from the IDE, or:
`./gradlew :tia-core:testClasses && java -cp "$(./gradlew -q :tia-core:printTestClasspath 2>/dev/null || echo tia-core/build/classes/java/test:tia-core/build/classes/java/main)" org.tiatesting.core.perf.ProfileSeedPersist 1000 196 6`
Expected: prints `persistTestSuites took <N>s`. Record this baseline (e.g. with 1000 suites) in the task PR/notes. If the classpath command is awkward in your environment, run `main` from the IDE instead.

- [ ] **Step 4: Commit**

```bash
git add tia-core/src/test/java/org/tiatesting/core/perf/ProfileSeedPersist.java
git commit -m "Add seed-persist profiling harness for measuring mapping persist time"
```

---

### Task 2: Tier 1 — multi-row class + edge inserts with app-side ids

**Files:**
- Modify: `tia-core/src/main/java/org/tiatesting/core/persistence/h2/H2DataStore.java` (`persistTestSuites(Connection, Collection, boolean)`, `persistTestSuiteClasses`, `persistTestSuiteClassMethods`; add SQL constants + two helpers)
- Test: `tia-core/src/test/java/org/tiatesting/core/persistence/h2/H2DataStoreBatchedPersistTest.java` (create)

**Interfaces:**
- Consumes: existing `getConnection()`, `COL_*` / `TABLE_*` constants, `TestSuiteTracker.getClassesImpacted()`, `ClassImpactTracker.getSourceFilename()` / `.getMethodsImpacted()` (a `MethodIdSet implements Set<Integer>`), `getTiaData(true)` for read-back.
- Produces: unchanged public method `void persistTestSuites(Map<String, TestSuiteTracker>)`. New private helpers `long readMaxSourceClassId(Connection)`, `void restartSourceClassIdentity(Connection, long)`, `String buildMultiRowInsertSql(...)`, `void insertRowsChunked(...)`, `void bindRows(...)`. New private constants `INSERT_CHUNK`, `INSERT_SOURCE_CLASS_CHUNK_SQL`, `INSERT_SOURCE_CLASS_METHOD_CHUNK_SQL`.

- [ ] **Step 1: Write the failing test (round-trip + app-side ids + identity restart)**

```java
package org.tiatesting.core.persistence.h2;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tiatesting.core.model.ClassImpactTracker;
import org.tiatesting.core.model.MethodIdSet;
import org.tiatesting.core.model.MethodImpactTracker;
import org.tiatesting.core.model.TestSuiteTracker;
import org.tiatesting.core.model.TiaData;

import java.io.File;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the batched mapping-insert path in {@link H2DataStore}: classes and edges round-trip
 * correctly, {@code tia_source_class} ids are assigned application-side from {@code MAX(id)+1}, the
 * identity sequence is reseated afterward, and a re-persist of one suite updates only that suite.
 */
class H2DataStoreBatchedPersistTest {

    private H2DataStore dataStore;
    private File tempDir;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = File.createTempFile("tia-batched-", "");
        tempDir.delete();
        tempDir.mkdirs();
        dataStore = new H2DataStore(H2ConnectionSettings.embedded(tempDir.getAbsolutePath(), "test"));
        dataStore.getTiaData(true);
    }

    @AfterEach
    void tearDown() {
        dataStore.close();
        if (tempDir != null && tempDir.exists()) {
            for (File f : tempDir.listFiles()) { f.delete(); }
            tempDir.delete();
        }
    }

    /** Build a tracker for one suite covering the given (filename -> method ids) classes. */
    private TestSuiteTracker suite(String name, Map<String, int[]> classes) {
        TestSuiteTracker t = new TestSuiteTracker(name);
        List<ClassImpactTracker> impacted = new ArrayList<>();
        for (Map.Entry<String, int[]> e : classes.entrySet()) {
            MethodIdSet ids = new MethodIdSet();
            for (int id : e.getValue()) { ids.appendForBulkBuild(id); }
            ids.finishBulkBuild();
            impacted.add(new ClassImpactTracker(e.getKey(), ids));
        }
        t.setClassesImpacted(impacted);
        return t;
    }

    private void seedMethods(int... ids) {
        Map<Integer, MethodImpactTracker> methods = new HashMap<>();
        for (int id : ids) { methods.put(id, new MethodImpactTracker("com/example/C" + id + ".m.()V", 1, 5)); }
        dataStore.persistSourceMethods(methods);
    }

    @Test
    void persistsClassesAndEdges_andRoundTripsThroughFullLoad() {
        // given
        seedMethods(1, 2, 3);
        Map<String, int[]> classes = new HashMap<>();
        classes.put("com/example/Foo.java", new int[]{1, 2});
        classes.put("com/example/Bar.java", new int[]{3});
        Map<String, TestSuiteTracker> suites = new HashMap<>();
        suites.put("com.example.FooTest", suite("com.example.FooTest", classes));

        // when
        dataStore.persistTestSuites(suites);
        TiaData loaded = dataStore.getTiaData(true);

        // then - the suite's two tracked classes and their method ids are read back
        TestSuiteTracker round = loaded.getTestSuitesTracked().get("com.example.FooTest");
        assertEquals(2, round.getClassesImpacted().size());
        int totalEdges = round.getClassesImpacted().stream().mapToInt(c -> c.getMethodsImpacted().size()).sum();
        assertEquals(3, totalEdges);
    }

    @Test
    void assignsContiguousIdsAndReseatsIdentity() throws Exception {
        // given
        seedMethods(1, 2);
        Map<String, int[]> classes = new HashMap<>();
        classes.put("com/example/Foo.java", new int[]{1});
        classes.put("com/example/Bar.java", new int[]{2});
        Map<String, TestSuiteTracker> suites = new HashMap<>();
        suites.put("com.example.FooTest", suite("com.example.FooTest", classes));

        // when
        dataStore.persistTestSuites(suites);

        // then - ids are 1..2, and a subsequent auto-increment insert gets id 3 (identity reseated)
        try (Connection c = java.sql.DriverManager.getConnection(dataStore.getJdbcUrl(), "sa", "");
             Statement st = c.createStatement()) {
            ResultSet rs = st.executeQuery("SELECT MIN(id), MAX(id), COUNT(*) FROM tia_source_class");
            rs.next();
            assertEquals(1, rs.getLong(1));
            assertEquals(2, rs.getLong(2));
            assertEquals(2, rs.getLong(3));
            st.executeUpdate("INSERT INTO tia_source_class (tia_test_suite_id, source_filename) "
                    + "VALUES (999, 'x')", Statement.RETURN_GENERATED_KEYS);
            ResultSet keys = st.getGeneratedKeys();
            keys.next();
            assertTrue(keys.getLong(1) >= 3, "identity must be reseated past the app-assigned ids");
        }
    }

    @Test
    void rePersistOneSuite_updatesOnlyThatSuite() {
        // given - two suites persisted
        seedMethods(1, 2, 3, 4);
        Map<String, TestSuiteTracker> first = new HashMap<>();
        first.put("com.example.ATest", suite("com.example.ATest", singleClass("com/example/A.java", 1, 2)));
        first.put("com.example.BTest", suite("com.example.BTest", singleClass("com/example/B.java", 3)));
        dataStore.persistTestSuites(first);

        // when - re-persist only ATest with a different class/methods
        Map<String, TestSuiteTracker> update = new HashMap<>();
        update.put("com.example.ATest", suite("com.example.ATest", singleClass("com/example/A2.java", 4)));
        dataStore.persistTestSuites(update);

        // then - ATest now maps A2.java(4); BTest still maps B.java(3)
        TiaData loaded = dataStore.getTiaData(true);
        TestSuiteTracker a = loaded.getTestSuitesTracked().get("com.example.ATest");
        assertEquals(1, a.getClassesImpacted().size());
        assertEquals("com/example/A2.java", a.getClassesImpacted().get(0).getSourceFilename());
        TestSuiteTracker b = loaded.getTestSuitesTracked().get("com.example.BTest");
        assertEquals("com/example/B.java", b.getClassesImpacted().get(0).getSourceFilename());
    }

    private Map<String, int[]> singleClass(String file, int... ids) {
        Map<String, int[]> m = new HashMap<>();
        m.put(file, ids);
        return m;
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :tia-core:test --tests "org.tiatesting.core.persistence.h2.H2DataStoreBatchedPersistTest"`
Expected: FAIL — `assignsContiguousIdsAndReseatsIdentity` fails on the reseat assertion (today ids come from auto-increment without an explicit reseat, and the round-trip tests may pass against the old code; the reseat test is the one that pins the new behaviour). If all three pass against the old code, that is acceptable — they are characterization tests for the rewrite; proceed and keep them green.

- [ ] **Step 3: Add the SQL constants and helpers**

Add near the other `private static final String` constants in `H2DataStore`:

```java
    private static final int INSERT_CHUNK = 1000;
    private static final String SOURCE_CLASS_COLS = COL_ID + ", " + COL_TIA_TEST_SUITE_ID + ", " + COL_SOURCE_FILENAME;
    private static final String SOURCE_CLASS_METHOD_COLS = COL_TIA_SOURCE_CLASS_ID + ", " + COL_TIA_SOURCE_METHOD_ID;
    private static final String INSERT_SOURCE_CLASS_CHUNK_SQL =
            buildMultiRowInsertSql(TABLE_TIA_SOURCE_CLASS, SOURCE_CLASS_COLS, 3, INSERT_CHUNK);
    private static final String INSERT_SOURCE_CLASS_METHOD_CHUNK_SQL =
            buildMultiRowInsertSql(TABLE_TIA_SOURCE_CLASS_METHOD, SOURCE_CLASS_METHOD_COLS, 2, INSERT_CHUNK);

    private static String buildMultiRowInsertSql(String table, String columnsCsv, int paramsPerRow, int rows) {
        StringBuilder tuple = new StringBuilder("(");
        for (int p = 0; p < paramsPerRow; p++) { tuple.append(p > 0 ? ", ?" : "?"); }
        tuple.append(')');
        StringBuilder sb = new StringBuilder("INSERT INTO ").append(table).append(" (").append(columnsCsv).append(") VALUES ");
        for (int r = 0; r < rows; r++) { if (r > 0) { sb.append(','); } sb.append(tuple); }
        return sb.toString();
    }
```

(`buildMultiRowInsertSql` builds `INSERT ... VALUES (?,?,?),(?,?,?),...` with `rows` tuples; the chunk constants use `INSERT_CHUNK` rows.)

Add these helper methods to `H2DataStore`:

```java
    /**
     * Read the highest existing {@code tia_source_class} id, or {@code 0} when the table is empty.
     * Ids are auto-increment starting at 1, so {@code 0} unambiguously means "empty" - used both to
     * detect a seed and to derive the first application-assigned id ({@code result + 1}).
     *
     * @param connection the H2 connection
     * @return the maximum stored id, or {@code 0} when no rows exist
     * @throws SQLException if the query fails
     */
    private long readMaxSourceClassId(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT MAX(" + COL_ID + ") FROM " + TABLE_TIA_SOURCE_CLASS)) {
            rs.next();
            long max = rs.getLong(1);
            return rs.wasNull() ? 0L : max;
        }
    }

    /**
     * Reseat the {@code tia_source_class} identity sequence so the next auto-increment value is
     * {@code nextId}. Required after inserting rows with explicit ids, so any later insert that
     * relies on auto-increment cannot collide with an application-assigned id.
     *
     * @param connection the H2 connection
     * @param nextId the value the identity should next produce
     * @throws SQLException if the DDL fails
     */
    private void restartSourceClassIdentity(Connection connection, long nextId) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("ALTER TABLE " + TABLE_TIA_SOURCE_CLASS
                    + " ALTER COLUMN " + COL_ID + " RESTART WITH " + nextId);
        }
    }
```

- [ ] **Step 4: Rewrite the insert path**

Replace the class-mapping persist in `persistTestSuites(Connection, Collection<TestSuiteTracker>, boolean)`. The suite MERGE loop stays (suites are few); prepare the two statements once, track `nextId` in a one-element holder, and call the new multi-row per-suite method:

```java
    private void persistTestSuites(Connection connection, Collection<TestSuiteTracker> testSuites,
                                   boolean includeClassMappings) throws SQLException {
        if (testSuites.isEmpty()){
            return;
        }

        Statement statement = connection.createStatement();

        // Two full-chunk multi-row statements prepared once and reused for the whole persist, so
        // rows go in INSERT_CHUNK at a time (one round trip per chunk) instead of one per row.
        long[] nextSourceClassId = null;
        PreparedStatement classChunkPs = null;
        PreparedStatement edgeChunkPs = null;
        if (includeClassMappings){
            nextSourceClassId = new long[]{ readMaxSourceClassId(connection) + 1 };
            classChunkPs = connection.prepareStatement(INSERT_SOURCE_CLASS_CHUNK_SQL);
            edgeChunkPs = connection.prepareStatement(INSERT_SOURCE_CLASS_METHOD_CHUNK_SQL);
        }

        try {
            for (TestSuiteTracker testSuite : testSuites){
                String disabledColumn = includeClassMappings ? ", " + COL_DEVELOPER_DISABLED : "";
                String disabledValue = includeClassMappings ? ", " + testSuite.isDeveloperDisabled() : "";

                String mergeSql = "MERGE INTO " + TABLE_TIA_TEST_SUITE + " (" +
                        COL_NAME + ", " +
                        COL_NUM_RUNS + ", " +
                        COL_AVG_RUN_TIME + ", " +
                        COL_NUM_SUCCESS_RUNS + ", " +
                        COL_NUM_FAIL_RUNS + disabledColumn + ") " +
                        "KEY (" + COL_NAME + ") VALUES ('" +
                        testSuite.getName() + "', " +
                        testSuite.getTestStats().getNumRuns() + ", " +
                        testSuite.getTestStats().getAvgRunTime() + ", " +
                        testSuite.getTestStats().getNumSuccessRuns() + ", " +
                        testSuite.getTestStats().getNumFailRuns() + disabledValue + ")";

                statement.executeUpdate(mergeSql, Statement.RETURN_GENERATED_KEYS);

                if (includeClassMappings && !testSuite.getClassesImpacted().isEmpty()){
                    ResultSet rs = statement.getGeneratedKeys();
                    rs.next();
                    persistTestSuiteClasses(connection, rs.getLong(COL_ID),
                            testSuite.getClassesImpacted(), classChunkPs, edgeChunkPs, nextSourceClassId);
                }
            }

            if (includeClassMappings){
                restartSourceClassIdentity(connection, nextSourceClassId[0]);
            }
        } finally {
            if (classChunkPs != null){ classChunkPs.close(); }
            if (edgeChunkPs != null){ edgeChunkPs.close(); }
        }
    }
```

Replace `persistTestSuiteClasses` and delete `persistTestSuiteClassMethods` (its logic folds in):

```java
    /**
     * Re-persist one suite's source-class -> method mapping using the shared, reused prepared
     * statements. Deletes the suite's existing classes and edges (a no-op on a seed where the table
     * is empty), assigns each class an application-side id from {@code nextId}, and inserts the
     * class rows and their edge rows via chunked multi-row inserts. Kept inside a per-suite
     * transaction so a failure leaves the suite's previous mapping intact.
     *
     * @param connection the H2 connection
     * @param testSuiteId the id of the suite these classes belong to
     * @param sourceClasses the suite's impacted classes (each with its method-id set)
     * @param classChunkPs reused full-chunk multi-row insert for {@code tia_source_class}
     * @param edgeChunkPs reused full-chunk multi-row insert for {@code tia_source_class_method}
     * @param nextId one-element holder for the next application-assigned class id; advanced in place
     * @throws SQLException if any insert/delete fails (the suite's transaction is rolled back first)
     */
    private void persistTestSuiteClasses(Connection connection, long testSuiteId,
                                         List<ClassImpactTracker> sourceClasses,
                                         PreparedStatement classChunkPs, PreparedStatement edgeChunkPs,
                                         long[] nextId) throws SQLException {
        if (sourceClasses.isEmpty()){
            return;
        }

        // Materialise this suite's class rows and edge rows, then insert them with chunked multi-row
        // statements. Per-suite, so the buffers are bounded by the suite's size.
        List<Object[]> classRows = new ArrayList<>(sourceClasses.size());
        List<Object[]> edgeRows = new ArrayList<>();
        for (ClassImpactTracker sourceClass : sourceClasses){
            long classId = nextId[0]++;
            classRows.add(new Object[]{ classId, testSuiteId, sourceClass.getSourceFilename() });
            for (Integer methodId : sourceClass.getMethodsImpacted()){
                edgeRows.add(new Object[]{ classId, methodId });
            }
        }

        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);

        try (Statement statement = connection.createStatement()) {
            String deleteClassMethodSql = "DELETE FROM " + TABLE_TIA_SOURCE_CLASS_METHOD + " WHERE " + COL_TIA_SOURCE_CLASS_ID +
                    " IN (SELECT " + COL_ID + " FROM " + TABLE_TIA_SOURCE_CLASS +
                    " WHERE " + COL_TIA_TEST_SUITE_ID + " = " + testSuiteId + ")";
            statement.executeUpdate(deleteClassMethodSql);

            String deleteClassSql = "DELETE FROM " + TABLE_TIA_SOURCE_CLASS + " WHERE " + COL_TIA_TEST_SUITE_ID + " = " + testSuiteId;
            statement.executeUpdate(deleteClassSql);

            insertRowsChunked(connection, classChunkPs, TABLE_TIA_SOURCE_CLASS, SOURCE_CLASS_COLS, 3, classRows);
            insertRowsChunked(connection, edgeChunkPs, TABLE_TIA_SOURCE_CLASS_METHOD, SOURCE_CLASS_METHOD_COLS, 2, edgeRows);

            connection.commit();
        } catch (Exception e) {
            try {
                connection.rollback();
            } catch (SQLException rollbackEx) {
                e.addSuppressed(rollbackEx);
            }
            throw e;
        } finally {
            try {
                connection.setAutoCommit(previousAutoCommit);
            } catch (SQLException restoreEx) {
                log.debug("Failed to restore autoCommit on connection: {}", restoreEx.getMessage());
            }
        }
    }

    /**
     * Insert {@code rows} into {@code table} using multi-row inserts: full INSERT_CHUNK-row chunks go
     * through the reused {@code fullChunkPs} (one round trip per chunk), and any remainder
     * (< INSERT_CHUNK rows) is inserted with a single one-off statement sized to it. This collapses
     * the per-row round trips that dominate a remote-server persist.
     */
    private void insertRowsChunked(Connection connection, PreparedStatement fullChunkPs, String table,
                                   String columnsCsv, int paramsPerRow, List<Object[]> rows) throws SQLException {
        int total = rows.size();
        int index = 0;
        while (total - index >= INSERT_CHUNK){
            bindRows(fullChunkPs, rows, index, INSERT_CHUNK, paramsPerRow);
            fullChunkPs.executeUpdate();
            index += INSERT_CHUNK;
        }
        int remainder = total - index;
        if (remainder > 0){
            String remainderSql = buildMultiRowInsertSql(table, columnsCsv, paramsPerRow, remainder);
            try (PreparedStatement remainderPs = connection.prepareStatement(remainderSql)) {
                bindRows(remainderPs, rows, index, remainder, paramsPerRow);
                remainderPs.executeUpdate();
            }
        }
    }

    /**
     * Bind {@code count} rows from {@code rows} (starting at {@code from}) into a multi-row insert
     * statement, laying each row's values out consecutively across the placeholders.
     */
    private void bindRows(PreparedStatement ps, List<Object[]> rows, int from, int count, int paramsPerRow) throws SQLException {
        for (int r = 0; r < count; r++){
            Object[] values = rows.get(from + r);
            for (int p = 0; p < paramsPerRow; p++){
                ps.setObject(r * paramsPerRow + p + 1, values[p]);
            }
        }
    }
```

Remove the now-unused `persistTestSuiteClassMethods` method. `import java.sql.PreparedStatement;` is already present in the file.

- [ ] **Step 5: Run the new tests**

Run: `./gradlew :tia-core:test --tests "org.tiatesting.core.persistence.h2.H2DataStoreBatchedPersistTest"`
Expected: PASS (all three).

- [ ] **Step 6: Run the full tia-core suite (persist path is widely exercised)**

Run: `./gradlew :tia-core:test`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Re-measure with the harness**

Re-run `ProfileSeedPersist` (same size as the Task 1 baseline). Expected: substantially faster than the baseline (no more row-by-row class inserts / per-class prepares). Record the number.

- [ ] **Step 8: Commit**

```bash
git add tia-core/src/main/java/org/tiatesting/core/persistence/h2/H2DataStore.java \
        tia-core/src/test/java/org/tiatesting/core/persistence/h2/H2DataStoreBatchedPersistTest.java
git commit -m "Batch tia_source_class / tia_source_class_method inserts with app-side ids"
```

**STOP — Stage 1 review.** Print a summary: files changed, the before/after harness numbers, and that incremental per-suite atomicity is preserved (per-suite transaction kept). Wait for review before Stage 2.

---

## Stage 2 — Tier 2: drop/recreate secondary indexes on the seed

### Task 3: Seed-only index drop and recreate

**Files:**
- Modify: `tia-core/src/main/java/org/tiatesting/core/persistence/h2/H2DataStore.java` (`persistTestSuites(Connection, Collection, boolean)`; add `dropSourceMappingSecondaryIndexes` + `createSourceMappingSecondaryIndexes`)
- Test: `tia-core/src/test/java/org/tiatesting/core/persistence/h2/H2DataStoreSeedIndexTest.java` (create)

**Interfaces:**
- Consumes: `readMaxSourceClassId(Connection)` (Task 2), the existing index DDL builders `buildCreateSourceClassTestSuiteIndexSql()`, `buildCreateSourceClassFilenameIndexSql()`, `buildCreateSourceClassMethodMethodIdIndexSql()`.
- Produces: the seed persist drops then recreates the three secondary read-indexes; non-empty persists leave indexes untouched.

- [ ] **Step 1: Write the failing test**

```java
package org.tiatesting.core.persistence.h2;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tiatesting.core.model.ClassImpactTracker;
import org.tiatesting.core.model.MethodIdSet;
import org.tiatesting.core.model.MethodImpactTracker;
import org.tiatesting.core.model.TestSuiteTracker;
import org.tiatesting.core.model.TiaData;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the seed mapping persist (empty {@code tia_source_class}) ends with the three secondary
 * read-indexes present and the data correct, and that a persist over a non-empty table (incremental
 * / crashed-seed retry) still produces correct data with the indexes intact.
 */
class H2DataStoreSeedIndexTest {

    private static final Set<String> SECONDARY_INDEXES = new HashSet<>(java.util.Arrays.asList(
            "TIA_TEST_SUITE_ID_IDX", "IDX_SOURCE_CLASS_FILENAME", "IDX_SOURCE_CLASS_METHOD_METHOD_ID"));

    private H2DataStore dataStore;
    private File tempDir;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = File.createTempFile("tia-seedidx-", "");
        tempDir.delete();
        tempDir.mkdirs();
        dataStore = new H2DataStore(H2ConnectionSettings.embedded(tempDir.getAbsolutePath(), "test"));
        dataStore.getTiaData(true);
    }

    @AfterEach
    void tearDown() {
        dataStore.close();
        if (tempDir != null && tempDir.exists()) {
            for (File f : tempDir.listFiles()) { f.delete(); }
            tempDir.delete();
        }
    }

    private TestSuiteTracker suite(String name, String file, int... methodIds) {
        Map<Integer, MethodImpactTracker> methods = new HashMap<>();
        MethodIdSet ids = new MethodIdSet();
        for (int id : methodIds) { ids.appendForBulkBuild(id); methods.put(id, new MethodImpactTracker("c" + id, 1, 2)); }
        ids.finishBulkBuild();
        dataStore.persistSourceMethods(methods);
        TestSuiteTracker t = new TestSuiteTracker(name);
        List<ClassImpactTracker> cls = new ArrayList<>();
        cls.add(new ClassImpactTracker(file, ids));
        t.setClassesImpacted(cls);
        return t;
    }

    private Set<String> presentSecondaryIndexes() throws Exception {
        Set<String> found = new HashSet<>();
        try (Connection c = DriverManager.getConnection(dataStore.getJdbcUrl(), "sa", "");
             PreparedStatement ps = c.prepareStatement(
                     "SELECT INDEX_NAME FROM INFORMATION_SCHEMA.INDEXES WHERE INDEX_NAME IN (?, ?, ?)")) {
            ps.setString(1, "TIA_TEST_SUITE_ID_IDX");
            ps.setString(2, "IDX_SOURCE_CLASS_FILENAME");
            ps.setString(3, "IDX_SOURCE_CLASS_METHOD_METHOD_ID");
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) { found.add(rs.getString(1)); }
            }
        }
        return found;
    }

    @Test
    void seedPersist_endsWithSecondaryIndexesPresentAndDataCorrect() throws Exception {
        // given - empty tia_source_class (fresh DB)
        Map<String, TestSuiteTracker> suites = new HashMap<>();
        suites.put("com.example.FooTest", suite("com.example.FooTest", "com/example/Foo.java", 1, 2));

        // when
        dataStore.persistTestSuites(suites);

        // then - all three secondary indexes are present and the mapping reads back
        assertEquals(SECONDARY_INDEXES, presentSecondaryIndexes());
        TiaData loaded = dataStore.getTiaData(true);
        assertEquals(2, loaded.getTestSuitesTracked().get("com.example.FooTest")
                .getClassesImpacted().get(0).getMethodsImpacted().size());
    }

    @Test
    void persistOverNonEmptyTable_keepsIndexesAndData() throws Exception {
        // given - a first seed persist (table now non-empty)
        Map<String, TestSuiteTracker> first = new HashMap<>();
        first.put("com.example.ATest", suite("com.example.ATest", "com/example/A.java", 1));
        dataStore.persistTestSuites(first);

        // when - a second persist over the non-empty table (incremental / crashed-seed retry shape)
        Map<String, TestSuiteTracker> second = new HashMap<>();
        second.put("com.example.BTest", suite("com.example.BTest", "com/example/B.java", 2));
        dataStore.persistTestSuites(second);

        // then - indexes still present and both suites' mappings are correct
        assertEquals(SECONDARY_INDEXES, presentSecondaryIndexes());
        TiaData loaded = dataStore.getTiaData(true);
        assertTrue(loaded.getTestSuitesTracked().containsKey("com.example.ATest"));
        assertTrue(loaded.getTestSuitesTracked().containsKey("com.example.BTest"));
    }
}
```

- [ ] **Step 2: Run to verify it fails appropriately**

Run: `./gradlew :tia-core:test --tests "org.tiatesting.core.persistence.h2.H2DataStoreSeedIndexTest"`
Expected: both tests likely PASS against the Stage 1 code (indexes are created by `ensureSchema` and never dropped). These are characterization tests guarding that the seed index drop/recreate does not lose an index. Proceed - Step 3 adds the drop/recreate and they must stay green.

- [ ] **Step 3: Add the index drop / create helpers**

```java
    /**
     * Drop the three secondary read-indexes on the source-mapping tables before a seed bulk load, so
     * they are not maintained per row during the insert. Idempotent ({@code DROP INDEX IF EXISTS}).
     * The primary keys are left in place.
     *
     * @param connection the H2 connection
     * @throws SQLException if a DROP fails
     */
    private void dropSourceMappingSecondaryIndexes(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("DROP INDEX IF EXISTS " + COL_TIA_TEST_SUITE_ID + "_idx");
            statement.executeUpdate("DROP INDEX IF EXISTS idx_source_class_filename");
            statement.executeUpdate("DROP INDEX IF EXISTS idx_source_class_method_method_id");
        }
    }

    /**
     * Recreate the three secondary read-indexes after a seed bulk load, reusing the canonical index
     * DDL. Idempotent ({@code CREATE INDEX IF NOT EXISTS}).
     *
     * @param connection the H2 connection
     * @throws SQLException if a CREATE fails
     */
    private void createSourceMappingSecondaryIndexes(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(buildCreateSourceClassTestSuiteIndexSql());
            statement.executeUpdate(buildCreateSourceClassFilenameIndexSql());
            statement.executeUpdate(buildCreateSourceClassMethodMethodIdIndexSql());
        }
    }
```

- [ ] **Step 4: Gate the bulk path on an empty table**

In `persistTestSuites(Connection, Collection, boolean)`, the `includeClassMappings` block computes `nextSourceClassId` from `readMaxSourceClassId`. Capture the seed flag from the same read and wrap the suite loop with drop/recreate when seeding:

```java
        long[] nextSourceClassId = null;
        PreparedStatement classChunkPs = null;
        PreparedStatement edgeChunkPs = null;
        boolean seed = false;
        if (includeClassMappings){
            long maxId = readMaxSourceClassId(connection);
            seed = (maxId == 0L); // empty table -> seed (full rebuild, runs alone)
            nextSourceClassId = new long[]{ maxId + 1 };
            classChunkPs = connection.prepareStatement(INSERT_SOURCE_CLASS_CHUNK_SQL);
            edgeChunkPs = connection.prepareStatement(INSERT_SOURCE_CLASS_METHOD_CHUNK_SQL);
            if (seed){
                dropSourceMappingSecondaryIndexes(connection);
            }
        }

        try {
            // ... unchanged suite loop ...

            if (includeClassMappings){
                restartSourceClassIdentity(connection, nextSourceClassId[0]);
                if (seed){
                    createSourceMappingSecondaryIndexes(connection);
                }
            }
        } finally {
            // ... unchanged statement closes ...
        }
```

(On a seed the per-suite `DELETE`s in `persistTestSuiteClasses` hit an empty table and are harmless no-ops; leaving them keeps the method single-purpose.)

- [ ] **Step 5: Run the seed-index tests**

Run: `./gradlew :tia-core:test --tests "org.tiatesting.core.persistence.h2.H2DataStoreSeedIndexTest"`
Expected: PASS (both).

- [ ] **Step 6: Run the full tia-core suite**

Run: `./gradlew :tia-core:test`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Re-measure**

Re-run `ProfileSeedPersist` (same size as before). Expected: faster again than the Stage 1 number on the seed (no per-row secondary-index maintenance). Record it.

- [ ] **Step 8: Commit**

```bash
git add tia-core/src/main/java/org/tiatesting/core/persistence/h2/H2DataStore.java \
        tia-core/src/test/java/org/tiatesting/core/persistence/h2/H2DataStoreSeedIndexTest.java
git commit -m "Drop and recreate source-mapping secondary indexes around the seed bulk load"
```

**STOP — Stage 2 review.** Print a summary: files changed, before/after numbers across both stages, and confirmation that select-tests reads still hit the recreated indexes (the existing `H2DataStoreTargetedQueriesTest` covers the index-backed read path - confirm it is green).

---

## Self-review notes (for the implementer)

- **Spec coverage:** Tier 1 (Task 2), Tier 2 / seed gate via empty table (Task 3), measurement harness (Task 1), incremental safety (per-suite transaction kept in Task 2), crashed-seed retry (Task 3 `persistOverNonEmptyTable_*`). The atomicity trade (seed no longer per-suite atomic) does NOT apply here because Task 2 keeps the per-suite transaction; the only seed-time non-atomic action is the index DDL, handled by `ensureSchema`'s `CREATE INDEX IF NOT EXISTS` on retry.
- **`MethodIdSet`** implements `Set<Integer>` (iterating it yields `Integer` method ids); `appendForBulkBuild(int)` + `finishBulkBuild()` is the bulk build API used by the read path and the tests.
- **Index names are case-folded to upper case** by H2 in `INFORMATION_SCHEMA.INDEXES` - the tests query upper-case names while the DDL uses lower-case identifiers (H2 treats unquoted identifiers case-insensitively, storing them upper-case).
- If the `ProfileSeedPersist` classpath invocation is awkward in CI, running `main` from the IDE is an acceptable substitute - the harness is a manual measurement aid, not a gated test.
