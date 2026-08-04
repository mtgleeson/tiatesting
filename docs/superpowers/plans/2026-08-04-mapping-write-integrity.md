# Mapping Write Integrity Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Tia's mapping data provably consistent with the stored commit value, closing two under-selection windows and one concurrency bug in the persist path.

**Architecture:** Three independent changes to `tia-core`'s persist path. (1) `tia_source_class` ids move from an in-memory `MAX(id)+1` to an atomically allocated block, so concurrent writers cannot collide. (2) The method catalogue write, the library drain cleanup and the commit seal move into one transaction, so the catalogue and library baselines can never be ahead of the stored commit. (3) A per-suite `unsealed` flag is set when a suite's mapping rows are written and cleared by the seal, so a run that crashes before sealing force-runs exactly the suites that ran.

**Tech Stack:** Java 8 (`sourceCompatibility = '1.8'`), Gradle, JUnit 5 (`useJUnitPlatform()`), H2 and Postgres via `SqlDialect`.

**Spec:** `docs/superpowers/specs/2026-08-04-mapping-write-integrity-design.md`

## Global Constraints

- **Unit tests for every code change.** Every test method uses `// given` / `// when` / `// then` marker comments separating setup, action and assertion. Match the existing tests in `tia-core/src/test`.
- **Javadoc on every new or modified method**, with `@param` for each parameter and `@return` when it returns a value. Explain purpose, do not restate the signature. This overrides any general "no comments" rule.
- **Do not reference design docs** (`docs/superpowers/specs/*`) from javadoc or code comments. Reference the relevant `WIKI.md` chapter instead.
- **No backwards-compatibility shims.** Tia is pre-release. Change signatures directly and update all callers in the same change. Do not add transitional overloads or deprecated routing methods.
- **ASCII hyphen `-` only.** Never the em-dash character. Applies to commit messages, javadoc, comments and any prose.
- **Performance.** Do not add work to the `select-tests` read path (`TestSelector.selectTestsToIgnore`, `JdbcDataStore.getTiaData`). Task 6 deliberately reuses already-loaded data rather than issuing a new query.
- **Both dialects.** Every new SQL statement must work on H2 and Postgres. Use `dialect.upsert(...)` for upserts; plain ANSI SQL elsewhere.
- **Compile after changes:** `./gradlew :tia-core:compileJava`. Address build warnings on lines you touched; ignore pre-existing warnings elsewhere.
- **Stop after each task for review.** Do not batch tasks together.

## Deviation from the spec (agreed)

The spec's "API impact" section says `persistSourceMethods` and `persistCoreData` are *replaced* by the combined operation. Checking callers shows both are used as seeding helpers by ~30 test files, while their only production callers are the two seal-path methods in `TestRunnerService`. So:

- Both methods **stay** on `DataStore` as standalone primitives.
- `TestRunnerService` stops calling them for the seal and uses `persistSealedRunData` instead.

This is not a compatibility shim - they retain real independent users. Amend the spec's "API impact" paragraph to match when Task 7 updates the docs.

## File Structure

| File | Responsibility | Tasks |
|---|---|---|
| `tia-core/src/main/java/org/tiatesting/core/persistence/JdbcDataStore.java` | All SQL: new id-block table and allocator, the atomic seal bundle, the `unsealed` column | 1, 2, 3, 5, 6 |
| `tia-core/src/main/java/org/tiatesting/core/persistence/DataStore.java` | Interface: adds `persistSealedRunData` | 3 |
| `tia-core/src/main/java/org/tiatesting/core/persistence/SealedRunData.java` | **New.** Value object describing one seal bundle | 3 |
| `tia-core/src/main/java/org/tiatesting/core/persistence/SerializedDataStore.java` | Legacy `.ser` store: non-atomic implementation of the new method | 3 |
| `tia-core/src/main/java/org/tiatesting/core/testrunner/TestRunnerService.java` | Decides *what* to seal; delegates the atomic write | 4, 6 |
| `tia-core/src/main/java/org/tiatesting/core/model/TestSuiteTracker.java` | Carries the `unsealed` flag | 5 |
| `tia-core/src/main/java/org/tiatesting/core/diff/diffanalyze/selector/TestSelector.java` | Force-selects unsealed suites | 6 |
| `tia-core/src/main/java/org/tiatesting/core/report/StatusReportGenerator.java` | Surfaces unsealed suites in `tia-status` | 7 |
| `wiki/persist-flow-and-crash-safety.md`, `wiki/database-schema.md` | Durable documentation | 7 |

---

### Task 1: Atomic id-block allocator

**Files:**
- Modify: `tia-core/src/main/java/org/tiatesting/core/persistence/JdbcDataStore.java`
- Test: `tia-core/src/test/java/org/tiatesting/core/persistence/JdbcDataStoreIdBlockTest.java` (create)

**Interfaces:**
- Consumes: nothing.
- Produces: `long JdbcDataStore.allocateSourceClassIdBlock(Connection connection, int blockSize)` - package-private, returns the first id of a reserved contiguous block of `blockSize` ids. Task 2 calls it.

**Background for the implementer:** `tia_source_class.id` is declared as an identity/auto-increment primary key, but `persistTestSuites` currently assigns ids itself from `readMaxSourceClassId() + 1` held in a local `long[]`, then resets the identity sequence with `ALTER TABLE ... RESTART WITH`. Application-side assignment exists for performance - it allows chunked multi-row inserts instead of one round trip per row - and must be preserved. Only the *source* of the ids changes.

- [ ] **Step 1: Write the failing test**

Create `tia-core/src/test/java/org/tiatesting/core/persistence/JdbcDataStoreIdBlockTest.java`:

```java
package org.tiatesting.core.persistence;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tiatesting.core.persistence.connection.H2ConnectionProvider;
import org.tiatesting.core.persistence.dialect.H2Dialect;
import org.tiatesting.core.persistence.h2.H2ConnectionSettings;

import java.io.File;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lock the atomicity of {@code tia_source_class} id block allocation. Ids are assigned
 * application-side so rows can be inserted in chunks, so the allocator is the only thing
 * preventing two concurrent writers from handing out the same ids and colliding on the
 * primary key.
 */
class JdbcDataStoreIdBlockTest {

    private JdbcDataStore dataStore;
    private File tempDir;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = File.createTempFile("tia-id-block-", "");
        tempDir.delete();
        tempDir.mkdirs();
        dataStore = new JdbcDataStore(new H2Dialect(),
                new H2ConnectionProvider(H2ConnectionSettings.embedded(tempDir.getAbsolutePath())),
                BranchSchema.schemaName("test"));
        dataStore.getTiaData(true);
    }

    @AfterEach
    void tearDown() {
        dataStore.close();
    }

    @Test
    void sequentialAllocationsReturnNonOverlappingBlocks() throws Exception {
        // given
        Connection connection = dataStore.getConnection();

        // when
        long firstBlockStart = dataStore.allocateSourceClassIdBlock(connection, 10);
        long secondBlockStart = dataStore.allocateSourceClassIdBlock(connection, 5);
        connection.close();

        // then
        assertEquals(firstBlockStart + 10, secondBlockStart,
                "the second block must start immediately after the first block ends");
    }

    @Test
    void concurrentAllocationsNeverOverlap() throws Exception {
        // given
        int threads = 8;
        int blockSize = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        List<Callable<Long>> jobs = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            jobs.add(new Callable<Long>() {
                @Override
                public Long call() throws Exception {
                    Connection connection = dataStore.getConnection();
                    try {
                        return dataStore.allocateSourceClassIdBlock(connection, blockSize);
                    } finally {
                        connection.close();
                    }
                }
            });
        }

        // when
        List<Long> starts = new ArrayList<>();
        for (Future<Long> future : executor.invokeAll(jobs)) {
            starts.add(future.get());
        }
        executor.shutdown();

        // then
        Collections.sort(starts);
        for (int i = 1; i < starts.size(); i++) {
            assertTrue(starts.get(i) - starts.get(i - 1) >= blockSize,
                    "blocks must not overlap: " + starts);
        }
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :tia-core:test --tests 'org.tiatesting.core.persistence.JdbcDataStoreIdBlockTest'`

Expected: compilation failure - `cannot find symbol: method allocateSourceClassIdBlock`.

- [ ] **Step 3: Add the table constants and DDL**

In `JdbcDataStore.java`, add alongside the other table/column constants near the top of the class:

```java
    private static final String TABLE_TIA_ID_BLOCK = "tia_id_block";
    private static final String COL_BLOCK_NAME = "block_name";
    private static final String COL_NEXT_VALUE = "next_value";
    private static final String ID_BLOCK_SOURCE_CLASS = "tia_source_class";
```

Add the migration helper next to `ensureTestSuiteDeveloperDisabledColumnExists`:

```java
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
        Statement statement = connection.createStatement();
        statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + TABLE_TIA_ID_BLOCK + " ("
                + COL_BLOCK_NAME + " VARCHAR(100) PRIMARY KEY, "
                + COL_NEXT_VALUE + " BIGINT NOT NULL)");
    }
```

Wire it into `ensureSchema(Connection)`, after `ensureTiaCoreAllTestsStatsColumnsExist(connection);`:

```java
        ensureIdBlockTableExists(connection);
```

- [ ] **Step 4: Implement the allocator**

Add to `JdbcDataStore.java`, next to `readMaxSourceClassId`:

