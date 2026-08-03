# Library-declared static forced selection Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a tracked library route its own `tiaStaticTestSelectionRules` through the publish/stamp/drain lifecycle so a non-code library change (e.g. a SQL file) can force downstream tests in a version-gated way.

**Architecture:** At publish/stamp time the library evaluates its own static rules against its own since-previous-publish changed files and records a forced-selection batch (mode + suite-name patterns) keyed to the publish sequence, persisted atomically with the ledger row. The consumer's drain gates that batch on the resolved build sequence exactly like method stamps, resolves it against the consumer's current tracked suites (RUN_ALL = all suites, SUITE_NAMES = matching subset), and unions the result into the run set. Forced and method batches at the same sequence both apply.

**Tech Stack:** Java 8 source level, JDBC (H2 + Postgres) and serialized data stores, Gradle and Maven plugins, JUnit + given/when/then unit tests.

## Global Constraints

- Source level is Java 8 (`sourceCompatibility = '1.8'`). No Java 9+ APIs.
- Every new or modified method gets a javadoc: purpose plus `@param` for each parameter and `@return` when it returns a value.
- Unit tests use `// given` / `// when` / `// then` marker comments.
- Use ASCII hyphen `-` only. No em-dash character in code, comments, javadocs, or commit messages.
- No backwards-compatibility shims: change signatures directly and update all callers in the same change. No transitional overloads.
- Reference `WIKI.md` chapters (not design/plan docs) from any javadoc or comment.
- Commit at the end of each task. End every commit message body with:
  `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`
- Work on branch `feature/library-static-forced-selection` (already created).

---

### Task 1: `PendingLibraryForcedSelection` domain model

**Files:**
- Create: `tia-core/src/main/java/org/tiatesting/core/model/PendingLibraryForcedSelection.java`
- Test: `tia-core/src/test/java/org/tiatesting/core/model/PendingLibraryForcedSelectionTest.java`

**Interfaces:**
- Consumes: `org.tiatesting.core.staticselection.StaticTestSelectionRuleMode` (existing enum: `RUN_ALL`, `SUITE_NAMES`, `ANNOTATIONS_TAGS`).
- Produces: `PendingLibraryForcedSelection` with getters `getGroupArtifact()`, `getStampVersion()`, `getPublishSeq()` (long), `getRuleName()`, `getMode()` (`StaticTestSelectionRuleMode`), `getSuiteNamePatterns()` (`List<String>`); equality on `(groupArtifact, publishSeq, ruleName)`.

- [ ] **Step 1: Write the failing test**

```java
package org.tiatesting.core.model;

import org.junit.Test;
import org.tiatesting.core.staticselection.StaticTestSelectionRuleMode;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.*;

public class PendingLibraryForcedSelectionTest {

    @Test
    public void constructorPopulatesAllFields() {
        // given
        PendingLibraryForcedSelection forced = new PendingLibraryForcedSelection(
                "com.acme:widget", "1.2.0", 5L, "sql-run-all",
                StaticTestSelectionRuleMode.SUITE_NAMES, Arrays.asList("Repo.*", ".*IT"));

        // when / then
        assertEquals("com.acme:widget", forced.getGroupArtifact());
        assertEquals("1.2.0", forced.getStampVersion());
        assertEquals(5L, forced.getPublishSeq());
        assertEquals("sql-run-all", forced.getRuleName());
        assertEquals(StaticTestSelectionRuleMode.SUITE_NAMES, forced.getMode());
        assertEquals(Arrays.asList("Repo.*", ".*IT"), forced.getSuiteNamePatterns());
    }

    @Test
    public void nullPatternsBecomeEmptyList() {
        // given
        PendingLibraryForcedSelection forced = new PendingLibraryForcedSelection(
                "com.acme:widget", "1.2.0", 5L, "run-all",
                StaticTestSelectionRuleMode.RUN_ALL, null);

        // when / then
        assertEquals(Collections.emptyList(), forced.getSuiteNamePatterns());
    }

    @Test
    public void equalityKeyedOnGroupArtifactPublishSeqAndRuleName() {
        // given
        PendingLibraryForcedSelection a = new PendingLibraryForcedSelection(
                "com.acme:widget", "1.2.0", 5L, "r1", StaticTestSelectionRuleMode.RUN_ALL, null);
        PendingLibraryForcedSelection b = new PendingLibraryForcedSelection(
                "com.acme:widget", "9.9.9", 5L, "r1", StaticTestSelectionRuleMode.RUN_ALL, null);
        PendingLibraryForcedSelection c = new PendingLibraryForcedSelection(
                "com.acme:widget", "1.2.0", 5L, "r2", StaticTestSelectionRuleMode.RUN_ALL, null);

        // when / then
        assertEquals(a, b);
        assertNotEquals(a, c);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :tia-core:test --tests 'org.tiatesting.core.model.PendingLibraryForcedSelectionTest'`
Expected: FAIL - compilation error, `PendingLibraryForcedSelection` does not exist.

- [ ] **Step 3: Write minimal implementation**

```java
package org.tiatesting.core.model;

import org.tiatesting.core.staticselection.StaticTestSelectionRuleMode;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Represents a forced test-selection intent recorded for a single published library build.
 * Produced at publish time when one of the library's own static test selection rules matches a
 * file changed since the library's previous publish, and drained by the consumer against the build
 * it resolves - exactly like {@link PendingLibraryImpactedMethod}, but carrying a rule mode plus
 * suite-name patterns rather than method ids. See the library publish-time stamping chapter in
 * {@code WIKI.md}.
 */
public class PendingLibraryForcedSelection implements Serializable {
    private static final long serialVersionUID = 1L;

    /** {@code groupId:artifactId} of the tracked library. */
    private String groupArtifact;

    /** The version the batch's publish shipped under - display only; the drain keys on {@link #publishSeq}. */
    private String stampVersion;

    /** The publish-ledger sequence of the build this forced selection shipped in. */
    private long publishSeq;

    /** The display name of the library static rule that produced this forced selection. */
    private String ruleName;

    /** The selection mode: {@code RUN_ALL} or {@code SUITE_NAMES}. */
    private StaticTestSelectionRuleMode mode;

    /** The rule's suite-name regex patterns; empty for {@code RUN_ALL}. */
    private List<String> suiteNamePatterns;

    public PendingLibraryForcedSelection() {
        this.suiteNamePatterns = new ArrayList<>();
    }

    /**
     * Construct a fully populated forced-selection batch.
     *
     * @param groupArtifact {@code groupId:artifactId} of the tracked library.
     * @param stampVersion the version the batch's publish shipped under (display only).
     * @param publishSeq the publish-ledger sequence of the build this forced selection shipped in.
     * @param ruleName the display name of the matching library static rule.
     * @param mode the selection mode.
     * @param suiteNamePatterns the suite-name regex patterns; {@code null} is treated as empty.
     */
    public PendingLibraryForcedSelection(String groupArtifact, String stampVersion, long publishSeq,
                                         String ruleName, StaticTestSelectionRuleMode mode,
                                         List<String> suiteNamePatterns) {
        this.groupArtifact = groupArtifact;
        this.stampVersion = stampVersion;
        this.publishSeq = publishSeq;
        this.ruleName = ruleName;
        this.mode = mode;
        this.suiteNamePatterns = suiteNamePatterns != null ? new ArrayList<>(suiteNamePatterns) : new ArrayList<>();
    }

    public String getGroupArtifact() { return groupArtifact; }
    public void setGroupArtifact(String groupArtifact) { this.groupArtifact = groupArtifact; }

    public String getStampVersion() { return stampVersion; }
    public void setStampVersion(String stampVersion) { this.stampVersion = stampVersion; }

    public long getPublishSeq() { return publishSeq; }
    public void setPublishSeq(long publishSeq) { this.publishSeq = publishSeq; }

    public String getRuleName() { return ruleName; }
    public void setRuleName(String ruleName) { this.ruleName = ruleName; }

    public StaticTestSelectionRuleMode getMode() { return mode; }
    public void setMode(StaticTestSelectionRuleMode mode) { this.mode = mode; }

    public List<String> getSuiteNamePatterns() { return suiteNamePatterns; }
    public void setSuiteNamePatterns(List<String> suiteNamePatterns) {
        this.suiteNamePatterns = suiteNamePatterns != null ? new ArrayList<>(suiteNamePatterns) : new ArrayList<>();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PendingLibraryForcedSelection that = (PendingLibraryForcedSelection) o;
        return publishSeq == that.publishSeq
                && Objects.equals(groupArtifact, that.groupArtifact)
                && Objects.equals(ruleName, that.ruleName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(groupArtifact, publishSeq, ruleName);
    }

    @Override
    public String toString() {
        return "PendingLibraryForcedSelection{groupArtifact='" + groupArtifact
                + "', publishSeq=" + publishSeq
                + ", ruleName='" + ruleName
                + "', mode=" + mode
                + ", patterns=" + suiteNamePatterns + "}";
    }

    private static List<String> emptyIfNull(List<String> in) {
        return in != null ? in : Collections.<String>emptyList();
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :tia-core:test --tests 'org.tiatesting.core.model.PendingLibraryForcedSelectionTest'`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add tia-core/src/main/java/org/tiatesting/core/model/PendingLibraryForcedSelection.java \
        tia-core/src/test/java/org/tiatesting/core/model/PendingLibraryForcedSelectionTest.java
