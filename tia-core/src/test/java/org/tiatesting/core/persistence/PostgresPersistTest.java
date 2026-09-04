package org.tiatesting.core.persistence;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.tiatesting.core.model.ClassImpactTracker;
import org.tiatesting.core.model.PendingLibraryImpactedMethod;
import org.tiatesting.core.model.RunOrigin;
import org.tiatesting.core.model.TestRunHistoryEntry;
import org.tiatesting.core.model.TestSuiteTracker;
import org.tiatesting.core.model.TrackedLibrary;
import org.tiatesting.core.persistence.dialect.PostgresDialect;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Execution-coverage test for the three Postgres {@code ON CONFLICT} upserts that
 * {@link DatastoreEquivalenceTest} does not exercise - it seeds once (a pure insert) and only
 * round-trips the {@code tia_test_suite} upsert - plus the {@code tia_test_suite} DO-UPDATE path
 * on a second persist of the same suites. Runs the persist-then-read round trip for
 * {@code persistTrackedLibrary} (twice, to force the ON CONFLICT DO UPDATE branch),
 * {@code persistTestRunHistoryEntry} and {@code persistPendingLibraryImpactedMethods} against a
 * real Postgres, so a missing unique constraint on any of their conflict targets - the class of
 * bug already found and fixed for {@code tia_test_suite} - fails loudly here instead of surfacing
 * only in production. See the pluggable-datastore WIKI chapter.
 *
 * <p>Guarded exactly like {@link DatastoreEquivalenceTest}: skipped (not failed) when no Postgres
 * is reachable on {@code localhost:5432}, so the normal build stays green without the
 * {@code spike/postgres/} harness running.
 */
class PostgresPersistTest {

    private static final String POSTGRES_URL = "jdbc:postgresql://localhost:5432/tiaperf";
    private static final String POSTGRES_USER = "tia";
    private static final String POSTGRES_PASSWORD = "tia";
    private static final String BRANCH = "main";
    private static final String LIB = "com.example:pgpersist";

    private DataStore postgresStore;

    /**
     * Close the Postgres datastore opened by the test, releasing its connection.
     */
    @AfterEach
    void tearDown() {
        if (postgresStore != null) {
            postgresStore.close();
        }
    }