```java
    /**
     * Reserve a contiguous block of {@code tia_source_class} ids for the calling writer. The
     * counter row is locked with {@code SELECT ... FOR UPDATE} and advanced in the same
     * transaction, so two writers persisting concurrently receive disjoint ranges rather than
     * both reading the same {@code MAX(id)} and colliding on the primary key.
     *
     * <p>On first use against a database created before this table existed, the counter is
     * seeded from {@code MAX(id) + 1} of the existing rows so already-assigned ids are never
     * handed out again.
     *
     * <p>A writer that dies after allocating leaves its block unused. That is a harmless gap in
     * the id space - ids carry no meaning beyond identity.
     *
     * @param connection the connection to allocate on; its auto-commit state is restored before
     *                   returning
     * @param blockSize the number of ids to reserve; must be positive
     * @return the first id of the reserved block; the caller may use
     *         {@code [result, result + blockSize)}
     * @throws SQLException if the lock, read or update fails
     */
    long allocateSourceClassIdBlock(Connection connection, int blockSize) throws SQLException {
        ensureIdBlockTableExists(connection);
        seedSourceClassIdBlockIfAbsent(connection);

        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            long nextValue;
            String selectSql = "SELECT " + COL_NEXT_VALUE + " FROM " + TABLE_TIA_ID_BLOCK
                    + " WHERE " + COL_BLOCK_NAME + " = ? FOR UPDATE";
            try (PreparedStatement ps = connection.prepareStatement(selectSql)) {
                ps.setString(1, ID_BLOCK_SOURCE_CLASS);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    nextValue = rs.getLong(1);
                }
            }

            String updateSql = "UPDATE " + TABLE_TIA_ID_BLOCK + " SET " + COL_NEXT_VALUE + " = ?"
                    + " WHERE " + COL_BLOCK_NAME + " = ?";
            try (PreparedStatement ps = connection.prepareStatement(updateSql)) {
                ps.setLong(1, nextValue + blockSize);
                ps.setString(2, ID_BLOCK_SOURCE_CLASS);
                ps.executeUpdate();
            }

            connection.commit();
            return nextValue;
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    /**
     * Insert the {@code tia_source_class} counter row if it is not already present, seeding it
     * from the highest existing id so a database populated before the id-block table existed
     * continues from where it left off. The conditional insert makes this a no-op once seeded;
     * a duplicate-key failure from two writers racing the very first seed is swallowed, since
     * it means another writer seeded it first.
     *
     * @param connection the connection to seed on
     * @throws SQLException if reading the existing maximum id fails
     */
    private void seedSourceClassIdBlockIfAbsent(Connection connection) throws SQLException {
        long seed = readMaxSourceClassId(connection) + 1;
        String sql = "INSERT INTO " + TABLE_TIA_ID_BLOCK + " (" + COL_BLOCK_NAME + ", " + COL_NEXT_VALUE + ")"
                + " SELECT ?, ? WHERE NOT EXISTS ("
                + "SELECT 1 FROM " + TABLE_TIA_ID_BLOCK + " WHERE " + COL_BLOCK_NAME + " = ?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, ID_BLOCK_SOURCE_CLASS);
            ps.setLong(2, seed);
            ps.setString(3, ID_BLOCK_SOURCE_CLASS);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.debug("Seed of the {} id block lost a race with another writer - continuing.",
                    ID_BLOCK_SOURCE_CLASS);
        }
    }
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew :tia-core:test --tests 'org.tiatesting.core.persistence.JdbcDataStoreIdBlockTest'`

Expected: PASS, 2 tests.

- [ ] **Step 6: Verify the Postgres dialect path**

Run: `./gradlew :tia-core:test --tests 'org.tiatesting.core.persistence.DatastoreEquivalenceTest'`

Expected: PASS (or skipped if no Postgres is available locally - the suite self-skips). The SQL used here is ANSI and needs no dialect method.

- [ ] **Step 7: Commit**

```bash
git add tia-core/src/main/java/org/tiatesting/core/persistence/JdbcDataStore.java \
        tia-core/src/test/java/org/tiatesting/core/persistence/JdbcDataStoreIdBlockTest.java
git commit -m "feat(persistence): atomic id block allocation for tia_source_class

Adds tia_id_block plus allocateSourceClassIdBlock, which locks the counter
row with SELECT ... FOR UPDATE and advances it in the same transaction so
concurrent writers reserve disjoint id ranges. Seeded from MAX(id)+1 for
databases created before the table existed. Not yet wired into the persist
path."
```

---

### Task 2: Use the allocated block in `persistTestSuites`

**Files:**
- Modify: `tia-core/src/main/java/org/tiatesting/core/persistence/JdbcDataStore.java`
- Test: `tia-core/src/test/java/org/tiatesting/core/persistence/JdbcDataStoreIdBlockTest.java` (extend)

**Interfaces:**
- Consumes: `long allocateSourceClassIdBlock(Connection, int)` from Task 1.
- Produces: `persistTestSuites` no longer calls `readMaxSourceClassId` directly and no longer issues `ALTER TABLE ... RESTART WITH`.

- [ ] **Step 1: Write the failing test**

Append to `JdbcDataStoreIdBlockTest.java` (add the imports it needs: `org.tiatesting.core.model.ClassImpactTracker`, `MethodIdSet`, `TestSuiteTracker`, `java.util.*`):

```java
    @Test
    void concurrentSuiteMappingPersistsDoNotCollideOnSourceClassIds() throws Exception {
        // given - two writers persisting disjoint suites at the same time
        int threads = 4;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        List<Callable<Void>> jobs = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int writerIndex = i;
            jobs.add(new Callable<Void>() {
                @Override
                public Void call() {
                    dataStore.persistTestSuites(suiteWithClasses("Suite" + writerIndex, 25));
                    return null;
                }
            });
        }

        // when
        for (Future<Void> future : executor.invokeAll(jobs)) {
            future.get();
        }
        executor.shutdown();

        // then - every suite kept all of its source-class rows (no PK collision dropped any)
        Map<String, TestSuiteTracker> stored = dataStore.getTiaData(true).getTestSuitesTracked();
        assertEquals(threads, stored.size(), "every suite must have persisted");
        for (TestSuiteTracker tracker : stored.values()) {
            assertEquals(25, tracker.getClassesImpacted().size(),
                    "suite " + tracker.getName() + " lost source-class rows");
        }
    }

    /**
     * Build a single-suite map whose suite covers {@code classCount} distinct source files, each
     * with one impacted method. Used to give concurrent writers enough source-class rows that
     * overlapping id blocks would collide.
     *
     * @param suiteName the test suite name
     * @param classCount how many source classes the suite covers
     * @return a map of suite name to tracker, ready for {@code persistTestSuites}
     */
    private Map<String, TestSuiteTracker> suiteWithClasses(String suiteName, int classCount) {
        TestSuiteTracker tracker = new TestSuiteTracker(suiteName);
        List<ClassImpactTracker> classes = new ArrayList<>();
        for (int i = 0; i < classCount; i++) {
            MethodIdSet methods = new MethodIdSet();
            methods.add(suiteName.hashCode() + i);
            classes.add(new ClassImpactTracker("com/example/" + suiteName + "Class" + i + ".java", methods));
        }
        tracker.setClassesImpacted(classes);
        Map<String, TestSuiteTracker> suites = new HashMap<>();
        suites.put(suiteName, tracker);
        return suites;
    }
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :tia-core:test --tests 'org.tiatesting.core.persistence.JdbcDataStoreIdBlockTest.concurrentSuiteMappingPersistsDoNotCollideOnSourceClassIds'`

Expected: FAIL with a primary key violation from H2 (`Unique index or primary key violation: "PRIMARY KEY ON ...TIA_SOURCE_CLASS"`), or a suite with fewer than 25 classes.

- [ ] **Step 3: Replace the id source in `persistTestSuites`**

In `JdbcDataStore.persistTestSuites(Connection, Collection<TestSuiteTracker>, boolean)`, replace:

```java
        long[] nextSourceClassId = null;
        PreparedStatement classChunkPs = null;
        PreparedStatement edgeChunkPs = null;
        if (includeClassMappings){
            nextSourceClassId = new long[]{ readMaxSourceClassId(connection) + 1 };
            classChunkPs = connection.prepareStatement(INSERT_SOURCE_CLASS_CHUNK_SQL);
            edgeChunkPs = connection.prepareStatement(INSERT_SOURCE_CLASS_METHOD_CHUNK_SQL);
        }
```

with:

```java
        long[] nextSourceClassId = null;
        PreparedStatement classChunkPs = null;
        PreparedStatement edgeChunkPs = null;
        if (includeClassMappings){
            // Reserve exactly the ids this persist needs, in one atomic allocation, so a
            // concurrent writer cannot be handed the same range. See the "Persist flow and crash
            // safety" chapter in WIKI.md.
            int idsNeeded = countSourceClassRows(testSuites);
            nextSourceClassId = new long[]{ idsNeeded > 0 ? allocateSourceClassIdBlock(connection, idsNeeded) : 0L };
            classChunkPs = connection.prepareStatement(INSERT_SOURCE_CLASS_CHUNK_SQL);
            edgeChunkPs = connection.prepareStatement(INSERT_SOURCE_CLASS_METHOD_CHUNK_SQL);
        }
```

Delete the `restartSourceClassIdentity(connection, nextSourceClassId[0]);` call and the surrounding `if (includeClassMappings){ ... }` block that contains only it. Delete the `restartSourceClassIdentity` method entirely.

Add the counting helper next to `readMaxSourceClassId`:

```java
    /**
     * Count the {@code tia_source_class} rows a persist will insert: one per impacted class of
     * every suite that has coverage this run. Suites with no coverage are skipped because their
     * existing rows are left untouched, exactly matching the condition guarding
     * {@code persistTestSuiteClasses}.
     *
     * @param testSuites the suites being persisted
     * @return the number of source-class ids the persist needs to reserve
     */
    private static int countSourceClassRows(Collection<TestSuiteTracker> testSuites){
        int count = 0;
        for (TestSuiteTracker testSuite : testSuites){
            if (!testSuite.getClassesImpacted().isEmpty()){
                count += testSuite.getClassesImpacted().size();
            }
        }
        return count;
    }
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :tia-core:test --tests 'org.tiatesting.core.persistence.JdbcDataStoreIdBlockTest'`

Expected: PASS, 3 tests.

- [ ] **Step 5: Run the persistence regression suites**

Run: `./gradlew :tia-core:test --tests 'org.tiatesting.core.persistence.*'`

Expected: PASS. Pay particular attention to `JdbcDataStoreBatchedPersistTest` - it covers the chunked insert path whose ids just changed source.