git commit -m "feat(library): add PendingLibraryForcedSelection domain model

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 2: Persist forced selections (DataStore interface + both stores)

**Files:**
- Modify: `tia-core/src/main/java/org/tiatesting/core/persistence/DataStore.java`
- Modify: `tia-core/src/main/java/org/tiatesting/core/persistence/JdbcDataStore.java`
- Modify: `tia-core/src/main/java/org/tiatesting/core/persistence/SerializedDataStore.java`
- Modify: `tia-core/src/main/java/org/tiatesting/core/library/LibraryPublishStamper.java` (single caller of `persistLibraryPublish`)
- Test: `tia-core/src/test/java/org/tiatesting/core/persistence/DatastoreEquivalenceTest.java`

**Interfaces:**
- Consumes: `PendingLibraryForcedSelection` (Task 1); existing `persistLibraryPublish(LibraryPublish, Set<Integer>)`.
- Produces on `DataStore`:
  - `long persistLibraryPublish(LibraryPublish publish, Set<Integer> impactedMethodIds, List<PendingLibraryForcedSelection> forcedSelections)` (replaces the 2-arg form)
  - `List<PendingLibraryForcedSelection> readAllPendingLibraryForcedSelections()`
  - `List<PendingLibraryForcedSelection> readPendingLibraryForcedSelections(String groupArtifact)`
  - `void deletePendingLibraryForcedSelections(String groupArtifact, long publishSeq)`

New table `tia_pending_library_forced_selection`, columns `group_artifact` VARCHAR(512), `stamp_version` VARCHAR(128), `publish_seq` BIGINT, `rule_name` VARCHAR(256), `mode` VARCHAR(32), `suite_name_pattern` VARCHAR(512) (nullable; RUN_ALL stores one row with NULL pattern). One row per pattern; batch grouped by `(group_artifact, publish_seq, rule_name)`.

- [ ] **Step 1: Write the failing test** (add to `DatastoreEquivalenceTest`, which runs the same assertions against H2 and Postgres via its existing parameterization)

```java
    @Test
    public void forcedSelectionRoundTripAndDelete() {
        // given
        DataStore store = newStore();
        TrackedLibrary lib = new TrackedLibrary("com.acme:widget", "com.acme", "widget");
        store.persistTrackedLibrary(lib);
        LibraryPublish publish = new LibraryPublish("com.acme:widget", "1.2.0", "hash", "commitA", 111L);
        List<PendingLibraryForcedSelection> forced = Arrays.asList(
                new PendingLibraryForcedSelection("com.acme:widget", "1.2.0", 0L, "run-all",
                        StaticTestSelectionRuleMode.RUN_ALL, Collections.<String>emptyList()),
                new PendingLibraryForcedSelection("com.acme:widget", "1.2.0", 0L, "repos",
                        StaticTestSelectionRuleMode.SUITE_NAMES, Arrays.asList("Repo.*", ".*IT")));

        // when
        long seq = store.persistLibraryPublish(publish, Collections.<Integer>emptySet(), forced);
        List<PendingLibraryForcedSelection> read = store.readAllPendingLibraryForcedSelections();

        // then
        assertEquals(2, read.size());
        PendingLibraryForcedSelection runAll = findByRule(read, "run-all");
        assertEquals(StaticTestSelectionRuleMode.RUN_ALL, runAll.getMode());
        assertEquals(seq, runAll.getPublishSeq());
        assertTrue(runAll.getSuiteNamePatterns().isEmpty());
        PendingLibraryForcedSelection repos = findByRule(read, "repos");
        assertEquals(StaticTestSelectionRuleMode.SUITE_NAMES, repos.getMode());
        assertEquals(new HashSet<>(Arrays.asList("Repo.*", ".*IT")),
                new HashSet<>(repos.getSuiteNamePatterns()));

        // when
        store.deletePendingLibraryForcedSelections("com.acme:widget", seq);

        // then
        assertTrue(store.readAllPendingLibraryForcedSelections().isEmpty());
    }

    private static PendingLibraryForcedSelection findByRule(List<PendingLibraryForcedSelection> list, String rule) {
        return list.stream().filter(f -> rule.equals(f.getRuleName())).findFirst().orElseThrow(AssertionError::new);
    }
```

Add imports to the test as needed: `PendingLibraryForcedSelection`, `StaticTestSelectionRuleMode`, `java.util.*`. Use the test's existing helper for constructing a store (`newStore()` / the parameterized store field - match the existing pattern in the file; if the file uses a `@Parameter` `dataStore` field rather than `newStore()`, use that field instead).

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :tia-core:test --tests 'org.tiatesting.core.persistence.DatastoreEquivalenceTest'`
Expected: FAIL - `persistLibraryPublish` 3-arg form and forced-selection methods do not exist.

- [ ] **Step 3a: Update `DataStore` interface**

Replace the existing `persistLibraryPublish(LibraryPublish, Set<Integer>)` declaration with the 3-arg form and add the three forced-selection methods. Full javadocs:

```java
    /**
     * Persist a published library build and, atomically, the impacted-method stamp and any
     * forced-selection batches for that build. Assigns and returns the next per-library publish
     * sequence. All rows are written in one transaction so a ledger row can never exist without
     * the stamps of the build it identifies.
     *
     * @param publish the publish-ledger row to write.
     * @param impactedMethodIds the tracked source method ids impacted since the baseline; may be empty.
     * @param forcedSelections the forced-selection batches produced by the library's static rules;
     *                         may be empty.
     * @return the assigned publish sequence.
     */
    long persistLibraryPublish(final LibraryPublish publish, final Set<Integer> impactedMethodIds,
                               final List<PendingLibraryForcedSelection> forcedSelections);

    /**
     * Read every pending forced-selection batch across all tracked libraries.
     *
     * @return the forced-selection batches; never {@code null}, may be empty.
     */
    List<PendingLibraryForcedSelection> readAllPendingLibraryForcedSelections();

    /**
     * Read the pending forced-selection batches for one tracked library.
     *
     * @param groupArtifact the {@code groupId:artifactId} of the library.
     * @return the library's forced-selection batches; never {@code null}, may be empty.
     */
    List<PendingLibraryForcedSelection> readPendingLibraryForcedSelections(final String groupArtifact);

    /**
     * Delete the forced-selection batches of one published build after they have been drained.
     *
     * @param groupArtifact the {@code groupId:artifactId} of the library.
     * @param publishSeq the publish sequence whose forced-selection rows to delete.
     */
    void deletePendingLibraryForcedSelections(final String groupArtifact, final long publishSeq);
```

Add `import org.tiatesting.core.model.PendingLibraryForcedSelection;` to `DataStore.java`.

- [ ] **Step 3b: Implement in `JdbcDataStore`**

Add column/table constants near the existing ones (after `COL_STAMP_VERSION`):

```java
    private static final String TABLE_TIA_PENDING_LIBRARY_FORCED_SELECTION = "tia_pending_library_forced_selection";
    private static final String COL_RULE_NAME = "rule_name";
    private static final String COL_MODE = "mode";
    private static final String COL_SUITE_NAME_PATTERN = "suite_name_pattern";
