package org.tiatesting.core.persistence;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.tiatesting.core.diff.ChangeType;
import org.tiatesting.core.diff.SourceFileDiffContext;
import org.tiatesting.core.diff.diffanalyze.selector.TestSelector;
import org.tiatesting.core.diff.diffanalyze.selector.TestSelectorResult;
import org.tiatesting.core.model.ClassImpactTracker;
import org.tiatesting.core.model.MethodImpactTracker;
import org.tiatesting.core.model.TestSuiteTracker;
import org.tiatesting.core.model.TiaData;
import org.tiatesting.core.persistence.dialect.PostgresDialect;
import org.tiatesting.core.vcs.VCSReader;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Proves that {@link DataStoreFactory}-built H2 and Postgres {@link JdbcDataStore}s select the
 * same test suites to ignore for the same seeded mapping and the same VCS diff - the production
 * dialect-persist-and-read path validated end to end, not just the connection-building path
 * {@link DataStoreFactoryPostgresTest} covers. See the pluggable-datastore WIKI chapter.
 *
 * <p>Seeds a small, deterministic mapping directly through the {@link DataStore} persist API
 * (not {@code GenerateLargeTiaDb}, whose Postgres-seeding path does not exist on this branch)
 * into a fresh temp-directory H2 database and into the local Postgres instance, then runs
 * {@link TestSelector#selectTestsToIgnore} against each with an identical synthetic diff and
 * asserts the two ignore sets are equal and non-empty.
 *
 * <p>Guarded via a JUnit assumption exactly like {@link DataStoreFactoryPostgresTest}: skipped
 * (not failed) when no Postgres is reachable on {@code localhost:5432}, so the normal build stays
 * green without the {@code spike/postgres/} harness running.
 */
class DatastoreEquivalenceTest {

    private static final String POSTGRES_URL = "jdbc:postgresql://localhost:5432/tiaperf";
    private static final String POSTGRES_USER = "tia";
    private static final String POSTGRES_PASSWORD = "tia";
    private static final String BRANCH = "main";

    private static final String FOO_FILE = "com/example/pgh2equiv/Foo.java";
    private static final String BAR_FILE = "com/example/pgh2equiv/Bar.java";
    private static final String BAZ_FILE = "com/example/pgh2equiv/Baz.java";

    private DataStore h2Store;
    private DataStore postgresStore;
    private Path h2TempDir;

    /**
     * Close both datastores opened by the test, releasing the H2 file lock and the Postgres
     * connection. The temp H2 directory is left in place (matches the other H2 datastore tests'
     * cleanup, which is best-effort and not load-bearing for correctness).
     */
    @AfterEach
    void tearDown() {
        if (h2Store != null) {
            h2Store.close();
        }
        if (postgresStore != null) {
            postgresStore.close();
        }
    }

    /**
     * Skip the test (rather than fail it) when the local Postgres instance is not reachable, via
     * a quick raw TCP connect with a short timeout. Mirrors {@link DataStoreFactoryPostgresTest}'s
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
     * Drop the Tia tables so the next seed recreates the schema from the current DDL. Two reasons
     * this must DROP rather than merely truncate:
     * <ul>
     *   <li>The Postgres instance is a persistent, shared scratch database (unlike the fresh
     *       temp-directory H2 database each run gets); rows left by a previous run or by unrelated
     *       spike seeding would otherwise appear as extra tracked suites on the Postgres side only
     *       and break the equivalence assertion for reasons unrelated to the dialect path.</li>
     *   <li>{@code JdbcDataStore.ensureSchema} only (re)runs the {@code CREATE TABLE} DDL when
     *       {@code tia_core} is absent, so a stale {@code tia_test_suite} created before the
     *       {@code UNIQUE(name)} index fix would survive a truncate and the suite upsert's
     *       {@code ON CONFLICT (name)} would still fail. Dropping the table forces the corrected
     *       DDL (with the name unique index) to be applied on the next connection.</li>
     * </ul>
     *
     * @throws SQLException if the cleanup connection or the drop statement fails
     */
    private static void cleanPostgres() throws SQLException {
        try (Connection connection = DriverManager.getConnection(POSTGRES_URL, POSTGRES_USER, POSTGRES_PASSWORD);
             Statement statement = connection.createStatement()) {
            // the store's tables now live in the per-branch schema (see DataStoreFactory), not the
            // default "public" schema a raw connection starts in - select it before dropping.
            statement.execute(new PostgresDialect().selectSchemaSql(BranchSchema.schemaName(BRANCH)));
            statement.executeUpdate("DROP TABLE IF EXISTS tia_source_class_method, tia_source_class, "
                    + "tia_test_suite, tia_test_suites_failed, tia_source_method, tia_core, "
                    + "tia_pending_library_impacted_method, tia_library_publish, tia_library, "
                    + "tia_test_run_history CASCADE");
        }
    }

    /**
     * Build the fixed, deterministic method map shared by both backends: two methods in
     * {@link #FOO_FILE} (the file the synthetic diff touches) and one each in {@link #BAR_FILE}
     * and {@link #BAZ_FILE} (files the diff leaves untouched, so their covering suites are
     * expected to land in the ignore set on both backends).
     *
     * @return the method id to tracker map, ready for {@link DataStore#persistSourceMethods}
     */
    private static Map<Integer, MethodImpactTracker> buildMethods() {
        Map<Integer, MethodImpactTracker> methods = new HashMap<>();
        methods.put(101, new MethodImpactTracker("com/example/pgh2equiv/Foo.methodA.()V", 10, 20));
        methods.put(102, new MethodImpactTracker("com/example/pgh2equiv/Foo.methodB.()V", 30, 40));
        methods.put(103, new MethodImpactTracker("com/example/pgh2equiv/Bar.methodC.()V", 5, 15));
        methods.put(104, new MethodImpactTracker("com/example/pgh2equiv/Baz.methodD.()V", 1, 10));
        return methods;
    }

    /**
     * Build the fixed, deterministic suite map shared by both backends: {@code SuiteFoo} covers
     * the two {@link #FOO_FILE} methods (the diff-impacted file, so it is expected in the run set
     * on both backends), while {@code SuiteBar} and {@code SuiteBaz} each cover a file the diff
     * leaves untouched (so both are expected in the ignore set on both backends).
     *
     * @return the suite name to tracker map, ready for {@link DataStore#persistTestSuites}
     */
    private static Map<String, TestSuiteTracker> buildSuites() {
        Map<String, TestSuiteTracker> suites = new HashMap<>();
        suites.put("SuiteFoo", buildSuite("SuiteFoo",
                new ClassImpactTracker(FOO_FILE, Arrays.asList(101, 102))));
        suites.put("SuiteBar", buildSuite("SuiteBar",
                new ClassImpactTracker(BAR_FILE, Collections.singletonList(103))));
        suites.put("SuiteBaz", buildSuite("SuiteBaz",
                new ClassImpactTracker(BAZ_FILE, Collections.singletonList(104))));
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
     * Seed the same deterministic mapping (core row, tracked methods, tracked suites) into the
     * given store via the public {@link DataStore} persist API - the path exercised by every real
     * Tia run, and (for a {@code jdbc:postgresql} store) the first end-to-end persist-and-read
     * proof against a real Postgres.
     *
     * @param store the datastore to seed
     */
    private static void seed(DataStore store) {
        // First contact bootstraps the schema (same pattern as the other H2/Postgres datastore
        // tests - persistCoreData's UPDATE-vs-INSERT probe otherwise queries a table that does
        // not exist yet on a brand new database).
        store.getTiaData(true);

        TiaData core = new TiaData();
        core.setCommitValue("equivalence-baseline");
        core.setBranch(BRANCH);
        core.setLastUpdated(Instant.now());
        store.persistCoreData(core);
        store.persistSourceMethods(buildMethods());
        store.persistTestSuites(buildSuites());
    }

    /**
     * Run {@code TestSelector.selectTestsToIgnore} against the given store using the shared
     * {@link SyntheticFooDiffVCSReader}, which simulates a diff touching only {@link #FOO_FILE}.
     *
     * @param store the datastore to select against
     * @return the selector's result, including the computed ignore set
     */
    private static TestSelectorResult runSelect(DataStore store) {
        TestSelector selector = new TestSelector(store);
        VCSReader stubVcs = new SyntheticFooDiffVCSReader();
        return selector.selectTestsToIgnore(stubVcs, Collections.emptyList(), Collections.emptyList(),
                false, null, null, false);
    }

    /**
     * Seeds the same deterministic mapping into a fresh temp-directory H2 database and into the
     * local Postgres instance via {@link DataStoreFactory#fromConfig}, runs the selector against
     * each with an identical synthetic diff, and asserts the two ignore sets are equal and
     * non-empty - i.e. the Postgres dialect's persist-and-read path selects exactly the same
     * suites as H2 for the same data and the same diff.
     *
     * @throws Exception if seeding the H2 temp database or cleaning Postgres fails
     */
    @Test
    void sameIgnoreSetOnBothBackends() throws Exception {
        // given - the same deterministic mapping seeded into a fresh H2 temp DB and into a
        //         freshly-cleaned Postgres, via the production DataStoreFactory path
        assumePg();
        cleanPostgres();

        h2TempDir = Files.createTempDirectory("tia-h2-postgres-equivalence");
        h2Store = DataStoreFactory.fromConfig(h2TempDir.toString(), null, "tia", "", null, BRANCH);
        postgresStore = DataStoreFactory.fromConfig(null, POSTGRES_URL, POSTGRES_USER, POSTGRES_PASSWORD,
                null, BRANCH);
        seed(h2Store);
        seed(postgresStore);

        // when - the selector runs against each store with the same stub VCS reader
        Set<String> h2Ignore = runSelect(h2Store).getTestsToIgnore();
        Set<String> postgresIgnore = runSelect(postgresStore).getTestsToIgnore();

        // then - both backends ignore exactly the same suites, and the set is non-trivial (the
        // synthetic diff actually impacted SuiteFoo's coverage on both sides, leaving the other
        // two tracked suites in the ignore set)
        assertEquals(h2Ignore, postgresIgnore);
        assertFalse(h2Ignore.isEmpty(), "expected the synthetic diff to leave at least one suite ignored");
        assertEquals(new HashSet<>(Arrays.asList("SuiteBar", "SuiteBaz")), h2Ignore);
    }

    /**
     * Minimal {@link VCSReader} stub that simulates a diff touching only {@link #FOO_FILE},
     * modelled on the perf harness's {@code ProfileSelectTests.SyntheticDiffVCSReader} (that
     * class is private to the {@code org.tiatesting.core.perf} package, so it is duplicated here
     * rather than reused). Every line of the file is changed, producing one hunk spanning the
     * whole file so it intersects both of {@link #FOO_FILE}'s tracked method line ranges.
     */
    private static final class SyntheticFooDiffVCSReader implements VCSReader {

        @Override
        public String getBranchName() {
            return BRANCH;
        }

        @Override
        public String getHeadCommit() {
            return "synthetic-head";
        }

        /**
         * Build a single modified-source diff context for {@link #FOO_FILE} with synthetic
         * before/after content covering every stored line range for that file.
         *
         * @param baseChangeNum ignored (no real VCS)
         * @param sourceFilesDirs ignored (paths are already mapping-key shaped)
         * @param testFilesDirs ignored
         * @param checkLocalChanges ignored
         * @return a single-element set containing the {@link #FOO_FILE} diff context
         */
        @Override
        public Set<SourceFileDiffContext> getDiffFiles(String baseChangeNum, List<String> sourceFilesDirs,
                                                        List<String> testFilesDirs, boolean checkLocalChanges) {
            StringBuilder original = new StringBuilder();
            StringBuilder changed = new StringBuilder();
            for (int line = 1; line <= 50; line++) {
                original.append("int value").append(line).append(" = ").append(line).append(";\n");
                changed.append("int value").append(line).append(" = ").append(line + 1).append(";\n");
            }

            // Leading slash: the selector normalizes diff paths by stripping the (here, empty)
            // source dirs then dropping the leading separator, yielding the stored mapping key.
            String path = "/" + FOO_FILE;
            SourceFileDiffContext diff = new SourceFileDiffContext(path, path, ChangeType.MODIFY);
            diff.setSourceContentOriginal(original.toString());
            diff.setSourceContentNew(changed.toString());

            Set<SourceFileDiffContext> diffs = new HashSet<>();
            diffs.add(diff);
            return diffs;
        }

        @Override
        public void loadContentForDiffs(Collection<SourceFileDiffContext> diffs, String baseChangeNum,
                                        boolean checkLocalChanges) {
            // no-op: synthetic content is baked into the contexts by getDiffFiles
        }

        @Override
        public Set<String> getChangedFilePaths(String baseChangeNum, boolean checkLocalChanges) {
            return new HashSet<>();
        }

        @Override
        public void close() {
        }
    }
}