- [ ] **Step 6: Commit**

```bash
git add tia-core/src/main/java/org/tiatesting/core/persistence/JdbcDataStore.java \
        tia-core/src/test/java/org/tiatesting/core/persistence/JdbcDataStoreIdBlockTest.java
git commit -m "fix(persistence): allocate tia_source_class ids from an atomic block

persistTestSuites read MAX(id)+1 into a local holder and reset the identity
sequence with ALTER TABLE ... RESTART WITH. Two concurrent writers read the
same maximum and handed out colliding ids, surfacing as a primary key
violation. Ids now come from a single atomic block reservation sized to the
persist, and the RESTART WITH DDL is gone from the persist path."
```

---

### Task 3: `SealedRunData` and the atomic `persistSealedRunData`

**Files:**
- Create: `tia-core/src/main/java/org/tiatesting/core/persistence/SealedRunData.java`
- Modify: `tia-core/src/main/java/org/tiatesting/core/persistence/DataStore.java`
- Modify: `tia-core/src/main/java/org/tiatesting/core/persistence/JdbcDataStore.java`
- Modify: `tia-core/src/main/java/org/tiatesting/core/persistence/SerializedDataStore.java`
- Test: `tia-core/src/test/java/org/tiatesting/core/persistence/JdbcDataStoreSealedRunDataTest.java` (create)

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces:
  - `SealedRunData` with constructor `SealedRunData(TiaData tiaData, Map<Integer, MethodImpactTracker> methodsTracked, List<LibraryImpactDrainResult.DrainedBatchKey> drainedMethodBatchKeys, List<LibraryImpactDrainResult.DrainedBatchKey> drainedForcedBatchKeys, List<TrackedLibrary> librariesToPersist)` and matching getters `getTiaData()`, `getMethodsTracked()`, `getDrainedMethodBatchKeys()`, `getDrainedForcedBatchKeys()`, `getLibrariesToPersist()`.
  - `void DataStore.persistSealedRunData(SealedRunData sealedRunData)`. Task 4 calls it.

- [ ] **Step 1: Write the failing test**

Create `tia-core/src/test/java/org/tiatesting/core/persistence/JdbcDataStoreSealedRunDataTest.java`:

```java
package org.tiatesting.core.persistence;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tiatesting.core.model.MethodImpactTracker;
import org.tiatesting.core.model.TiaData;
import org.tiatesting.core.model.TrackedLibrary;
import org.tiatesting.core.persistence.connection.H2ConnectionProvider;
import org.tiatesting.core.persistence.dialect.H2Dialect;
import org.tiatesting.core.persistence.h2.H2ConnectionSettings;

import java.io.File;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Lock the atomicity of the seal bundle. The method catalogue, the library drain cleanup and the
 * commit value must land together or not at all - a catalogue or library baseline that is ahead
 * of the stored commit puts stored line numbers in a different coordinate space to the diff that
 * reads them. See the "Persist flow and crash safety" chapter in {@code WIKI.md}.
 */
class JdbcDataStoreSealedRunDataTest {

    private JdbcDataStore dataStore;
    private File tempDir;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = File.createTempFile("tia-sealed-run-", "");
        tempDir.delete();
        tempDir.mkdirs();
        dataStore = new JdbcDataStore(new H2Dialect(),
                new H2ConnectionProvider(H2ConnectionSettings.embedded(tempDir.getAbsolutePath())),
                BranchSchema.schemaName("test"));
        dataStore.getTiaData(true);

        TiaData seed = new TiaData();
        seed.setCommitValue("commitA");
        seed.setBranch("main");
        seed.setLastUpdated(Instant.now());
        dataStore.persistCoreData(seed);
    }

    @AfterEach
    void tearDown() {
        dataStore.close();
    }

    @Test
    void sealPersistsCatalogueLibrariesAndCommitTogether() {
        // given
        TrackedLibrary library = new TrackedLibrary();
        library.setGroupArtifact("com.example:lib");
        library.setProjectDir("/repo/lib");
        library.setMappingBaselineCommit("commitB");
        library.setLastAppliedSeq(4L);
        dataStore.persistTrackedLibrary(library);
        library.setMappingBaselineCommit("commitC");

        // when
        dataStore.persistSealedRunData(new SealedRunData(coreData("commitC"), methods(),
                Collections.emptyList(), Collections.emptyList(),
                Collections.singletonList(library)));

        // then
        assertEquals("commitC", dataStore.getTiaCore().getCommitValue());
        assertEquals(1, dataStore.getMethodsTracked().size());
        assertEquals("commitC",
                dataStore.readTrackedLibraries().get("com.example:lib").getMappingBaselineCommit());
    }

    @Test
    void aFailureDuringTheSealLeavesTheCommitAndCatalogueUnchanged() {
        // given - a method tracker map containing a null value, which fails mid-insert
        Map<Integer, MethodImpactTracker> broken = new HashMap<>();
        broken.put(1, new MethodImpactTracker("com.example.Foo.bar()V", 1, 5));
        broken.put(2, null);

        // when
        assertThrows(RuntimeException.class, () ->
                dataStore.persistSealedRunData(new SealedRunData(coreData("commitC"), broken,
                        Collections.emptyList(), Collections.emptyList(), new ArrayList<>())));

        // then - nothing advanced
        assertEquals("commitA", dataStore.getTiaCore().getCommitValue(),
                "the commit value must not advance when the seal bundle fails");
        assertEquals(0, dataStore.getMethodsTracked().size(),
                "the catalogue must not be left half-written");
    }

    /**
     * Build core data carrying the given commit value, for use as the seal payload.
     *
     * @param commitValue the commit the bundle should seal
     * @return populated core data
     */
    private TiaData coreData(String commitValue) {
        TiaData tiaData = dataStore.getTiaCore();
        tiaData.setCommitValue(commitValue);
        tiaData.setBranch("main");
        tiaData.setLastUpdated(Instant.now());
        return tiaData;
    }

    /**
     * Build a single-entry method catalogue for the seal payload.
     *
     * @return method id to tracker map
     */
    private Map<Integer, MethodImpactTracker> methods() {
        Map<Integer, MethodImpactTracker> methods = new HashMap<>();
        methods.put(1, new MethodImpactTracker("com.example.Foo.bar()V", 1, 5));
        return methods;
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :tia-core:test --tests 'org.tiatesting.core.persistence.JdbcDataStoreSealedRunDataTest'`

Expected: compilation failure - `cannot find symbol: class SealedRunData`.

- [ ] **Step 3: Create the value object**

Create `tia-core/src/main/java/org/tiatesting/core/persistence/SealedRunData.java`:

```java
package org.tiatesting.core.persistence;

import org.tiatesting.core.library.LibraryImpactDrainResult;
import org.tiatesting.core.model.MethodImpactTracker;
import org.tiatesting.core.model.TiaData;
import org.tiatesting.core.model.TrackedLibrary;

import java.util.List;
import java.util.Map;

/**
 * The complete payload of a run's seal: everything that must become visible at the same instant
 * as the new commit value.
 *
 * <p>The method catalogue and the library mapping baselines are both statements about the commit
 * being sealed - the catalogue's line ranges are in that commit's coordinate space, and a
 * library's {@code mappingBaselineCommit} claims its methods were re-captured there. If either
 * landed without the commit value, a later diff would read them against the wrong baseline and
 * could under-select. Bundling them lets {@link DataStore#persistSealedRunData} write all of it
 * in one transaction.
 *
 * <p>The bulk suite mapping rows are deliberately NOT part of this bundle - they are written
 * earlier and are safe to be ahead of the commit, because they carry no line coordinates and are
 * marked unsealed until the seal clears them. See the "Persist flow and crash safety" chapter in
 * {@code WIKI.md}.
 */
public class SealedRunData {

    private final TiaData tiaData;
    private final Map<Integer, MethodImpactTracker> methodsTracked;
    private final List<LibraryImpactDrainResult.DrainedBatchKey> drainedMethodBatchKeys;
    private final List<LibraryImpactDrainResult.DrainedBatchKey> drainedForcedBatchKeys;
    private final List<TrackedLibrary> librariesToPersist;

    /**
     * Construct a seal payload.
     *
     * @param tiaData the core data to write, carrying the commit value being sealed
     * @param methodsTracked the full method catalogue to write, keyed by method id
     * @param drainedMethodBatchKeys pending impacted-method batches to delete; may be empty
     * @param drainedForcedBatchKeys pending forced-selection batches to delete; may be empty
     * @param librariesToPersist tracked libraries whose baseline or applied sequence changed;
     *                           may be empty
     */
    public SealedRunData(TiaData tiaData, Map<Integer, MethodImpactTracker> methodsTracked,
                         List<LibraryImpactDrainResult.DrainedBatchKey> drainedMethodBatchKeys,
                         List<LibraryImpactDrainResult.DrainedBatchKey> drainedForcedBatchKeys,
                         List<TrackedLibrary> librariesToPersist) {
        this.tiaData = tiaData;
        this.methodsTracked = methodsTracked;
        this.drainedMethodBatchKeys = drainedMethodBatchKeys;
        this.drainedForcedBatchKeys = drainedForcedBatchKeys;
        this.librariesToPersist = librariesToPersist;
    }

    /** @return the core data to write, carrying the commit value being sealed */
    public TiaData getTiaData() { return tiaData; }

    /** @return the full method catalogue to write, keyed by method id */
    public Map<Integer, MethodImpactTracker> getMethodsTracked() { return methodsTracked; }

    /** @return the pending impacted-method batches to delete */
    public List<LibraryImpactDrainResult.DrainedBatchKey> getDrainedMethodBatchKeys() { return drainedMethodBatchKeys; }

    /** @return the pending forced-selection batches to delete */
    public List<LibraryImpactDrainResult.DrainedBatchKey> getDrainedForcedBatchKeys() { return drainedForcedBatchKeys; }

    /** @return the tracked libraries to upsert */
    public List<TrackedLibrary> getLibrariesToPersist() { return librariesToPersist; }
}
```