```

Add DDL + ensure helper (place beside `buildCreatePendingLibraryImpactedMethodTableSql`):

```java
    /**
     * Build the DDL for the {@code tia_pending_library_forced_selection} table - one row per
     * (library build, rule, suite-name pattern); RUN_ALL stores a single row with a NULL pattern.
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
    private void ensurePendingLibraryForcedSelectionTableExists(Connection connection) throws SQLException {
        ensureLibraryTableExists(connection);
        if (!checkTableExists(connection, TABLE_TIA_PENDING_LIBRARY_FORCED_SELECTION)) {
            Statement statement = connection.createStatement();
            statement.executeUpdate(buildCreatePendingLibraryForcedSelectionTableSql());
            log.debug("Created {} table in existing Tia DB", TABLE_TIA_PENDING_LIBRARY_FORCED_SELECTION);
        }
    }
```

Register the DDL alongside the other create statements in the schema-bootstrap method (the method around line 1843 that runs `createPendingLibraryMethodTableSql`): add
```java
        statement.executeUpdate(buildCreatePendingLibraryForcedSelectionTableSql());
```
immediately after the pending-method table is created there, so a fresh DB gets the table.

Change `persistLibraryPublish` to the 3-arg form. Inside the existing transaction (after the method-stamp `executeBatch()` block, before `connection.commit()`), add:

```java
            if (forcedSelections != null && !forcedSelections.isEmpty()) {
                ensurePendingLibraryForcedSelectionTableExists(connection);
                String forcedSql = "INSERT INTO " + TABLE_TIA_PENDING_LIBRARY_FORCED_SELECTION + " ("
                        + COL_GROUP_ARTIFACT + ", " + COL_STAMP_VERSION + ", " + COL_PUBLISH_SEQ + ", "
                        + COL_RULE_NAME + ", " + COL_MODE + ", " + COL_SUITE_NAME_PATTERN + ") "
                        + "VALUES (?, ?, ?, ?, ?, ?)";
                PreparedStatement forcedPs = connection.prepareStatement(forcedSql);
                for (PendingLibraryForcedSelection forced : forcedSelections) {
                    if (forced.getSuiteNamePatterns().isEmpty()) {
                        forcedPs.setString(1, publish.getGroupArtifact());
                        forcedPs.setString(2, publish.getPublishedVersion());
                        forcedPs.setLong(3, assignedSeq);
                        forcedPs.setString(4, forced.getRuleName());
                        forcedPs.setString(5, forced.getMode().name());
                        forcedPs.setNull(6, java.sql.Types.VARCHAR);
                        forcedPs.addBatch();
                    } else {
                        for (String pattern : forced.getSuiteNamePatterns()) {
                            forcedPs.setString(1, publish.getGroupArtifact());
                            forcedPs.setString(2, publish.getPublishedVersion());
                            forcedPs.setLong(3, assignedSeq);
                            forcedPs.setString(4, forced.getRuleName());
                            forcedPs.setString(5, forced.getMode().name());
                            forcedPs.setString(6, pattern);
                            forcedPs.addBatch();
                        }
                    }
                }
                forcedPs.executeBatch();
            }
```

Add read/delete methods (place beside the pending-method equivalents around line 663-765):

```java
    /**
     * Read every pending forced-selection batch across all tracked libraries.
     *
     * @return the forced-selection batches; never {@code null}, may be empty.
     */
    @Override
    public List<PendingLibraryForcedSelection> readAllPendingLibraryForcedSelections() {
        Connection connection = getConnection();
        try {
            if (!checkTableExists(connection, TABLE_TIA_PENDING_LIBRARY_FORCED_SELECTION)) {
                return new ArrayList<>();
            }
            String sql = "SELECT * FROM " + TABLE_TIA_PENDING_LIBRARY_FORCED_SELECTION;
            Statement statement = connection.createStatement();
            return buildForcedBatchesFromResultSet(statement.executeQuery(sql));
        } catch (SQLException e) {
            throw new TiaPersistenceException(e);
        } finally {
            closeQuietly(connection);
        }
    }

    /**
     * Read the pending forced-selection batches for one tracked library.
     *
     * @param groupArtifact the {@code groupId:artifactId} of the library.
     * @return the library's forced-selection batches; never {@code null}, may be empty.
     */
    @Override
    public List<PendingLibraryForcedSelection> readPendingLibraryForcedSelections(final String groupArtifact) {
        Connection connection = getConnection();
        try {
            if (!checkTableExists(connection, TABLE_TIA_PENDING_LIBRARY_FORCED_SELECTION)) {
                return new ArrayList<>();
            }
            String sql = "SELECT * FROM " + TABLE_TIA_PENDING_LIBRARY_FORCED_SELECTION
                    + " WHERE " + COL_GROUP_ARTIFACT + " = ?";
            PreparedStatement ps = connection.prepareStatement(sql);
            ps.setString(1, groupArtifact);
            return buildForcedBatchesFromResultSet(ps.executeQuery());
        } catch (SQLException e) {
            throw new TiaPersistenceException(e);
        } finally {
            closeQuietly(connection);
        }
    }

    /**
     * Delete the forced-selection rows of one published build after they have been drained.
     *
     * @param groupArtifact the {@code groupId:artifactId} of the library.
     * @param publishSeq the publish sequence whose forced-selection rows to delete.
     */
    @Override
    public void deletePendingLibraryForcedSelections(final String groupArtifact, final long publishSeq) {
        Connection connection = getConnection();
        try {
            if (!checkTableExists(connection, TABLE_TIA_PENDING_LIBRARY_FORCED_SELECTION)) {
                return;
            }
            String sql = "DELETE FROM " + TABLE_TIA_PENDING_LIBRARY_FORCED_SELECTION
                    + " WHERE " + COL_GROUP_ARTIFACT + " = ? AND " + COL_PUBLISH_SEQ + " = ?";
            PreparedStatement ps = connection.prepareStatement(sql);
            ps.setString(1, groupArtifact);
            ps.setLong(2, publishSeq);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new TiaPersistenceException(e);
        } finally {
            closeQuietly(connection);
        }
    }

    /**
     * Group flat forced-selection rows into batches keyed by
     * {@code (group_artifact, publish_seq, rule_name)}, collecting each batch's non-null patterns.
     *
     * @param resultSet the result set over the forced-selection table.
     * @return one {@link PendingLibraryForcedSelection} per distinct batch key.
     * @throws SQLException if the result set cannot be read.
     */
    private List<PendingLibraryForcedSelection> buildForcedBatchesFromResultSet(ResultSet resultSet) throws SQLException {
        Map<String, PendingLibraryForcedSelection> batchMap = new LinkedHashMap<>();
        while (resultSet.next()) {
            String ga = resultSet.getString(COL_GROUP_ARTIFACT);
            long seq = resultSet.getLong(COL_PUBLISH_SEQ);
            String ruleName = resultSet.getString(COL_RULE_NAME);
            String key = ga + "|" + seq + "|" + ruleName;
            PendingLibraryForcedSelection batch = batchMap.get(key);
            if (batch == null) {
                batch = new PendingLibraryForcedSelection(ga, resultSet.getString(COL_STAMP_VERSION),
                        seq, ruleName,
                        StaticTestSelectionRuleMode.valueOf(resultSet.getString(COL_MODE)),
                        new ArrayList<String>());
                batchMap.put(key, batch);
            }
            String pattern = resultSet.getString(COL_SUITE_NAME_PATTERN);
            if (pattern != null) {
                batch.getSuiteNamePatterns().add(pattern);
            }
        }
        return new ArrayList<>(batchMap.values());
    }