    /**
     * Skip the test (rather than fail it) when the local Postgres instance is not reachable, via
     * a quick raw TCP connect with a short timeout. Mirrors {@link DatastoreEquivalenceTest}'s
     * guard so the normal build stays green on machines without the Postgres harness running.
     */
    private static void assumePg() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", 5432), 500);
        } catch (IOException e) {
            assumeTrue(false, "spike Postgres not running");
        }
    }

    /**
     * Drop the Tia tables so the test starts from a clean schema, forcing the current DDL
     * (including the unique constraints the {@code ON CONFLICT} upserts target) to be recreated
     * on the next connection. Mirrors {@link DatastoreEquivalenceTest#cleanPostgres()}.
     *
     * @throws SQLException if the cleanup connection or the drop statement fails
     */
    private static void cleanPostgres() throws SQLException {
        try (Connection connection = DriverManager.getConnection(POSTGRES_URL, POSTGRES_USER, POSTGRES_PASSWORD);
             Statement statement = connection.createStatement()) {
            // the store's tables now live in the per-branch schema (see DataStoreFactory), not the
            // default "public" schema a raw connection starts in - select it before dropping.
            statement.execute(new PostgresDialect().selectSchemaSql(BranchSchema.schemaName(BRANCH, null)));
            statement.executeUpdate("DROP TABLE IF EXISTS tia_source_class_method, tia_source_class, "
                    + "tia_test_suite, tia_test_suites_failed, tia_source_method, tia_core, "
                    + "tia_pending_library_impacted_method, tia_library_publish, tia_library, "
                    + "tia_test_run_history CASCADE");
        }
    }

    /**
     * Build a three-suite mapping, each suite covering one class, so a second
     * {@code persistTestSuites} of the same map exercises the {@code tia_test_suite} DO-UPDATE
     * branch (and the {@code getGeneratedKeys} follow-up) for every row rather than inserting them.
     *
     * @return the suite name to tracker map, ready for {@link DataStore#persistTestSuites}
     */
    private static Map<String, TestSuiteTracker> buildSuites() {
        Map<String, TestSuiteTracker> suites = new HashMap<>();
        suites.put("SuiteFoo", buildSuite("SuiteFoo",
                new ClassImpactTracker("com/example/pgpersist/Foo.java", Collections.singletonList(101))));
        suites.put("SuiteBar", buildSuite("SuiteBar",
                new ClassImpactTracker("com/example/pgpersist/Bar.java", Collections.singletonList(102))));
        suites.put("SuiteBaz", buildSuite("SuiteBaz",
                new ClassImpactTracker("com/example/pgpersist/Baz.java", Collections.singletonList(103))));
        return suites;
    }

    /**
     * Build a test-suite tracker with the given name and covered classes.
     *
     * @param name the suite name
     * @param classes the source classes (and their method ids) the suite covers
     * @return the populated tracker
     */
    private static TestSuiteTracker buildSuite(String name, ClassImpactTracker... classes) {
        TestSuiteTracker suite = new TestSuiteTracker(name);
        suite.setClassesImpacted(Arrays.asList(classes));
        return suite;
    }

    /**
     * Runs the persist-then-read round trip for every {@code ON CONFLICT} upsert in
     * {@link JdbcDataStore} against a real Postgres: {@code tia_library} (twice, to force the
     * DO-UPDATE branch), {@code tia_test_run_history}, {@code tia_pending_library_impacted_method},
     * and a second {@code tia_test_suite} persist of the same suites. Asserts each upsert both
     * completes without throwing and reads back the expected data, proving every conflict target
     * has the unique constraint Postgres's {@code ON CONFLICT} requires.
     *
     * @throws Exception if cleaning Postgres or building the store fails
     */
    @Test
    void allConflictTargetsPersistAndReadBackOnPostgres() throws Exception {
        // given a freshly-cleaned Postgres schema and a store built through the production factory
        assumePg();
        cleanPostgres();
        postgresStore = DataStoreFactory.fromConfig(null, POSTGRES_URL, POSTGRES_USER, POSTGRES_PASSWORD,
                null, BRANCH, null);
        postgresStore.getTiaData(true); // bootstrap the core schema on first contact

        // when a tracked library is persisted twice with a changed field, to hit the tia_library
        // ON CONFLICT (group_artifact) DO UPDATE branch on the second persist
        TrackedLibrary lib = new TrackedLibrary(LIB, "/projects/pgpersist", "src/main/java");
        postgresStore.persistTrackedLibrary(lib);
        lib.setMappingBaselineCommit("commit-1");
        postgresStore.persistTrackedLibrary(lib);

        // then the read-back reflects the updated value, proving the DO-UPDATE branch ran (an
        // INSERT-only path, or one against a missing/wrong conflict target, would either throw or
        // leave the original null value in place)
        Map<String, TrackedLibrary> libraries = postgresStore.readTrackedLibraries();
        assertEquals("commit-1", libraries.get(LIB).getMappingBaselineCommit());

        // when a test-run-history entry is persisted
        TestRunHistoryEntry entry = TestRunHistoryEntry.create(BRANCH, "abc123", 1_700_000_000_000L,
                10, 2, 1, 5_000L, true, 4_000L, 80, RunOrigin.unknown());
        postgresStore.persistTestRunHistoryEntry(entry);

        // then it round-trips through tia_test_run_history's ON CONFLICT (id) upsert
        List<TestRunHistoryEntry> history = postgresStore.readTestRunHistory();
        assertEquals(1, history.size());
        assertEquals(entry.getId(), history.get(0).getId());

        // when pending library impacted methods are persisted
        Set<Integer> methodIds = new HashSet<>(Arrays.asList(10, 20, 30));
        PendingLibraryImpactedMethod pending = new PendingLibraryImpactedMethod(LIB, "1.0.0", 1L, methodIds);
        postgresStore.persistPendingLibraryImpactedMethods(pending);

        // then it round-trips through tia_pending_library_impacted_method's per-method-id upsert
        List<PendingLibraryImpactedMethod> pendingResult = postgresStore.readPendingLibraryImpactedMethods(LIB);
        assertEquals(1, pendingResult.size());
        assertEquals(methodIds, pendingResult.get(0).getSourceMethodIds());

        // when the same suites are persisted a second time, to exercise the tia_test_suite
        // ON CONFLICT (name) DO UPDATE branch and its getGeneratedKeys follow-up on Postgres
        Map<String, TestSuiteTracker> suites = buildSuites();
        postgresStore.persistTestSuites(suites);
        postgresStore.persistTestSuites(suites);

        // then the second persist does not throw and the suite count is stable (an unstable count
        // would mean the DO-UPDATE branch silently inserted duplicates instead of updating)
        assertEquals(3, postgresStore.getTestSuitesTracked().size());
    }
}