- [ ] **Step 4: Add the interface method**

In `DataStore.java`, add after `persistSourceMethods`:

```java
    /**
     * Persist a run's seal atomically: the method catalogue, the library drain cleanup and the
     * commit value are written in one transaction, so none of them can end up ahead of the
     * others. The catalogue's line ranges and each library's mapping baseline are both claims
     * about the commit being sealed, so a partial write would leave a later diff reading them
     * against the wrong baseline. See the "Persist flow and crash safety" chapter in
     * {@code WIKI.md}.
     *
     * @param sealedRunData the complete seal payload
     */
    void persistSealedRunData(final SealedRunData sealedRunData);
```

- [ ] **Step 5a: Extract a transaction-free core out of `persistSourceMethods`**

**This step is load-bearing for the whole task.** The existing private
`persistSourceMethods(Connection, Map)` manages its own transaction and calls
`connection.commit()`. Called from inside the seal transaction it would commit the outer
transaction's work early, so the bundle would not be atomic - and the happy path would look
completely correct, so a test that only checks the end state would not catch it.

Split it. Move the `TRUNCATE` + `INSERT` statements into a helper that does no transaction
management, and leave the existing method owning the transaction exactly as it does today:

```java
    /**
     * Rewrite {@code tia_source_method} on a caller-supplied connection without managing a
     * transaction, so the write can either own one ({@link #persistSourceMethods(Connection, Map)})
     * or join the caller's ({@link #persistSealedRunData(SealedRunData)}).
     *
     * <p>The {@code TRUNCATE} and the {@code INSERT} must end up in the same transaction whichever
     * caller runs them - H2's {@code TRUNCATE} is transactional, so a failure during the insert
     * rolls the truncate back and leaves the previous rows intact.
     *
     * @param connection the connection to write on; its auto-commit state is not changed
     * @param sourceMethods the full method catalogue to write, keyed by method id; a null map is
     *                      a no-op
     * @throws SQLException if the truncate or the insert fails
     */
    private void writeSourceMethods(Connection connection, Map<Integer, MethodImpactTracker> sourceMethods) throws SQLException {
        // Body: exactly the statements currently inside persistSourceMethods(Connection, Map)'s
        // try block - the TRUNCATE, the empty-map early return (without its commit() call), and
        // the batched multi-VALUES INSERT. No setAutoCommit, no commit, no rollback, no catch.
    }
```

`persistSourceMethods(Connection, Map)` keeps its `setAutoCommit(false)` / `catch (Exception)` /
rollback / `finally` restore structure verbatim, with its try block reduced to
`writeSourceMethods(connection, sourceMethods); connection.commit();`.

Run `./gradlew :tia-core:test --tests 'org.tiatesting.core.persistence.*'` after this split and
before moving on. It must be behaviour-preserving.

- [ ] **Step 5b: Implement `persistSealedRunData` on `JdbcDataStore`**

Add to `JdbcDataStore.java` near `persistCoreData`. It calls `writeSourceMethods` (not
`persistSourceMethods`), and follows the rollback pattern this file already uses in
`persistSourceMethods` and `persistTestSuiteClasses` - `catch (Exception)`, not
`catch (SQLException)`, so an unchecked failure still rolls back. Without that, the `finally`
restoring auto-commit would **commit** the partial transaction, because JDBC commits the open
transaction when auto-commit is switched back on.

`persistTiaCore` and the three library methods run plain `executeUpdate` with no transaction
handling of their own, so they are safe to call here as-is.

```java
    @Override
    public void persistSealedRunData(final SealedRunData sealedRunData){
        long startTime = System.currentTimeMillis();
        Connection connection = getConnection();

        try {
            ensureLibraryTableExists(connection);
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);

            try {
                writeSourceMethods(connection, sealedRunData.getMethodsTracked());

                for (LibraryImpactDrainResult.DrainedBatchKey key : sealedRunData.getDrainedMethodBatchKeys()){
                    deletePendingLibraryImpactedMethods(connection, key.getGroupArtifact(), key.getPublishSeq());
                }
                for (LibraryImpactDrainResult.DrainedBatchKey key : sealedRunData.getDrainedForcedBatchKeys()){
                    deletePendingLibraryForcedSelections(connection, key.getGroupArtifact(), key.getPublishSeq());
                }
                for (TrackedLibrary library : sealedRunData.getLibrariesToPersist()){
                    persistTrackedLibrary(connection, library);
                }

                clearUnsealedTestSuites(connection);

                // Seal last within the transaction too, so the write order still reads as
                // "everything, then the commit value" even though they commit together.
                persistTiaCore(connection, sealedRunData.getTiaData());

                connection.commit();
            } catch (Exception e) {
                // Catch Exception (not just SQLException) so any failure - including an unchecked
                // one - rolls back before the finally restores auto-commit. Switching auto-commit
                // back on commits the open transaction, so a missed rollback here would publish a
                // half-written seal.
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
        } catch (SQLException e) {
            throw new TiaPersistenceException(e);
        } finally {
            try {
                connection.close();
            } catch (SQLException e) {
                throw new TiaPersistenceException(e);
            }
        }

        log.debug("Time to persist the sealed run data (ms): " + (System.currentTimeMillis() - startTime));
    }
```

Note `clearUnsealedTestSuites(connection)` is called unconditionally. The bundle is only ever
invoked by a mapping-owning run (see Task 4), so there is no flag to gate it on. That method
arrives in Task 5; until then, omit the line and add it as part of Task 6.

Refactor the three library methods so their bodies move to `Connection`-taking private overloads, with the existing public methods reduced to connection management. For example, `deletePendingLibraryImpactedMethods` becomes:

```java
    @Override
    public void deletePendingLibraryImpactedMethods(final String groupArtifact, final long publishSeq) {
        Connection connection = getConnection();
        try {
            deletePendingLibraryImpactedMethods(connection, groupArtifact, publishSeq);
        } catch (SQLException e) {
            throw new TiaPersistenceException(e);
        } finally {
            try {
                connection.close();
            } catch (SQLException e) {
                throw new TiaPersistenceException(e);
            }
        }
    }

    /**
     * Delete one drained publish's pending impacted-method rows on a caller-supplied connection,
     * so the delete can join the seal transaction rather than committing on its own.
     *
     * @param connection the connection to delete on
     * @param groupArtifact the {@code groupId:artifactId} of the library
     * @param publishSeq the publish sequence whose stamp rows to delete
     * @throws SQLException if the delete fails
     */
    private void deletePendingLibraryImpactedMethods(Connection connection, String groupArtifact,
                                                     long publishSeq) throws SQLException {
        if (!checkTableExists(connection, TABLE_TIA_PENDING_LIBRARY_IMPACTED_METHOD)) {
            return;
        }
        String sql = "DELETE FROM " + TABLE_TIA_PENDING_LIBRARY_IMPACTED_METHOD
                + " WHERE " + COL_GROUP_ARTIFACT + " = ? AND " + COL_PUBLISH_SEQ + " = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, groupArtifact);
            ps.setLong(2, publishSeq);
            ps.executeUpdate();
        }
        log.debug("Deleted pending impacted methods for {} at seq {}", groupArtifact, publishSeq);
    }
```

Apply the identical split to `deletePendingLibraryForcedSelections` and `persistTrackedLibrary`, keeping each existing public method's behaviour unchanged. `persistTrackedLibrary(Connection, TrackedLibrary)` must keep the null handling for `lastAppliedSeq` (`ps.setNull(5, Types.BIGINT)`).

Add the import for `LibraryImpactDrainResult` to `JdbcDataStore.java`.

- [ ] **Step 6: Implement it on `SerializedDataStore`**

Add to `SerializedDataStore.java`:

```java
    /**
     * {@inheritDoc}
     *
     * <p>The serialized store writes the whole object graph as one file, so there is no
     * transaction to join - the calls are made in sequence and the single file write at the end
     * is what makes them visible together.
     */
    @Override
    public void persistSealedRunData(final SealedRunData sealedRunData) {
        persistSourceMethods(sealedRunData.getMethodsTracked());
        for (LibraryImpactDrainResult.DrainedBatchKey key : sealedRunData.getDrainedMethodBatchKeys()) {
            deletePendingLibraryImpactedMethods(key.getGroupArtifact(), key.getPublishSeq());
        }
        for (LibraryImpactDrainResult.DrainedBatchKey key : sealedRunData.getDrainedForcedBatchKeys()) {
            deletePendingLibraryForcedSelections(key.getGroupArtifact(), key.getPublishSeq());
        }
        for (TrackedLibrary library : sealedRunData.getLibrariesToPersist()) {
            persistTrackedLibrary(library);
        }
        persistCoreData(sealedRunData.getTiaData());
    }
```

Add the imports it needs (`LibraryImpactDrainResult`, `TrackedLibrary`).

- [ ] **Step 7: Run the test to verify it passes**

Run: `./gradlew :tia-core:test --tests 'org.tiatesting.core.persistence.JdbcDataStoreSealedRunDataTest'`

Expected: PASS, 2 tests.

- [ ] **Step 8: Compile and run the full core suite**

Run: `./gradlew :tia-core:compileJava && ./gradlew :tia-core:test`

Expected: PASS. Any test class implementing `DataStore` directly (the decorators in `TestRunnerServiceSealOrderTest`, `TestRunnerServiceSuiteMappingPersistRoutingTest`, `TestSelectorUpdateDBMappingGateTest`) will fail to compile until it delegates the new method. Add to each:

```java
        @Override public void persistSealedRunData(SealedRunData sealedRunData) { delegate.persistSealedRunData(sealedRunData); }
```

- [ ] **Step 9: Commit**