```

Add imports to `JdbcDataStore`: `org.tiatesting.core.model.PendingLibraryForcedSelection`, `org.tiatesting.core.staticselection.StaticTestSelectionRuleMode`. If no `closeQuietly` helper exists, replace `closeQuietly(connection)` with the existing `try { connection.close(); } catch (SQLException e) { throw new TiaPersistenceException(e); }` pattern used elsewhere in the file.

- [ ] **Step 3c: Implement in `SerializedDataStore`**

Follow the file's existing pattern for `PendingLibraryImpactedMethod`. If pending methods are stored on the in-memory `TiaData` object, add a parallel `List<PendingLibraryForcedSelection>` field on the same model holder (mirroring the method list), and implement the three new methods plus the 3-arg `persistLibraryPublish` by writing the forced list alongside the method stamps under the assigned sequence. Match whatever collection/keying `PendingLibraryImpactedMethod` uses so serialization stays symmetric.

- [ ] **Step 3d: Update the caller in `LibraryPublishStamper`**

Both current `persistLibraryPublish(publish, ...)` calls become 3-arg. For now pass `Collections.<PendingLibraryForcedSelection>emptyList()` at both call sites (the SEEDED path at line ~95 and the STAMPED path at line ~106). Task 3 replaces the STAMPED-path empty list with the computed forced selections.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :tia-core:test --tests 'org.tiatesting.core.persistence.DatastoreEquivalenceTest'`
Expected: PASS, including the Postgres-parameterized run (uses the existing test's Postgres harness).

- [ ] **Step 5: Commit**

```bash
git add tia-core/src/main/java/org/tiatesting/core/persistence/DataStore.java \
        tia-core/src/main/java/org/tiatesting/core/persistence/JdbcDataStore.java \
        tia-core/src/main/java/org/tiatesting/core/persistence/SerializedDataStore.java \
        tia-core/src/main/java/org/tiatesting/core/library/LibraryPublishStamper.java \
        tia-core/src/test/java/org/tiatesting/core/persistence/DatastoreEquivalenceTest.java
git commit -m "feat(library): persist forced-selection batches with the publish ledger

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 3: Evaluate library static rules at stamp time

**Files:**
- Modify: `tia-core/src/main/java/org/tiatesting/core/library/LibraryPublishStamper.java`
- Modify: `tia-core/src/test/java/org/tiatesting/core/library/LibraryPublishStamperTest.java`

**Interfaces:**
- Consumes: `StaticTestSelectionConfig` (existing), `StaticTestSelectionRule.getFilePathPattern()/getName()/getMode()/getSuiteNamePatterns()`, `vcsReader.getChangedFilePaths(String, boolean)`.
- Produces: `stampPublish(DataStore, VCSReader, String groupArtifact, String publishedVersion, String jarFilePath, StaticTestSelectionConfig staticConfig)` - the static config is a new required parameter (pass `StaticTestSelectionConfig.EMPTY` when the library configures no rules). `PublishStampResult` gains `getForcedSelections()` returning `List<PendingLibraryForcedSelection>`.

- [ ] **Step 1: Write the failing test** (add to `LibraryPublishStamperTest`, matching its existing mock setup for `DataStore` and `VCSReader`)

```java
    @Test
    public void recordsForcedSelectionWhenNonCodeFileMatchesLibraryRule() {
        // given a tracked library with a baseline, a previous publish, and a changed SQL file
        // (mirror the existing test's tracked-library + ledger mock setup)
        TrackedLibrary tracked = trackedLibraryWithBaseline("com.acme:widget", "baselineCommit", "prevCommit");
        when(dataStore.readTrackedLibraries()).thenReturn(singletonMap("com.acme:widget", tracked));
        when(dataStore.readLibraryPublishes("com.acme:widget"))
                .thenReturn(singletonList(new LibraryPublish("com.acme:widget", "1.1.0", "h", "prevCommit", 1L)));
        when(vcsReader.getHeadCommit()).thenReturn("headCommit");
        // no tracked method changes
        when(vcsReader.getDiffFiles(eq("baselineCommit"), anyList(), anyList(), eq(false)))
                .thenReturn(Collections.<SourceFileDiffContext>emptySet());
        // a SQL file changed since the previous publish, under the library source dir
        when(vcsReader.getChangedFilePaths("prevCommit", false))
                .thenReturn(new HashSet<>(Arrays.asList("libs/widget/src/main/resources/db/V2__add.sql")));
        when(dataStore.persistLibraryPublish(any(), anySet(), anyList())).thenReturn(2L);

        StaticTestSelectionConfig config = new StaticTestSelectionConfig(singletonList(
                new StaticTestSelectionRule("sql-run-all", "\\.sql$", StaticTestSelectionRuleMode.RUN_ALL, null)));

        // when
        LibraryPublishStamper.PublishStampResult result = new LibraryPublishStamper()
                .stampPublish(dataStore, vcsReader, "com.acme:widget", "1.2.0", null, config);

        // then a RUN_ALL forced selection is recorded for the publish
        ArgumentCaptor<List<PendingLibraryForcedSelection>> forcedCaptor = ArgumentCaptor.forClass(List.class);
        verify(dataStore).persistLibraryPublish(any(), anySet(), forcedCaptor.capture());
        List<PendingLibraryForcedSelection> forced = forcedCaptor.getValue();
        assertEquals(1, forced.size());
        assertEquals(StaticTestSelectionRuleMode.RUN_ALL, forced.get(0).getMode());
        assertEquals("sql-run-all", forced.get(0).getRuleName());
    }

    @Test
    public void recordsNoForcedSelectionWhenNoRuleMatches() {
        // given the same setup but the changed file does not match the rule
        TrackedLibrary tracked = trackedLibraryWithBaseline("com.acme:widget", "baselineCommit", "prevCommit");
        when(dataStore.readTrackedLibraries()).thenReturn(singletonMap("com.acme:widget", tracked));
        when(dataStore.readLibraryPublishes("com.acme:widget"))
                .thenReturn(singletonList(new LibraryPublish("com.acme:widget", "1.1.0", "h", "prevCommit", 1L)));
        when(vcsReader.getHeadCommit()).thenReturn("headCommit");
        when(vcsReader.getDiffFiles(eq("baselineCommit"), anyList(), anyList(), eq(false)))
                .thenReturn(Collections.<SourceFileDiffContext>emptySet());
        when(vcsReader.getChangedFilePaths("prevCommit", false))
                .thenReturn(new HashSet<>(Arrays.asList("libs/widget/src/main/java/Foo.java")));
        when(dataStore.persistLibraryPublish(any(), anySet(), anyList())).thenReturn(2L);

        StaticTestSelectionConfig config = new StaticTestSelectionConfig(singletonList(
                new StaticTestSelectionRule("sql-run-all", "\\.sql$", StaticTestSelectionRuleMode.RUN_ALL, null)));

        // when
        new LibraryPublishStamper().stampPublish(dataStore, vcsReader, "com.acme:widget", "1.2.0", null, config);

        // then an empty forced list is persisted
        ArgumentCaptor<List<PendingLibraryForcedSelection>> forcedCaptor = ArgumentCaptor.forClass(List.class);
        verify(dataStore).persistLibraryPublish(any(), anySet(), forcedCaptor.capture());
        assertTrue(forcedCaptor.getValue().isEmpty());
    }
```

Add a `trackedLibraryWithBaseline(...)` test helper if the file lacks one: constructs a `TrackedLibrary`, sets `mappingBaselineCommit` and source dirs (`libs/widget` so the SQL path prefix-matches). Match the existing test file's mock field names (`dataStore`, `vcsReader`) and imports (`org.mockito.ArgumentCaptor`, `static org.mockito.Mockito.*`, `StaticTestSelectionConfig`, `StaticTestSelectionRule`, `StaticTestSelectionRuleMode`, `PendingLibraryForcedSelection`).

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :tia-core:test --tests 'org.tiatesting.core.library.LibraryPublishStamperTest'`
Expected: FAIL - `stampPublish` has no `StaticTestSelectionConfig` parameter.

- [ ] **Step 3: Implement**

Change the `stampPublish` signature to add `final StaticTestSelectionConfig staticConfig` as the last parameter. In the `SEEDED` branch, pass `Collections.<PendingLibraryForcedSelection>emptyList()` to `persistLibraryPublish` (SEEDED forces nothing). In the `STAMPED` branch, compute forced selections and pass them:

```java
        String previousPublishCommit = resolvePreviousPublishCommit(dataStore, groupArtifact, tracked);
        Set<Integer> impactedMethods = findImpactedMethodsSinceBaseline(dataStore, vcsReader, tracked,
                previousPublishCommit);
        List<PendingLibraryForcedSelection> forcedSelections = evaluateForcedSelections(
                vcsReader, tracked, previousPublishCommit, publishedVersion, staticConfig);
        long seq = dataStore.persistLibraryPublish(publish, impactedMethods, forcedSelections);
        log.info("Stamped publish of library '{}' version '{}' at seq {} with {} impacted methods and {} forced-selection rule(s).",
                groupArtifact, publishedVersion, seq, impactedMethods.size(), forcedSelections.size());
        return new PublishStampResult(PublishStampResult.Outcome.STAMPED, seq, impactedMethods, forcedSelections);
```

Add the evaluation method:

```java
    /**
     * Evaluate the library's own static test selection rules against the files it changed since its
     * previous publish, producing a forced-selection batch per matching rule. The since-previous-publish
     * scope deduplicates: a version-only or no-matching-change re-publish yields nothing, mirroring the
     * method-stamp dedup. All changed file types are considered (the unfiltered
     * {@link VCSReader#getChangedFilePaths}), restricted to the library's own source dirs.
     * See the library publish-time stamping chapter in {@code WIKI.md}.
     *
     * @param vcsReader the VCS reader for the shared repository.
     * @param tracked the tracked library being published.
     * @param previousPublishCommit the commit of the library's previous publish (the diff baseline here).
     * @param publishedVersion the version being published (recorded as each batch's stamp version).
     * @param staticConfig the library's static test selection config; may be disabled/empty.
     * @return the forced-selection batches; empty when the config is disabled or no rule matches.
     */
    private List<PendingLibraryForcedSelection> evaluateForcedSelections(
            VCSReader vcsReader, TrackedLibrary tracked, String previousPublishCommit,
            String publishedVersion, StaticTestSelectionConfig staticConfig) {
        if (staticConfig == null || !staticConfig.isEnabled() || previousPublishCommit == null) {
            return Collections.emptyList();
        }
        List<String> sourceDirs = resolveLibrarySourceDirs(tracked);
        Set<String> changedPaths = vcsReader.getChangedFilePaths(previousPublishCommit, false);
        Set<String> libraryScopedPaths = restrictPathsToLibraryDirs(changedPaths, sourceDirs);
        if (libraryScopedPaths.isEmpty()) {
            return Collections.emptyList();
        }

        List<PendingLibraryForcedSelection> forced = new ArrayList<>();
        for (StaticTestSelectionRule rule : staticConfig.getRules()) {
            boolean matched = false;
            for (String path : libraryScopedPaths) {
                if (rule.getFilePathPattern().matcher(path).find()) {
                    matched = true;
                    break;
                }
            }
            if (matched) {
                List<String> patternStrings = new ArrayList<>();
                for (java.util.regex.Pattern p : rule.getSuiteNamePatterns()) {
                    patternStrings.add(p.pattern());
                }
                forced.add(new PendingLibraryForcedSelection(tracked.getGroupArtifact(), publishedVersion,
                        0L, rule.getName(), rule.getMode(), patternStrings));
                log.info("Library '{}' static rule '{}' matched a changed file - recording forced selection (mode {}).",
                        tracked.getGroupArtifact(), rule.getName(), rule.getMode());
            }
        }
        return forced;
    }

    /**
     * Restrict a set of repo-relative changed paths to those under the library's source dirs.
     * Both sides are normalized to forward slashes and compared by path-prefix so a repo-relative
     * diff path matches an absolute-or-relative recorded source dir by its trailing segment.
     *
     * @param changedPaths the repo-relative changed paths.
     * @param sourceDirs the library's recorded source dirs (absolute or relative).
     * @return the subset of {@code changedPaths} that fall under any of the source dirs; when no
     *         source dir is recorded, the input is returned unchanged (the whole library repo).
     */
    private Set<String> restrictPathsToLibraryDirs(Set<String> changedPaths, List<String> sourceDirs) {
        if (sourceDirs.isEmpty()) {
            return changedPaths;
        }
        Set<String> kept = new HashSet<>();
        for (String path : changedPaths) {
            String normPath = path.replace('\\', '/');
            for (String dir : sourceDirs) {
                String normDir = dir.replace('\\', '/');
                String tail = normDir.startsWith("/") || normDir.matches("^[A-Za-z]:.*")
                        ? deriveRepoRelativeTail(normDir) : normDir;
                if (normPath.startsWith(tail) || normPath.contains("/" + tail)) {
                    kept.add(path);
                    break;
                }
            }
        }
        return kept;
    }

    /**
     * Derive a repo-relative tail from an absolute source dir by dropping everything up to and
     * including the repository-root-relative portion is not known here, so fall back to the last
     * two path segments, which is specific enough to scope to the library module in a shared repo.
     *
     * @param normDir a forward-slash-normalized absolute source dir.
     * @return the last two segments of the path (or the whole thing when it has fewer).
     */
    private String deriveRepoRelativeTail(String normDir) {
        String trimmed = normDir.endsWith("/") ? normDir.substring(0, normDir.length() - 1) : normDir;
        String[] segs = trimmed.split("/");
        if (segs.length <= 2) {
            return trimmed.startsWith("/") ? trimmed.substring(1) : trimmed;
        }
        return segs[segs.length - 2] + "/" + segs[segs.length - 1];
    }
```

> Implementer note on path scoping: the recorded library source dirs may be absolute while `getChangedFilePaths` returns repo-relative paths. The helper above uses a trailing-segment heuristic. If, during implementation, `TrackedLibrary` already stores repo-relative source dirs (verify by inspecting a real `tia_library` row or the reconcile code that writes `sourceDirsCsv`), simplify `restrictPathsToLibraryDirs` to a direct `normPath.startsWith(normDir)` and delete `deriveRepoRelativeTail`. Pick the form that matches the stored data and cover it with the test's SQL path (`libs/widget/...` under source dir `libs/widget`).

Update `PublishStampResult`: add a `private final List<PendingLibraryForcedSelection> forcedSelections;` field, a new 4-arg constructor `PublishStampResult(Outcome, long, Set<Integer>, List<PendingLibraryForcedSelection>)`, a `getForcedSelections()` getter, and update the two existing `new PublishStampResult(...)` sites (SKIPPED_NOT_TRACKED and SEEDED) to pass `Collections.<PendingLibraryForcedSelection>emptyList()`.

Add imports: `StaticTestSelectionConfig`, `StaticTestSelectionRule`, `PendingLibraryForcedSelection`.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :tia-core:test --tests 'org.tiatesting.core.library.LibraryPublishStamperTest'`
Expected: PASS (existing tests plus the two new ones). Update any existing `stampPublish(...)` call in that test file to pass `StaticTestSelectionConfig.EMPTY`.

- [ ] **Step 5: Commit**

```bash
git add tia-core/src/main/java/org/tiatesting/core/library/LibraryPublishStamper.java \
        tia-core/src/test/java/org/tiatesting/core/library/LibraryPublishStamperTest.java
git commit -m "feat(library): evaluate library static rules at stamp time into forced selections

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 4: Thread library static config into the publish-stamp task (Gradle + Maven)

**Files:**
- Modify: `tia-gradle/src/main/java/org/tiatesting/gradle/plugin/TiaBasePlugin.java` (the `stampPublish()` private method, ~line 226-244)
- Modify: `tia-maven-plugin/src/main/java/org/tiatesting/maven/AbstractPublishLibStampMojo.java` (~line 47-48)
- Test: `tia-gradle/src/test/java/org/tiatesting/gradle/plugin/TiaBasePluginPublishStampHookTest.java`

**Interfaces:**
- Consumes: `stampPublish(..., StaticTestSelectionConfig)` (Task 3); existing `buildStaticTestSelectionConfig()` on both plugins.
- Produces: no new public API; both publish paths now pass the library's own static config.

- [ ] **Step 1: Write/adjust the failing test**

In `TiaBasePluginPublishStampHookTest`, add an assertion (or a new test) that when the extension has `staticTestSelectionRules` configured, the stamp path passes a non-empty `StaticTestSelectionConfig` to the stamper. If the existing test stubs the stamper, capture the config argument; if it exercises the real stamper, assert a forced row is persisted for a matching changed file. Match the file's existing harness.

```java
    @Test
    public void publishStampPassesConfiguredStaticRulesToStamper() {
        // given the tia extension has a static rule configured
        extension.setStaticTestSelectionRules(singletonList(
                gradleRule("sql-run-all", "\\.sql$", "RUN_ALL", null)));

        // when the publish stamp hook runs (invoke the same entry point the existing tests use)
        StaticTestSelectionConfig built = plugin.buildStaticTestSelectionConfig();

        // then the config the stamp path would pass is enabled and carries the rule
        assertTrue(built.isEnabled());
        assertEquals(1, built.getRules().size());
        assertEquals("sql-run-all", built.getRules().get(0).getName());
    }
```

Add a `gradleRule(...)` helper mirroring the existing rule-construction tests if not present.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :tia-gradle:test --tests 'org.tiatesting.gradle.plugin.TiaBasePluginPublishStampHookTest'`
Expected: FAIL until the wiring passes the config (if the test asserts on stamper input) - or PASS trivially if it only asserts `buildStaticTestSelectionConfig()`; in that case the meaningful change is verified by Step 4's full build.

- [ ] **Step 3: Implement**

In `TiaBasePlugin.stampPublish()`, build the config and pass it:

```java
            StaticTestSelectionConfig staticConfig = buildStaticTestSelectionConfig();
            LibraryPublishStamper.PublishStampResult result = new LibraryPublishStamper()
                    .stampPublish(dataStore, vcsReader, groupArtifact, publishedVersion, jarFilePath, staticConfig);
```

In `AbstractPublishLibStampMojo`, likewise:

```java
            StaticTestSelectionConfig staticConfig = buildStaticTestSelectionConfig();
            LibraryPublishStamper.PublishStampResult result = new LibraryPublishStamper()
                    .stampPublish(dataStore, vcsReader, groupArtifact, publishedVersion, jarFilePath, staticConfig);
```

Add the `StaticTestSelectionConfig` import to both if missing. `buildStaticTestSelectionConfig()` already exists on both (`TiaBasePlugin:451`, `AbstractTiaMojo:391`); `AbstractPublishLibStampMojo` extends the mojo hierarchy that declares it - if it is not in scope there, call `buildStaticTestSelectionConfig()` from the nearest superclass that defines it (verify the class hierarchy).

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :tia-gradle:test :tia-maven-plugin:compileJava`
Expected: PASS / compiles.

- [ ] **Step 5: Commit**

```bash
git add tia-gradle/src/main/java/org/tiatesting/gradle/plugin/TiaBasePlugin.java \
        tia-maven-plugin/src/main/java/org/tiatesting/maven/AbstractPublishLibStampMojo.java \
        tia-gradle/src/test/java/org/tiatesting/gradle/plugin/TiaBasePluginPublishStampHookTest.java
git commit -m "feat(library): pass library static rules into the publish stamp task (Gradle + Maven)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 5: Drain forced selections at the consumer

**Files:**
- Modify: `tia-core/src/main/java/org/tiatesting/core/staticselection/StaticTestSelectionResolver.java` (extract reusable mode+patterns resolution)
- Modify: `tia-core/src/main/java/org/tiatesting/core/library/LibraryImpactDrainResult.java` (carry drained forced keys)
- Modify: `tia-core/src/main/java/org/tiatesting/core/library/PendingLibraryImpactedMethodsDrainer.java` (drain forced batches)
- Modify: `tia-core/src/main/java/org/tiatesting/core/diff/diffanalyze/selector/TestSelector.java` (pass `testSuitesTracked` into the drain)
- Test: `tia-core/src/test/java/org/tiatesting/core/library/PendingLibraryForcedSelectionDrainerTest.java` (new)
- Test: `tia-core/src/test/java/org/tiatesting/core/staticselection/StaticTestSelectionResolverTest.java` (add resolution-reuse test)

**Interfaces:**
- Consumes: `readAllPendingLibraryForcedSelections()` (Task 2), `PendingLibraryForcedSelection` (Task 1), existing `lookupLibraryPublish`, `getTestSuitesForMethods`, `testSuitesTracked` map.
- Produces:
  - `StaticTestSelectionResolver.resolveForcedSelection(StaticTestSelectionRuleMode mode, List<Pattern> suiteNamePatterns, Map<String, TestSuiteTracker> tracked)` returning `Set<String>`.
  - `LibraryImpactDrainResult.addDrainedForcedBatch(String, long)` / `getDrainedForcedBatchKeys()` returning `List<DrainedBatchKey>`.
  - `drainPendingMethods(DataStore, LibraryImpactAnalysisConfig, Map<String, TestSuiteTracker> testSuitesTracked)` - `testSuitesTracked` is a new required parameter.

- [ ] **Step 1: Write the failing tests**

Resolver reuse test (add to `StaticTestSelectionResolverTest`):

```java
    @Test
    public void resolveForcedSelectionRunAllReturnsAllTrackedSuites() {
        // given
        Map<String, TestSuiteTracker> tracked = new HashMap<>();
        tracked.put("com.acme.AaaTest", new TestSuiteTracker("com.acme.AaaTest"));
        tracked.put("com.acme.BbbIT", new TestSuiteTracker("com.acme.BbbIT"));
        StaticTestSelectionResolver resolver = new StaticTestSelectionResolver(StaticTestSelectionConfig.EMPTY);

        // when
        Set<String> forced = resolver.resolveForcedSelection(
                StaticTestSelectionRuleMode.RUN_ALL, Collections.<Pattern>emptyList(), tracked);

        // then
        assertEquals(new HashSet<>(Arrays.asList("com.acme.AaaTest", "com.acme.BbbIT")), forced);
    }

    @Test
    public void resolveForcedSelectionSuiteNamesMatchesSubset() {
        // given
        Map<String, TestSuiteTracker> tracked = new HashMap<>();
        tracked.put("com.acme.AaaTest", new TestSuiteTracker("com.acme.AaaTest"));
        tracked.put("com.acme.BbbIT", new TestSuiteTracker("com.acme.BbbIT"));
        StaticTestSelectionResolver resolver = new StaticTestSelectionResolver(StaticTestSelectionConfig.EMPTY);

        // when
        Set<String> forced = resolver.resolveForcedSelection(
                StaticTestSelectionRuleMode.SUITE_NAMES,
                Collections.singletonList(Pattern.compile(".*IT$")), tracked);

        // then
        assertEquals(Collections.singleton("com.acme.BbbIT"), forced);
    }
```

Drainer test (new file `PendingLibraryForcedSelectionDrainerTest`, mirroring the existing drainer test's resolve/ledger mocks):

```java
    @Test
    public void drainsForcedRunAllWhenResolvedBuildContainsIt() {
        // given a tracked library resolved to publish seq 3 and a forced RUN_ALL batch at seq 2
        // (reuse the existing drainer test's mock setup for resolveLibrariesInSourceProject + lookupLibraryPublish)
        when(dataStore.readAllPendingLibraryImpactedMethods()).thenReturn(Collections.emptyList());
        when(dataStore.readAllPendingLibraryForcedSelections()).thenReturn(singletonList(
                new PendingLibraryForcedSelection("com.acme:widget", "1.2.0", 2L, "sql-run-all",
                        StaticTestSelectionRuleMode.RUN_ALL, Collections.<String>emptyList())));
        // library resolves to seq 3 (>= 2), lastAppliedSeq null
        stubResolvedBuild("com.acme:widget", 3L);
        Map<String, TestSuiteTracker> tracked = new HashMap<>();
        tracked.put("com.acme.AaaTest", new TestSuiteTracker("com.acme.AaaTest"));
        tracked.put("com.acme.BbbIT", new TestSuiteTracker("com.acme.BbbIT"));

        // when
        PendingLibraryImpactedMethodsDrainer.DrainOutcome outcome =
                new PendingLibraryImpactedMethodsDrainer().drainPendingMethods(dataStore, libraryConfig, tracked);

        // then all tracked suites are selected and the forced batch is marked drained
        assertEquals(new HashSet<>(Arrays.asList("com.acme.AaaTest", "com.acme.BbbIT")),
                new HashSet<>(outcome.getTestsToAdd()));
        assertEquals(1, outcome.getDrainResult().getDrainedForcedBatchKeys().size());
    }

    @Test
    public void holdsForcedBatchAboveResolvedSeq() {
        // given a forced batch at seq 5 but the library resolves to seq 3
        when(dataStore.readAllPendingLibraryImpactedMethods()).thenReturn(Collections.emptyList());
        when(dataStore.readAllPendingLibraryForcedSelections()).thenReturn(singletonList(
                new PendingLibraryForcedSelection("com.acme:widget", "1.5.0", 5L, "sql-run-all",
                        StaticTestSelectionRuleMode.RUN_ALL, Collections.<String>emptyList())));
        stubResolvedBuild("com.acme:widget", 3L);
        Map<String, TestSuiteTracker> tracked = new HashMap<>();
        tracked.put("com.acme.AaaTest", new TestSuiteTracker("com.acme.AaaTest"));

        // when
        PendingLibraryImpactedMethodsDrainer.DrainOutcome outcome =
                new PendingLibraryImpactedMethodsDrainer().drainPendingMethods(dataStore, libraryConfig, tracked);

        // then nothing drains
        assertTrue(outcome.getTestsToAdd().isEmpty());
        assertTrue(outcome.getDrainResult().getDrainedForcedBatchKeys().isEmpty());
    }
```

`stubResolvedBuild(...)` mirrors the existing drainer test's helper that stubs `resolveLibrariesInSourceProject` + `lookupLibraryPublish` to return a build at a given seq for a tracked library. Copy that helper's body from the existing test.

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :tia-core:test --tests 'org.tiatesting.core.staticselection.StaticTestSelectionResolverTest' --tests 'org.tiatesting.core.library.PendingLibraryForcedSelectionDrainerTest'`
Expected: FAIL - `resolveForcedSelection`, `drainPendingMethods(...,tracked)`, and `getDrainedForcedBatchKeys` do not exist.

- [ ] **Step 3a: Extract reusable resolution in `StaticTestSelectionResolver`**

Add the public method and route the existing private `resolveRule` through it (DRY):

```java
    /**
     * Resolve a forced selection - a decided mode plus suite-name patterns - into the set of
     * tracked suite names to force-run, against the given tracked-suites snapshot. RUN_ALL returns
     * every tracked suite; SUITE_NAMES returns the suites whose simple name or FQN matches any
     * pattern. Shared by rule evaluation (this class) and library forced-selection drain.
     *
     * @param mode the selection mode.
     * @param suiteNamePatterns the compiled suite-name patterns; ignored for RUN_ALL.
     * @param tracked the tracked test suites keyed by suite name.
     * @return the forced suite names; never {@code null}, may be empty.
     */
    public Set<String> resolveForcedSelection(final StaticTestSelectionRuleMode mode,
                                              final List<Pattern> suiteNamePatterns,
                                              final Map<String, TestSuiteTracker> tracked) {
        switch (mode) {
            case RUN_ALL:
                return (tracked == null || tracked.isEmpty())
                        ? Collections.emptySet() : new HashSet<>(tracked.keySet());
            case SUITE_NAMES:
                return resolveSuiteNamePatterns(suiteNamePatterns, tracked);
            default:
                return Collections.emptySet();
        }
    }
```

Refactor `resolveSuiteNamesRule` to delegate its matching loop to a new private `resolveSuiteNamePatterns(List<Pattern>, Map<String, TestSuiteTracker>)` that contains the existing SuiteNameIndex matching logic, and have both `resolveRule` (SUITE_NAMES case) and `resolveForcedSelection` call it. Keep the existing lazy `SuiteNameIndex` cache behavior.

- [ ] **Step 3b: Extend `LibraryImpactDrainResult`**

Add a parallel list and accessors:

```java
    /** The {@code (groupArtifact, publishSeq)} keys of the forced-selection batches drained this run. */
    private final List<DrainedBatchKey> drainedForcedBatchKeys = new ArrayList<>();

    /**
     * Record a drained forced-selection batch for post-run deletion.
     *
     * @param groupArtifact the library the forced batch belongs to.
     * @param publishSeq the publish sequence of the drained forced batch.
     */
    public void addDrainedForcedBatch(String groupArtifact, long publishSeq) {
        drainedForcedBatchKeys.add(new DrainedBatchKey(groupArtifact, publishSeq));
    }

    /** @return the drained forced-selection batch keys for post-run deletion. */
    public List<DrainedBatchKey> getDrainedForcedBatchKeys() {
        return drainedForcedBatchKeys;
    }
```

Update `hasDrainedBatches()` to also consider forced keys: `return !drainedBatchKeys.isEmpty() || !drainedForcedBatchKeys.isEmpty();`

- [ ] **Step 3c: Drain forced batches in `PendingLibraryImpactedMethodsDrainer`**

Add `final Map<String, TestSuiteTracker> testSuitesTracked` as a new parameter to `drainPendingMethods`. After the existing method-batch drain loop, add a forced-selection drain that reuses the same resolved-build lookup and hold rules. Extract the resolved-build resolution (ledger lookup + hold checks that today live inline in `drainPendingMethodsForLibrary`) so both loops share it, then:

```java
        Map<String, List<PendingLibraryForcedSelection>> forcedByLibrary =
                groupForcedByLibrary(dataStore.readAllPendingLibraryForcedSelections());
        if (!forcedByLibrary.isEmpty()) {
            StaticTestSelectionResolver forcedResolver = new StaticTestSelectionResolver(StaticTestSelectionConfig.EMPTY);
            for (Map.Entry<String, List<PendingLibraryForcedSelection>> entry : forcedByLibrary.entrySet()) {
                TrackedLibrary library = trackedLibraries.get(entry.getKey());
                if (library == null) {
                    log.warn("Pending forced selections exist for '{}' but the library is not tracked - skipping.", entry.getKey());
                    continue;
                }
                drainForcedSelectionsForLibrary(dataStore, library, entry.getValue(), resolvedLibraries,
                        testSuitesTracked, forcedResolver, testsFromDrain, drainResult);
            }
        }
```

Add the per-library forced drain, mirroring `drainPendingMethodsForLibrary`'s gate:

```java
    /**
     * Drain one library's pending forced-selection batches against the build the source project
     * resolved: apply the identical resolved-build lookup and hold rules as the method drain, then
     * for each forced batch at or below the resolved sequence, resolve the forced suites against the
     * consumer's current tracked suites and union them into the run set.
     *
     * @param dataStore the persistence layer for the ledger lookup.
     * @param library the tracked library whose forced batches are evaluated.
     * @param forcedBatches the library's pending forced-selection batches.
     * @param resolvedLibraries the libraries resolved on the source project classpath, by coordinate.
     * @param testSuitesTracked the consumer's tracked suites, used to resolve RUN_ALL / SUITE_NAMES.
     * @param resolver the shared static resolver used for forced resolution.
     * @param testsFromDrain accumulator for the selected test suites.
     * @param drainResult accumulator for the drained forced-batch keys and applied sequences.
     */
    private void drainForcedSelectionsForLibrary(DataStore dataStore, TrackedLibrary library,
                                                 List<PendingLibraryForcedSelection> forcedBatches,
                                                 Map<String, ResolvedSourceProjectLibrary> resolvedLibraries,
                                                 Map<String, TestSuiteTracker> testSuitesTracked,
                                                 StaticTestSelectionResolver resolver,
                                                 Set<String> testsFromDrain,
                                                 LibraryImpactDrainResult drainResult) {
        String groupArtifact = library.getGroupArtifact();
        Long resolvedSeq = resolveBuildSeqOrHold(dataStore, library, resolvedLibraries, forcedBatches.size());
        if (resolvedSeq == null) {
            return;
        }
        boolean anyDrained = false;
        for (PendingLibraryForcedSelection batch : forcedBatches) {
            if (batch.getPublishSeq() <= resolvedSeq) {
                List<java.util.regex.Pattern> patterns = new ArrayList<>();
                for (String p : batch.getSuiteNamePatterns()) {
                    patterns.add(java.util.regex.Pattern.compile(p));
                }
                Set<String> forcedSuites = resolver.resolveForcedSelection(batch.getMode(), patterns, testSuitesTracked);
                testsFromDrain.addAll(forcedSuites);
                drainResult.addDrainedForcedBatch(groupArtifact, batch.getPublishSeq());
                anyDrained = true;
                log.info("Drained forced selection for library '{}' at seq {} (rule '{}', mode {}) - {} tests selected.",
                        groupArtifact, batch.getPublishSeq(), batch.getRuleName(), batch.getMode(), forcedSuites.size());
            } else {
                log.debug("Forced selection for library '{}' at seq {} is above resolved seq {} - held.",
                        groupArtifact, batch.getPublishSeq(), resolvedSeq);
            }
        }
        if (anyDrained) {
            drainResult.setAppliedSeq(groupArtifact, resolvedSeq);
        }
    }

    /**
     * Group pending forced-selection batches by their owning library coordinate, preserving order.
     *
     * @param forced all pending forced-selection batches.
     * @return map of {@code groupArtifact} to that library's forced batches.
     */
    private Map<String, List<PendingLibraryForcedSelection>> groupForcedByLibrary(List<PendingLibraryForcedSelection> forced) {
        Map<String, List<PendingLibraryForcedSelection>> byLibrary = new LinkedHashMap<>();
        for (PendingLibraryForcedSelection batch : forced) {
            byLibrary.computeIfAbsent(batch.getGroupArtifact(), k -> new ArrayList<>()).add(batch);
        }
        return byLibrary;
    }
```

Refactor the method drain's inline resolved-build lookup + hold checks into a shared helper `Long resolveBuildSeqOrHold(DataStore, TrackedLibrary, Map<String,ResolvedSourceProjectLibrary>, int pendingCount)` that returns the resolved seq or `null` when any hold rule fires (unresolvable, unknown build, downgrade), logging the same warnings as today. Call it from both `drainPendingMethodsForLibrary` and `drainForcedSelectionsForLibrary` so the hold logic is not duplicated. Add imports: `PendingLibraryForcedSelection`, `StaticTestSelectionResolver`, `StaticTestSelectionConfig`, `TestSuiteTracker`.

- [ ] **Step 3d: Pass `testSuitesTracked` from `TestSelector`**

In `TestSelector.drainPendingLibraryMethodsIfConfigured`, add a `Map<String, TestSuiteTracker> testSuitesTracked` parameter and forward it to `drainer.drainPendingMethods(dataStore, libraryConfig, testSuitesTracked)`. Update the call at `selectTestsToIgnore` (line ~105) to pass the `testSuitesTracked` already loaded at line ~100.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :tia-core:test --tests 'org.tiatesting.core.staticselection.StaticTestSelectionResolverTest' --tests 'org.tiatesting.core.library.PendingLibraryForcedSelectionDrainerTest' --tests 'org.tiatesting.core.library.*Drainer*'`
Expected: PASS. Update the existing drainer test's `drainPendingMethods(...)` calls to pass a tracked-suites map (empty map is fine for method-only tests).

- [ ] **Step 5: Commit**

```bash
git add tia-core/src/main/java/org/tiatesting/core/staticselection/StaticTestSelectionResolver.java \
        tia-core/src/main/java/org/tiatesting/core/library/LibraryImpactDrainResult.java \
        tia-core/src/main/java/org/tiatesting/core/library/PendingLibraryImpactedMethodsDrainer.java \
        tia-core/src/main/java/org/tiatesting/core/diff/diffanalyze/selector/TestSelector.java \
        tia-core/src/test/java/org/tiatesting/core/staticselection/StaticTestSelectionResolverTest.java \
        tia-core/src/test/java/org/tiatesting/core/library/PendingLibraryForcedSelectionDrainerTest.java \
        tia-core/src/test/java/org/tiatesting/core/library/PendingLibraryImpactedMethodsDrainerTest.java
git commit -m "feat(library): drain forced selections and union into consumer test selection

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 6: Delete drained forced rows after the test run

**Files:**
- Modify: `tia-core/src/main/java/org/tiatesting/core/testrunner/TestRunnerService.java` (`deleteDrainedPendingBatches`, ~line 353)
- Test: `tia-core/src/test/java/org/tiatesting/core/testrunner/TestRunnerServiceTest.java` (or the existing test that covers `applyLibraryImpactDrainResult`)

**Interfaces:**
- Consumes: `LibraryImpactDrainResult.getDrainedForcedBatchKeys()` (Task 5), `dataStore.deletePendingLibraryForcedSelections(String, long)` (Task 2).
- Produces: no new API.

- [ ] **Step 1: Write the failing test**

```java
    @Test
    public void applyDrainResultDeletesForcedBatches() {
        // given a drain result with one method batch and one forced batch
        LibraryImpactDrainResult drainResult = new LibraryImpactDrainResult();
        drainResult.addDrainedBatch("com.acme:widget", 2L);
        drainResult.addDrainedForcedBatch("com.acme:widget", 2L);
        drainResult.setAppliedSeq("com.acme:widget", 2L);

        // when the post-run apply runs (invoke via the same path the existing test uses)
        invokeApplyLibraryImpactDrainResult(drainResult, "commitZ");

        // then both the method and forced pending rows are deleted
        verify(dataStore).deletePendingLibraryImpactedMethods("com.acme:widget", 2L);
        verify(dataStore).deletePendingLibraryForcedSelections("com.acme:widget", 2L);
    }
```

Use whatever entry point the existing `TestRunnerServiceTest` uses to reach `applyLibraryImpactDrainResult` (it is private; the existing test likely drives it through `persistTestRunData` / a public method - match that).

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :tia-core:test --tests 'org.tiatesting.core.testrunner.TestRunnerServiceTest'`
Expected: FAIL - forced rows are not deleted.

- [ ] **Step 3: Implement**

In `deleteDrainedPendingBatches`, after the existing method-batch delete loop, add:

```java
        for (LibraryImpactDrainResult.DrainedBatchKey key : drainResult.getDrainedForcedBatchKeys()) {
            log.info("Deleting drained forced-selection batch: {}", key);
            dataStore.deletePendingLibraryForcedSelections(key.getGroupArtifact(), key.getPublishSeq());
        }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :tia-core:test --tests 'org.tiatesting.core.testrunner.TestRunnerServiceTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add tia-core/src/main/java/org/tiatesting/core/testrunner/TestRunnerService.java \
        tia-core/src/test/java/org/tiatesting/core/testrunner/TestRunnerServiceTest.java
git commit -m "feat(library): delete drained forced-selection rows after the test run

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 7: Surface pending forced selections in reports

**Files:**
- Modify: `tia-core/src/main/java/org/tiatesting/core/report/LibraryPendingMethodsReportGenerator.java`
- Modify: `tia-core/src/main/java/org/tiatesting/core/report/html/HtmlLibraryReport.java`
- Modify: `tia-gradle/src/main/java/org/tiatesting/gradle/plugin/TiaLibraryPendingMethodsTask.java`
- Modify: `tia-maven-plugin/src/main/java/org/tiatesting/maven/AbstractLibraryPendingMethodsMojo.java`
- Test: `tia-core/src/test/java/org/tiatesting/core/report/LibraryPendingMethodsReportGeneratorTest.java` (or add to the existing report test)

**Interfaces:**
- Consumes: `dataStore.readAllPendingLibraryForcedSelections()` / `readPendingLibraryForcedSelections(String)` (Task 2).
- Produces: report/task output that lists forced batches (library, publish seq, stamp version, rule name, mode, patterns) alongside pending method batches.

- [ ] **Step 1: Write the failing test**

Add a test asserting the text/console report generator includes a forced-selection line when the store has one:

```java
    @Test
    public void reportIncludesPendingForcedSelections() {
        // given
        when(dataStore.readAllPendingLibraryImpactedMethods()).thenReturn(Collections.emptyList());
        when(dataStore.readAllPendingLibraryForcedSelections()).thenReturn(singletonList(
                new PendingLibraryForcedSelection("com.acme:widget", "1.2.0", 2L, "sql-run-all",
                        StaticTestSelectionRuleMode.RUN_ALL, Collections.<String>emptyList())));

        // when
        String report = new LibraryPendingMethodsReportGenerator(dataStore).generate();

        // then
        assertTrue(report.contains("com.acme:widget"));
        assertTrue(report.contains("sql-run-all"));
        assertTrue(report.contains("RUN_ALL"));
    }
```

Match the generator's real constructor and entry-method names (inspect the file; if it takes a `TiaData` rather than a `DataStore`, adapt the test and read forced selections from the same source the generator already uses for pending methods).

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :tia-core:test --tests 'org.tiatesting.core.report.LibraryPendingMethodsReportGeneratorTest'`
Expected: FAIL - forced selections are not in the report.

- [ ] **Step 3: Implement**

In `LibraryPendingMethodsReportGenerator`, read forced selections from the same data source used for pending methods and render a section per library listing each forced batch's seq, stamp version, rule name, mode, and patterns. In `HtmlLibraryReport`, add a "Pending forced selections" table mirroring the existing pending-methods table markup. In `TiaLibraryPendingMethodsTask` and `AbstractLibraryPendingMethodsMojo`, include the forced batches in the console/log output the task already prints (they delegate to the generator - if so, no change beyond the generator; verify and only touch them if they format output directly).

Keep drain-time application log-only (no report change there) - this task is pending-state visibility only.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :tia-core:test --tests 'org.tiatesting.core.report.LibraryPendingMethodsReportGeneratorTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add tia-core/src/main/java/org/tiatesting/core/report/LibraryPendingMethodsReportGenerator.java \
        tia-core/src/main/java/org/tiatesting/core/report/html/HtmlLibraryReport.java \
        tia-gradle/src/main/java/org/tiatesting/gradle/plugin/TiaLibraryPendingMethodsTask.java \
        tia-maven-plugin/src/main/java/org/tiatesting/maven/AbstractLibraryPendingMethodsMojo.java \
        tia-core/src/test/java/org/tiatesting/core/report/LibraryPendingMethodsReportGeneratorTest.java
git commit -m "feat(library): surface pending forced selections in reports and pending-methods task

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 8: Document in WIKI and verify full build

**Files:**
- Modify: `WIKI.md` (library publish-time stamping chapter)

- [ ] **Step 1: Update the WIKI chapter**

In the library publish-time stamping chapter, add a section "Library-declared forced selection" describing: a library's own `tiaStaticTestSelectionRules` are evaluated at publish time against files changed since the previous publish; a match records a forced-selection batch (mode + suite-name patterns) keyed to the publish sequence; the consumer's drain gates it on the resolved build sequence like method stamps and resolves RUN_ALL to all consumer suites / SUITE_NAMES to the matching subset; forced and method batches at the same sequence both apply; non-code files (e.g. SQL) are supported because the evaluation uses the unfiltered changed-paths diff. Note the `tia_pending_library_forced_selection` table. ASCII hyphens only.

- [ ] **Step 2: Full verification build**

Run: `./gradlew :tia-core:test :tia-gradle:test :tia-maven-plugin:test :tia-vcs-git:test`
Expected: PASS across modules.

- [ ] **Step 3: Commit**

```bash
git add WIKI.md
git commit -m "docs(wiki): document library-declared forced selection via publish stamping

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Self-Review

**Spec coverage:**
- Data model (spec 1) -> Task 1.
- Sibling table + both stores + atomic persist (spec 1) -> Task 2.
- Stamp-time evaluation, since-previous-publish dedup, SEEDED forces nothing, non-code via unfiltered paths (spec 2) -> Task 3.
- Config wiring Gradle + Maven (spec 4) -> Task 4.
- Drain gate + RUN_ALL/SUITE_NAMES resolution against consumer suites + union (spec 3) -> Task 5.
- Post-run cleanup, shared seq advance (spec 3) -> Task 6.
- Reporting: HTML + pending-methods task, drain log-only (spec 5) -> Task 7.
- WIKI + full build (spec 4 doc note) -> Task 8.
All spec sections map to a task.

**Placeholder scan:** No "TBD"/"implement later". Two implementer notes (path-scoping form in Task 3; report generator's real data source in Task 7) direct verification against the actual code and give both concrete branches to pick from - not deferred work.

**Type consistency:** `persistLibraryPublish(LibraryPublish, Set<Integer>, List<PendingLibraryForcedSelection>)` used consistently (Tasks 2, 3). `resolveForcedSelection(StaticTestSelectionRuleMode, List<Pattern>, Map<String,TestSuiteTracker>)` defined Task 5, used Task 5. `PendingLibraryForcedSelection` getters match across Tasks 1, 2, 3, 5, 7. `getDrainedForcedBatchKeys()` / `addDrainedForcedBatch()` defined Task 5, used Tasks 5, 6. `drainPendingMethods(DataStore, LibraryImpactAnalysisConfig, Map<String,TestSuiteTracker>)` defined Task 5, callers updated Task 5.

## Known verification points for the implementer
- Confirm `TrackedLibrary` source-dir storage form (absolute vs repo-relative) before finalizing Task 3's `restrictPathsToLibraryDirs`.
- Confirm `DatastoreEquivalenceTest`'s store-construction idiom (`newStore()` vs parameterized field) before writing Task 2's test.
- Confirm `LibraryPendingMethodsReportGenerator`'s constructor/data source before writing Task 7's test.