```bash
git add tia-core/src/main/java/org/tiatesting/core/persistence/ \
        tia-core/src/test/java/org/tiatesting/core/
git commit -m "feat(persistence): atomic persistSealedRunData bundle

Adds SealedRunData plus DataStore.persistSealedRunData, writing the method
catalogue, the library drain cleanup and the commit value in one transaction.
The catalogue's line ranges and each library's mappingBaselineCommit are both
claims about the commit being sealed, so a partial write leaves a later diff
reading them against the wrong baseline.

Splits the three library persist/delete methods into connection-taking
private overloads so they can join the transaction. Not yet wired into
TestRunnerService."
```

---

### Task 4: Rewire `TestRunnerService` to the atomic bundle

**Files:**
- Modify: `tia-core/src/main/java/org/tiatesting/core/testrunner/TestRunnerService.java`
- Test: `tia-core/src/test/java/org/tiatesting/core/testrunner/TestRunnerServiceSealOrderTest.java` (extend)

**Interfaces:**
- Consumes: `SealedRunData` and `DataStore.persistSealedRunData` from Task 3.
- Produces: `persistTestRunData` no longer calls `persistSourceMethods` or `persistCoreData` directly.

**Background:** the current order in `persistTestRunData` is (1) suite mapping, (2) catalogue, (3) failed set, (4) drain cleanup, (5) seal, (6) history. The new order is (1) suite mapping, (3) failed set, (2+4+5) the atomic bundle, (6) history. `updateMethodsTracked` reads `getUniqueMethodIdsTracked()` which depends on step 1's edges, so it must stay after step 1; it has no relationship to step 3.

- [ ] **Step 1: Write the failing test**

Add to `TestRunnerServiceSealOrderTest.java`:

```java
    @Test
    void sealBundleIsTheSingleWriteThatAdvancesTheCommit() {
        // given - a spy that fails inside the seal bundle
        RecordingDataStore spy = new RecordingDataStore(dataStore);
        spy.failInSealBundle = true;
        TestRunnerService service = new TestRunnerService(spy);

        // when
        assertThrows(RuntimeException.class, () -> service.persistTestRunData(true, true, false,
                "newCommit", "main", System.currentTimeMillis(), buildTestRunResult()));

        // then - the prior commit survived and the seal ran as one call, not two
        assertEquals("priorCommit", dataStore.getTiaCore().getCommitValue());
        assertEquals(0, Collections.frequency(spy.callOrder, "persistCoreData"),
                "the seal must go through persistSealedRunData, not persistCoreData");
        assertEquals(0, Collections.frequency(spy.callOrder, "persistSourceMethods"),
                "the catalogue must go through persistSealedRunData, not persistSourceMethods");
    }

    @Test
    void suiteMappingAndFailedSetAreWrittenBeforeTheSealBundle() {
        // given
        RecordingDataStore spy = new RecordingDataStore(dataStore);
        TestRunnerService service = new TestRunnerService(spy);

        // when
        service.persistTestRunData(true, true, false, "newCommit", "main",
                System.currentTimeMillis(), buildTestRunResult());

        // then
        int sealIdx = spy.callOrder.indexOf("persistSealedRunData");
        assertTrue(sealIdx >= 0, "persistSealedRunData must be invoked");
        assertTrue(spy.callOrder.indexOf("persistTestSuites") < sealIdx,
                "suite mapping must be written before the seal. Call order: " + spy.callOrder);
        assertTrue(spy.callOrder.indexOf("persistTestSuitesFailed") < sealIdx,
                "the failed set must be written before the seal. Call order: " + spy.callOrder);
    }
```

Add to the `RecordingDataStore` inner class:

```java
        boolean failInSealBundle;

        @Override
        public void persistSealedRunData(SealedRunData sealedRunData) {
            callOrder.add("persistSealedRunData");
            if (failInSealBundle) {
                throw new RuntimeException("simulated failure in persistSealedRunData");
            }
            delegate.persistSealedRunData(sealedRunData);
        }
```

Add the imports: `java.util.Collections`, `org.tiatesting.core.persistence.SealedRunData`.

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :tia-core:test --tests 'org.tiatesting.core.testrunner.TestRunnerServiceSealOrderTest'`

Expected: FAIL - `persistSealedRunData must be invoked` (index is -1), because `TestRunnerService` still calls the two methods separately.

- [ ] **Step 3: Restructure `persistTestRunData`**

Replace the body of `persistTestRunData` between the duration capture and the history write with:

```java
        TiaData tiaData = dataStore.getTiaCore();

        // 1. Suite mapping rows first. These are safe to be ahead of the stored commit - they
        //    carry no line coordinates, and they are marked unsealed until the seal clears them.
        updateTestSuiteMapping(tiaData, testRunResult.getTestSuiteTrackers(), testRunResult.getRunnerTestSuites(),
                testRunResult.getSelectedTests(), updateDBMapping, updateDBStats);

        // A run where Tia ignored zero suites is an all-tests run (seed run, or every suite
        // selected). getIgnoredTestSuiteCount() already excludes developer-disabled suites,
        // so this stays a plain == 0 check.
        boolean allTestsRun = testRunResult.getIgnoredTestSuiteCount() == 0;

        if (updateDBMapping){
            // 2. The failed set is incremental and safe to be ahead of the commit; over-inclusion
            //    only force-runs extra suites next time.
            updateTestSuitesFailed(tiaData, testRunResult.getSelectedTests(), testRunResult.getTestSuitesFailed());
        }

        // 3. The seal bundle: catalogue, library drain cleanup and the commit value, written in
        //    one transaction so none of them can end up ahead of the others.
        sealRun(tiaData, commitValue, branch, updateDBMapping, updateDBStats,
                testRunResult, allTestsRun);

        // 4. History row is audit-only and has no select-tests consistency implications;
        //    written after the seal so history rows only exist for fully-sealed runs.
        if (updateDBTestRunHistory) {
            long allTestsRunTimeMs = tiaData.getTestStats().getAllTestsRunTime();
            persistTestRunHistory(updateDBMapping, commitValue, branch, runStartTimestampMs,
                    durationMs, testRunResult, allTestsRunTimeMs);
        }
```

Add the new method, replacing `updateTiaCoreData`:

```java
    /**
     * Assemble and write the run's seal. The method catalogue, the library drain cleanup and the
     * commit value all describe the commit being sealed, so they are handed to the data store as
     * one bundle and written in a single transaction. On a run that does not own mapping updates
     * the bundle carries only the stats-updated core data - there is no catalogue rewrite, no
     * drain cleanup and no commit advance.
     *
     * @param tiaData the core data read at the start of the persist, mutated with the new commit
     *                and stats before being written
     * @param commitValue the VCS commit / changelist the run was against
     * @param branch the VCS branch the run targeted
     * @param updateDBMapping whether this run owns mapping-DB updates
     * @param updateDBStats whether the run stats should be updated
     * @param testRunResult the collected results of the test run
     * @param allTestsRun {@code true} when Tia ignored zero suites this run
     */
    private void sealRun(final TiaData tiaData, final String commitValue, final String branch,
                         final boolean updateDBMapping, final boolean updateDBStats,
                         final TestRunResult testRunResult, final boolean allTestsRun){
        if (updateDBStats){
            tiaData.incrementStats(testRunResult.getTestStats(), allTestsRun);
        }

        if (!updateDBMapping) {
            // This run does not own mapping updates, so there is nothing to seal. The only write
            // is the core row, which carries the Tia-level run stats as well as the commit value.
            // tia_source_method, the library baselines and the unsealed flags are all mapping
            // concerns and stay untouched, exactly as on a stats-only run today.
            dataStore.persistCoreData(tiaData);
            return;
        }

        tiaData.setCommitValue(commitValue);
        tiaData.setBranch(branch);
        tiaData.setLastUpdated(Instant.now());

        Map<Integer, MethodImpactTracker> methodsTracked =
                buildMethodsTracked(tiaData, testRunResult.getMethodTrackersFromTestRun());

        List<LibraryImpactDrainResult.DrainedBatchKey> drainedMethodKeys = Collections.emptyList();
        List<LibraryImpactDrainResult.DrainedBatchKey> drainedForcedKeys = Collections.emptyList();
        LibraryImpactDrainResult drainResult = testRunResult.getLibraryImpactDrainResult();
        if (drainResult != null && drainResult.hasDrainedBatches()) {
            drainedMethodKeys = new ArrayList<>(drainResult.getDrainedBatchKeys());
            drainedForcedKeys = new ArrayList<>(drainResult.getDrainedForcedBatchKeys());
        }

        List<TrackedLibrary> librariesToPersist =
                collectLibrariesToPersist(drainResult, commitValue, allTestsRun);

        dataStore.persistSealedRunData(new SealedRunData(tiaData, methodsTracked,
                drainedMethodKeys, drainedForcedKeys, librariesToPersist));
    }
```

Rename `updateMethodsTracked` to `buildMethodsTracked`, returning the map instead of persisting it:

```java
    /**
     * Build the method catalogue to write at the seal. Note this must be called after the suite
     * mapping has been persisted - it queries the data store for the updated set of source class
     * method ids.
     *
     * @param tiaData the Tia DB, updated in place with the resulting catalogue
     * @param methodTrackersFromTestRun all source code methods covered by any test suite executed
     *                                  in this run
     * @return the catalogue to persist, keyed by method id
     */
    private Map<Integer, MethodImpactTracker> buildMethodsTracked(final TiaData tiaData,
                                                                  final Map<Integer, MethodImpactTracker> methodTrackersFromTestRun){
        Map<Integer, MethodImpactTracker> methodTrackersOnDisk = dataStore.getMethodsTracked();
        Map<Integer, MethodImpactTracker> updatedMethodTrackers = updateMethodTracker(methodTrackersOnDisk, methodTrackersFromTestRun);
        tiaData.setMethodsTracked(updatedMethodTrackers);
        return updatedMethodTrackers;
    }
```

Replace `applyLibraryImpactDrainResult` / `updateAppliedLibraryState` / `advanceAllMappingBaselines` with a single collector that returns rows instead of writing them:

```java
    /**
     * Collect the tracked-library rows whose state changes as part of this seal, without writing
     * them - the caller hands them to the data store inside the seal transaction.
     *
     * <p>Two sources contribute. A drained library has its {@code lastAppliedSeq} advanced to the
     * resolved build's sequence and its {@code mappingBaselineCommit} to this run's commit. An
     * all-tests run advances every tracked library's baseline, because every suite was just
     * re-covered. See the mapping-baseline section of the library publish-time stamping chapter
     * in {@code WIKI.md}.
     *
     * @param drainResult the drain result from test selection, or {@code null} when no drain ran
     * @param commitValue the commit this run seals - the new mapping baseline
     * @param allTestsRun {@code true} when Tia ignored zero suites this run
     * @return the library rows to upsert; empty when nothing changed
     */
    private List<TrackedLibrary> collectLibrariesToPersist(final LibraryImpactDrainResult drainResult,
                                                           final String commitValue,
                                                           final boolean allTestsRun) {
        Map<String, TrackedLibrary> trackedLibraries = dataStore.readTrackedLibraries();
        Map<String, TrackedLibrary> changed = new LinkedHashMap<>();

        if (drainResult != null && drainResult.hasDrainedBatches()) {
            for (Map.Entry<String, Long> entry : drainResult.getAppliedSeqByLibrary().entrySet()) {
                TrackedLibrary library = trackedLibraries.get(entry.getKey());
                if (library == null) {
                    log.warn("Tracked library '{}' not found during drain cleanup - skipping applied-seq update.",
                            entry.getKey());
                    continue;
                }
                library.setLastAppliedSeq(entry.getValue());
                library.setMappingBaselineCommit(commitValue);
                changed.put(entry.getKey(), library);
                log.info("Updating tracked library '{}': last_applied_seq={}, mapping_baseline_commit='{}'.",
                        entry.getKey(), entry.getValue(), commitValue);
            }
        }

        if (allTestsRun) {
            for (TrackedLibrary library : trackedLibraries.values()) {
                if (!Objects.equals(library.getMappingBaselineCommit(), commitValue)) {
                    library.setMappingBaselineCommit(commitValue);
                    changed.put(library.getGroupArtifact(), library);
                    log.info("All-tests run - advancing mapping baseline for library '{}' to '{}'.",
                            library.getGroupArtifact(), commitValue);
                }
            }
        }

        return new ArrayList<>(changed.values());
    }
```

Delete `updateTiaCoreData`, `updateMethodsTracked`, `applyLibraryImpactDrainResult`, `deleteDrainedPendingBatches`, `updateAppliedLibraryState` and `advanceAllMappingBaselines`. Add imports for `java.util.ArrayList`, `java.util.Collections`, `java.util.LinkedHashMap`, `java.util.List`, and `org.tiatesting.core.persistence.SealedRunData`.

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :tia-core:test --tests 'org.tiatesting.core.testrunner.*'`

Expected: PASS. `TestRunnerServiceDrainCleanupTest` exercises the drain paths that just moved; if it asserts on `deletePendingLibraryImpactedMethods` being called directly, update it to assert the same effect through `persistSealedRunData`.

- [ ] **Step 5: Run the full suite**

Run: `./gradlew :tia-core:test`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add tia-core/src/main/java/org/tiatesting/core/testrunner/TestRunnerService.java \
        tia-core/src/test/java/org/tiatesting/core/testrunner/TestRunnerServiceSealOrderTest.java
git commit -m "fix(testrunner): seal the catalogue, drain cleanup and commit atomically

persistTestRunData wrote the method catalogue at step 2 and the commit value
at step 5, so a crash between them left stored line ranges in a different
coordinate space to the diff that reads them - the MethodImpactAnalyzer
matches original-side hunk line numbers against them, so a drifted method
could be missed and its covering suites not selected.

The catalogue, the library drain cleanup and the commit value now go to
persistSealedRunData as one bundle. The drain cleanup joins them because
mappingBaselineCommit is set to the commit being sealed, so left outside it
would claim a commit that never landed."
```

---

### Task 5: The `unsealed` flag - schema, model and persistence

**Files:**
- Modify: `tia-core/src/main/java/org/tiatesting/core/model/TestSuiteTracker.java`
- Modify: `tia-core/src/main/java/org/tiatesting/core/persistence/JdbcDataStore.java`
- Test: `tia-core/src/test/java/org/tiatesting/core/persistence/JdbcDataStoreUnsealedSuiteTest.java` (create)

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: `boolean TestSuiteTracker.isUnsealed()` / `void TestSuiteTracker.setUnsealed(boolean)`, populated by `getTestSuitesTracked()` and `getTiaData()`, and written by `persistTestSuites`. Task 6 reads it.

**Critical detail:** `persistTestSuites` receives the *whole* merged suite map, including suites that did not run this time (empty `classesImpacted`). The flag must be set only for suites whose mapping rows are actually written, and must never clear a flag set by an earlier unsealed run. The stored value is therefore `existingFlag || ranThisRun`.

- [ ] **Step 1: Write the failing test**

Create `tia-core/src/test/java/org/tiatesting/core/persistence/JdbcDataStoreUnsealedSuiteTest.java`:

```java
package org.tiatesting.core.persistence;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tiatesting.core.model.ClassImpactTracker;
import org.tiatesting.core.model.MethodIdSet;
import org.tiatesting.core.model.TestSuiteTracker;
import org.tiatesting.core.persistence.connection.H2ConnectionProvider;
import org.tiatesting.core.persistence.dialect.H2Dialect;
import org.tiatesting.core.persistence.h2.H2ConnectionSettings;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lock the per-suite unsealed flag. A suite's mapping rows are marked unsealed when written and
 * cleared by the seal, so a run that crashes before sealing leaves exactly the suites that ran
 * flagged for a forced re-run. See the "Persist flow and crash safety" chapter in
 * {@code WIKI.md}.
 */
class JdbcDataStoreUnsealedSuiteTest {

    private JdbcDataStore dataStore;
    private File tempDir;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = File.createTempFile("tia-unsealed-", "");
        tempDir.delete();
        tempDir.mkdirs();
        dataStore = new JdbcDataStore(new H2Dialect(),
                new H2ConnectionProvider(H2ConnectionSettings.embedded(tempDir.getAbsolutePath())),
                BranchSchema.schemaName("test"));
        dataStore.getTiaData(true);
    }

    @AfterEach
    void tearDown() {
        dataStore.close();
    }

    @Test
    void aSuiteWithCoverageIsMarkedUnsealed() {
        // given
        Map<String, TestSuiteTracker> suites = suites(withCoverage("SuiteA"));

        // when
        dataStore.persistTestSuites(suites);

        // then
        assertTrue(dataStore.getTestSuitesTracked().get("SuiteA").isUnsealed());
    }

    @Test
    void aSuiteWithoutCoverageIsNotMarkedUnsealed() {
        // given - SuiteB ran, SuiteC did not (Tia ignored it, so it has no classes impacted)
        Map<String, TestSuiteTracker> suites = suites(withCoverage("SuiteB"), withoutCoverage("SuiteC"));

        // when
        dataStore.persistTestSuites(suites);

        // then
        assertTrue(dataStore.getTestSuitesTracked().get("SuiteB").isUnsealed());
        assertFalse(dataStore.getTestSuitesTracked().get("SuiteC").isUnsealed(),
                "a suite Tia ignored must not be flagged");
    }

    @Test
    void anExistingFlagSurvivesALaterPersistThatDidNotRunTheSuite() {
        // given - SuiteD ran and was flagged, and the run never sealed
        dataStore.persistTestSuites(suites(withCoverage("SuiteD")));

        // when - a later run persists without having run SuiteD
        dataStore.persistTestSuites(suites(withoutCoverage("SuiteD")));

        // then
        assertTrue(dataStore.getTestSuitesTracked().get("SuiteD").isUnsealed(),
                "a flag from an earlier unsealed run must not be cleared by a later persist");
    }

    @Test
    void clearingRemovesTheFlagFromEverySuite() {
        // given
        dataStore.persistTestSuites(suites(withCoverage("SuiteE"), withCoverage("SuiteF")));

        // when
        dataStore.clearUnsealedTestSuites();

        // then
        assertFalse(dataStore.getTestSuitesTracked().get("SuiteE").isUnsealed());
        assertFalse(dataStore.getTestSuitesTracked().get("SuiteF").isUnsealed());
    }

    /**
     * Build a suite tracker that has coverage this run, so its mapping rows will be written.
     *
     * @param name the suite name
     * @return the tracker
     */
    private TestSuiteTracker withCoverage(String name) {
        TestSuiteTracker tracker = new TestSuiteTracker(name);
        MethodIdSet methods = new MethodIdSet();
        methods.add(name.hashCode());
        List<ClassImpactTracker> classes = new ArrayList<>();
        classes.add(new ClassImpactTracker("com/example/" + name + ".java", methods));
        tracker.setClassesImpacted(classes);
        return tracker;
    }

    /**
     * Build a suite tracker with no coverage this run, as carried forward for a suite Tia ignored.
     *
     * @param name the suite name
     * @return the tracker
     */
    private TestSuiteTracker withoutCoverage(String name) {
        return new TestSuiteTracker(name);
    }

    /**
     * Collect trackers into the name-keyed map {@code persistTestSuites} expects.
     *
     * @param trackers the suite trackers
     * @return the map
     */
    private Map<String, TestSuiteTracker> suites(TestSuiteTracker... trackers) {
        Map<String, TestSuiteTracker> map = new HashMap<>();
        for (TestSuiteTracker tracker : Arrays.asList(trackers)) {
            map.put(tracker.getName(), tracker);
        }
        return map;
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :tia-core:test --tests 'org.tiatesting.core.persistence.JdbcDataStoreUnsealedSuiteTest'`

Expected: compilation failure - `cannot find symbol: method isUnsealed()`.

- [ ] **Step 3: Add the model field**

In `TestSuiteTracker.java`, add the field next to `developerDisabled`:

```java
    /**
     * True when this suite's mapping rows have been written but the run that wrote them has not
     * sealed its commit value. Such rows describe a later commit than the stored one, so the
     * suite is force-selected on the next run until a seal clears the flag.
     */
    private boolean unsealed;
```

and the accessors:

```java
    /**
     * @return {@code true} when this suite's mapping rows were written by a run that has not
     *         sealed its commit value
     */
    public boolean isUnsealed() {
        return unsealed;
    }

    /**
     * @param unsealed {@code true} when this suite's mapping rows were written by a run that has
     *                 not sealed its commit value
     */
    public void setUnsealed(boolean unsealed) {
        this.unsealed = unsealed;
    }
```

- [ ] **Step 4: Add the column, the write and the read**

In `JdbcDataStore.java`, add the constant next to `COL_DEVELOPER_DISABLED`:

```java
    private static final String COL_UNSEALED = "unsealed";
```

Add the column to `createTestSuiteTableSql` (`... COL_DEVELOPER_DISABLED + " BOOLEAN DEFAULT FALSE, " + COL_UNSEALED + " BOOLEAN DEFAULT FALSE)"`), and add the migration next to `ensureTestSuiteDeveloperDisabledColumnExists`:

```java
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
        Statement statement = connection.createStatement();
        statement.executeUpdate("ALTER TABLE " + TABLE_TIA_TEST_SUITE + " ADD COLUMN IF NOT EXISTS " +
                COL_UNSEALED + " BOOLEAN DEFAULT FALSE");
    }
```

Call it from `ensureSchema(Connection)` after `ensureTestSuiteDeveloperDisabledColumnExists(connection);`.

In `persistTestSuites(Connection, Collection<TestSuiteTracker>, boolean)`, add the column when writing mappings:

```java
        if (includeClassMappings){
            suiteColumns.add(COL_DEVELOPER_DISABLED);
            suiteColumns.add(COL_UNSEALED);
        }
```

and bind it per row, immediately after the `developer_disabled` bind:

```java
                if (includeClassMappings){
                    suitePs.setBoolean(6, testSuite.isDeveloperDisabled());
                    // Flag only the suites whose mapping rows are actually written, matching the
                    // condition that guards persistTestSuiteClasses below. An existing flag is
                    // preserved so a second consecutive unsealed run does not clear the first.
                    suitePs.setBoolean(7, testSuite.isUnsealed() || !testSuite.getClassesImpacted().isEmpty());
                }
```

Add `COL_UNSEALED` to both suite read paths - the aliased join query in `getTestSuitesData` (add `"ts." + COL_UNSEALED + " AS suite_unsealed, "` to the SELECT list and `testSuite.setUnsealed(resultSet.getBoolean("suite_unsealed"));` where `setDeveloperDisabled` is called) and `loadTestSuitesMetadataOnly` (add the column to its SELECT and the same setter call).

Add the clear operation next to `persistTestSuitesFailed`:

```java
    @Override
    public void clearUnsealedTestSuites(){
        Connection connection = getConnection();
        try {
            clearUnsealedTestSuites(connection);
        } catch (SQLException e) {
            throw new TiaPersistenceException(e);
        } finally {
            try {
                connection.close();
            } catch (SQLException e) {
                throw new TiaPersistenceException(e);
            }
        }
    }

    /**
     * Clear the unsealed flag from every flagged suite on a caller-supplied connection, so the
     * clear can join the seal transaction. Restricted to flagged rows so the update touches only
     * the suites this run wrote rather than rewriting the whole table.
     *
     * @param connection the connection to update on
     * @throws SQLException if the update fails
     */
    private void clearUnsealedTestSuites(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            int cleared = statement.executeUpdate("UPDATE " + TABLE_TIA_TEST_SUITE
                    + " SET " + COL_UNSEALED + " = FALSE WHERE " + COL_UNSEALED + " = TRUE");
            log.debug("Cleared the unsealed flag from {} test suite(s).", cleared);
        }
    }
```

Declare it on `DataStore`:

```java
    /**
     * Clear the unsealed flag from every flagged test suite. Called as part of the seal, once the
     * commit value those mapping rows describe is about to become the stored commit.
     */
    void clearUnsealedTestSuites();
```

and implement it on `SerializedDataStore` by setting `setUnsealed(false)` on every tracked suite and writing the file.

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew :tia-core:test --tests 'org.tiatesting.core.persistence.JdbcDataStoreUnsealedSuiteTest'`

Expected: PASS, 4 tests.

- [ ] **Step 6: Compile and run the full suite**

Run: `./gradlew :tia-core:compileJava && ./gradlew :tia-core:test`

Expected: PASS. Add the `clearUnsealedTestSuites` delegation to the test `DataStore` decorators as in Task 3 Step 8.

- [ ] **Step 7: Commit**

```bash
git add tia-core/src/main/java/org/tiatesting/core/ \
        tia-core/src/test/java/org/tiatesting/core/persistence/JdbcDataStoreUnsealedSuiteTest.java
git commit -m "feat(persistence): per-suite unsealed flag

Adds tia_test_suite.unsealed, set when a suite's mapping rows are written and
cleared by the seal. Flagged only for suites that actually have coverage this
run, matching the condition guarding the mapping-row rewrite, and OR-ed with
the stored value so a second consecutive unsealed run cannot clear the first.
Not yet cleared by the seal or consumed by selection."
```

---

### Task 6: Clear the flag at the seal and force-select flagged suites

**Files:**
- Modify: `tia-core/src/main/java/org/tiatesting/core/persistence/JdbcDataStore.java`
- Modify: `tia-core/src/main/java/org/tiatesting/core/testrunner/TestRunnerService.java`
- Modify: `tia-core/src/main/java/org/tiatesting/core/diff/diffanalyze/selector/TestSelector.java`
- Test: `tia-core/src/test/java/org/tiatesting/core/diff/diffanalyze/selector/TestSelectorUnsealedSuitesTest.java` (create)

**Interfaces:**
- Consumes: `TestSuiteTracker.isUnsealed()` from Task 5, `SealedRunData` from Task 3.
- Produces: unsealed suites appear in `TestSelectorResult.getTestsToRun()` and are absent from `getTestsToIgnore()`.

**Performance note:** the flag rides on the suite metadata `selectTestsToIgnore` already loads via `dataStore.getTestSuitesTracked()`. Do **not** add a query. This is a hot read path.

- [ ] **Step 1: Write the failing test**

Create `tia-core/src/test/java/org/tiatesting/core/diff/diffanalyze/selector/TestSelectorUnsealedSuitesTest.java`. Model the VCS reader stub and data store setup on the existing `TestSelectorStaticTestSelectionTest` in the same package - reuse its fake `VCSReader` shape so the diff returns no changes, isolating the unsealed behaviour:

```java
package org.tiatesting.core.diff.diffanalyze.selector;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An unsealed suite - one whose mapping rows were written by a run that never sealed its commit
 * value - must be force-selected, because those rows describe a later commit than the stored one.
 * See the "Persist flow and crash safety" chapter in {@code WIKI.md}.
 */
class TestSelectorUnsealedSuitesTest extends AbstractTestSelectorTestSupport {

    @Test
    void anUnsealedSuiteIsForceSelectedWithNoSourceChanges() {
        // given - no diff at all, one suite flagged unsealed
        seedTrackedSuite("com.example.SealedSpec", false);
        seedTrackedSuite("com.example.UnsealedSpec", true);

        // when
        TestSelectorResult result = selectWithNoChanges();

        // then
        assertTrue(result.getTestsToRun().contains("com.example.UnsealedSpec"));
        assertFalse(result.getTestsToIgnore().contains("com.example.UnsealedSpec"));
        assertTrue(result.getTestsToIgnore().contains("com.example.SealedSpec"),
                "a sealed suite with no impacting change must still be ignored");
    }
}
```

Create the small support base class in the same package if one does not already exist, exposing `seedTrackedSuite(String name, boolean unsealed)` (persist a `TestSuiteTracker` with one impacted class, then set the flag through `persistTestSuites`) and `selectWithNoChanges()` (run `selectTestsToIgnore` against a `VCSReader` returning an empty diff set and empty changed paths). If an existing test in the package already provides equivalent scaffolding, extend or reuse it rather than duplicating.

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :tia-core:test --tests 'org.tiatesting.core.diff.diffanalyze.selector.TestSelectorUnsealedSuitesTest'`

Expected: FAIL - `com.example.UnsealedSpec` is in `testsToIgnore`, not `testsToRun`.

- [ ] **Step 3: Force-select in `TestSelector`**

In `TestSelector.selectTestsToRun`, add the call immediately after `addPreviouslyFailedTests(testsToRun);`:

```java
        // Re-run suites whose mapping rows were written by a run that never sealed - those rows
        // describe a later commit than the stored one.
        addUnsealedTests(testSuitesTracked, testsToRun);
```

and add the method:

```java
    /**
     * Add suites whose stored mapping rows are unsealed - written by a run that did not reach its
     * commit seal, so they describe a later commit than the stored one. Re-running them recaptures
     * their coverage against this run's commit and lets the seal clear the flag.
     *
     * <p>The flag is read from the suite metadata already loaded for this selection, so this adds
     * no query to the read path.
     *
     * @param testSuitesTracked the tracked test suites keyed by suite name
     * @param testsToRun the run set to add the unsealed suites to
     */
    private void addUnsealedTests(final Map<String, TestSuiteTracker> testSuitesTracked,
                                  final Set<String> testsToRun){
        Set<String> unsealed = new HashSet<>();
        for (Map.Entry<String, TestSuiteTracker> entry : testSuitesTracked.entrySet()){
            if (entry.getValue().isUnsealed()){
                unsealed.add(entry.getKey());
            }
        }

        if (!unsealed.isEmpty()){
            log.info("Selected tests to run from unsealed mapping rows (a previous run did not complete): {}", unsealed);
            testsToRun.addAll(unsealed);
        }
    }
```

- [ ] **Step 4: Clear the flag inside the seal transaction**

In `JdbcDataStore.persistSealedRunData`, add the clear immediately before `persistTiaCore` (the line Task 3 Step 5b told you to defer):

```java
            clearUnsealedTestSuites(connection);
```

No flag guards it. `TestRunnerService.sealRun` only reaches `persistSealedRunData` on a
mapping-owning run - a non-mapping run returns early after `persistCoreData` - so the bundle is
by construction a mapping seal, and clearing is unconditionally correct. `SealedRunData` needs no
new field.

Mirror the clear in `SerializedDataStore.persistSealedRunData`, before its `persistCoreData` call.

- [ ] **Step 5: Write the round-trip test**

Add to `TestRunnerServiceSealOrderTest.java`:

```java
    @Test
    void aSealedRunClearsTheUnsealedFlagAndAnAbortedOneDoesNot() {
        // given
        RecordingDataStore spy = new RecordingDataStore(dataStore);
        TestRunnerService service = new TestRunnerService(spy);

        // when - a run that seals
        service.persistTestRunData(true, true, false, "sealedCommit", "main",
                System.currentTimeMillis(), buildTestRunResult());

        // then
        for (TestSuiteTracker tracker : dataStore.getTestSuitesTracked().values()) {
            assertFalse(tracker.isUnsealed(), tracker.getName() + " must be cleared by the seal");
        }

        // when - a run that fails inside the seal bundle
        spy.failInSealBundle = true;
        assertThrows(RuntimeException.class, () -> service.persistTestRunData(true, true, false,
                "abortedCommit", "main", System.currentTimeMillis(), buildTestRunResult()));

        // then - the suites that ran stay flagged for a forced re-run
        assertTrue(dataStore.getTestSuitesTracked().values().stream().anyMatch(TestSuiteTracker::isUnsealed),
                "an aborted run must leave the suites it ran flagged");
    }
```

Add the `assertFalse` import.

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./gradlew :tia-core:test --tests 'org.tiatesting.core.diff.diffanalyze.selector.*' --tests 'org.tiatesting.core.testrunner.*'`

Expected: PASS.

- [ ] **Step 7: Run the full suite**

Run: `./gradlew test`

Expected: PASS across all modules.

- [ ] **Step 8: Commit**

```bash
git add tia-core/src/main/java/org/tiatesting/core/ tia-core/src/test/java/org/tiatesting/core/
git commit -m "fix(selector): force-run suites with unsealed mapping rows

The seal now clears the unsealed flag inside its transaction, and selection
force-runs any suite still flagged. A run that writes a suite's mapping rows
and then fails before sealing leaves those rows describing a later commit
than the stored one; without this, a legitimately removed coverage edge could
stop the suite being selected for a change that later restores it.

The flag is read from the suite metadata selection already loads, so this
adds no query to the select-tests read path."
```

---

### Task 7: Documentation and the `tia-status` diagnostic

**Files:**
- Modify: `tia-core/src/main/java/org/tiatesting/core/report/StatusReportGenerator.java`
- Modify: `wiki/persist-flow-and-crash-safety.md`
- Modify: `wiki/database-schema.md`
- Modify: `docs/superpowers/specs/2026-08-04-mapping-write-integrity-design.md`
- Test: `tia-core/src/test/java/org/tiatesting/core/report/StatusReportUnsealedTest.java` (create)

**Interfaces:**
- Consumes: `TestSuiteTracker.isUnsealed()` from Task 5.
- Produces: nothing later depends on this.

- [ ] **Step 1: Write the failing test**

Create `tia-core/src/test/java/org/tiatesting/core/report/StatusReportUnsealedTest.java`, modelled on the existing `StatusReportBranchTest` in the same package for its data store setup:

```java
    @Test
    void statusReportsUnsealedSuiteCountWhenAPreviousRunDidNotComplete() {
        // given - two suites flagged unsealed
        seedUnsealedSuites("com.example.OneSpec", "com.example.TwoSpec");

        // when
        String report = new StatusReportGenerator().generateReport(dataStore);

        // then
        assertTrue(report.contains("Unsealed test suites: 2"),
                "the status report must surface unsealed suites. Report was:\n" + report);
    }
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :tia-core:test --tests 'org.tiatesting.core.report.StatusReportUnsealedTest'`

Expected: FAIL - the report does not contain the line.

- [ ] **Step 3: Add the diagnostic line**

In `StatusReportGenerator`, count suites with `isUnsealed()` and, when the count is above zero, append a line after the existing commit/branch lines:

```java
        long unsealedCount = testSuitesTracked.values().stream().filter(TestSuiteTracker::isUnsealed).count();
        if (unsealedCount > 0){
            sb.append("Unsealed test suites: ").append(unsealedCount)
              .append(" (a previous run wrote their mapping but did not complete - they will be re-run)")
              .append(lineSep);
        }
```

Match the surrounding builder style and line-separator handling in that class rather than copying this verbatim.

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :tia-core:test --tests 'org.tiatesting.core.report.StatusReportUnsealedTest'`

Expected: PASS.

- [ ] **Step 5: Update the WIKI**

In `wiki/persist-flow-and-crash-safety.md`:

- Update the numbered write sequence to the new order: suite mapping, failed set, then the atomic bundle (catalogue + drain cleanup + commit value), then history.
- Rewrite the **Category A** section. It currently claims "the next run computes a (possibly slightly oversized) diff and re-runs the impacted tests. Self-correcting. No under-selection." Replace with: the claim holds for coverage edges, which carry no line coordinates; it did **not** hold for the method catalogue, whose line ranges could sit in a later commit's coordinate space than the diff reading them; and both are now closed - the catalogue and library baselines can no longer be ahead of the commit, and suite mapping rows that are ahead are flagged unsealed and force-run.
- Add `tia_id_block` and the block-allocation rationale to the per-call atomicity section, replacing any reference to `ALTER TABLE ... RESTART WITH`.

In `wiki/database-schema.md`: add `tia_id_block` to the table list and the mermaid ER diagram, add the `unsealed` column to `tia_test_suite`, and update the table-purposes list for both.

- [ ] **Step 6: Correct the spec's API-impact paragraph**

In `docs/superpowers/specs/2026-08-04-mapping-write-integrity-design.md`, replace the "API impact" paragraph's claim that the existing methods are replaced. State instead that `persistSourceMethods` and `persistCoreData` remain on the interface as standalone primitives with real independent users (~30 test files use them for seeding), and that the production seal path uses only `persistSealedRunData`.

- [ ] **Step 7: Run the full suite and compile every module**

Run: `./gradlew test`

Expected: PASS across all modules.

- [ ] **Step 8: Commit**

```bash
git add tia-core/src/main/java/org/tiatesting/core/report/StatusReportGenerator.java \
        tia-core/src/test/java/org/tiatesting/core/report/StatusReportUnsealedTest.java \
        wiki/ docs/superpowers/specs/2026-08-04-mapping-write-integrity-design.md
git commit -m "docs(wiki): document mapping write integrity; surface unsealed suites in status

Corrects the Category A claim in the persist-flow chapter: no under-selection
held for coverage edges but not for the method catalogue, whose line ranges
could sit in a later commit's coordinate space than the diff reading them.
Both windows are now closed. Documents tia_id_block, the unsealed column and
the new write sequence, and adds an unsealed-suite count to tia-status so the
recovering state is legible."
```

---

## Self-Review

**Spec coverage.** Problem A (catalogue/seal atomicity, plus the drain cleanup folded in) is Tasks 3 and 4. Problem B (id block allocation) is Tasks 1 and 2. Problem C (unsealed flag, the boolean rather than a commit comparison) is Tasks 5 and 6. The spec's testing section maps to: catalogue/seal atomicity (Task 3 Step 1), reorder safety (Task 4 Step 1), block allocation (Tasks 1 and 2), unsealed flag set/cleared/survives/idempotent/ignored-suites-not-flagged (Task 5 Step 1, four tests, plus Task 6 Step 5), both dialects (Task 1 Step 6 and the existing `DatastoreEquivalenceTest`). The WIKI correction is Task 7.

**Deliberate spec deviations, both flagged in-plan:** `persistSourceMethods` / `persistCoreData` stay on the interface (Task 7 Step 6 amends the spec), and the spec's `unsealed_commit` diagnostic column is **not** implemented - Task 7 surfaces a count instead. Add the column later if the count proves insufficient; it drives nothing and can be added without touching selection.

**Type consistency.** `SealedRunData`'s five-parameter constructor is fixed across Tasks 3, 4 and 6 - no task changes its shape. `allocateSourceClassIdBlock(Connection, int)` returns `long` and is used as such in Task 2. `isUnsealed()` / `setUnsealed(boolean)` are used consistently across Tasks 5, 6 and 7. `clearUnsealedTestSuites()` exists in both a public no-arg form and a private `(Connection)` overload, matching the file's established pattern. `writeSourceMethods(Connection, Map)` (Task 3 Step 5a) is called by both `persistSourceMethods(Connection, Map)` and `persistSealedRunData`.

**Pre-flight corrections applied before execution.** Three defects in this plan's own code were found and fixed before Task 1 was dispatched:

1. `persistSealedRunData` called `persistSourceMethods(Connection, Map)`, which manages its own transaction and calls `connection.commit()`. Nested inside the seal transaction that would have committed the outer work early, leaving the bundle non-atomic while looking correct on the happy path. Fixed by extracting the transaction-free `writeSourceMethods` (Task 3 Step 5a).
2. `persistSealedRunData` caught only `SQLException`. An unchecked failure would have skipped the rollback, and the `finally` restoring auto-commit would then have committed the partial transaction. Fixed by adopting the `catch (Exception)` pattern this file already uses in `persistSourceMethods` and `persistTestSuiteClasses`.
3. `sealRun` routed non-mapping runs through the seal bundle, which would have made every stats-only build read, truncate and re-insert all of `tia_source_method` to reach identical contents. Fixed by returning early after `persistCoreData` when `updateDBMapping` is false - the path a stats-only run takes today.

`persistTiaCore` and the three library persist/delete methods were checked for the same nested-transaction issue and are clean: plain `executeUpdate` with no transaction handling.
