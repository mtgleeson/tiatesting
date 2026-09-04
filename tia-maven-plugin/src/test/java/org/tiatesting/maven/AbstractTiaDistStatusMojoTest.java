package org.tiatesting.maven;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tiatesting.core.diff.SourceFileDiffContext;
import org.tiatesting.core.model.DistributedRun;
import org.tiatesting.core.model.DistributedRunGroup;
import org.tiatesting.core.model.DistributedRunPlan;
import org.tiatesting.core.persistence.BranchSchema;
import org.tiatesting.core.persistence.DataStore;
import org.tiatesting.core.persistence.JdbcDataStore;
import org.tiatesting.core.persistence.connection.H2ConnectionProvider;
import org.tiatesting.core.persistence.dialect.H2Dialect;
import org.tiatesting.core.persistence.h2.H2ConnectionSettings;
import org.tiatesting.core.vcs.VCSReader;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cover {@link AbstractTiaDistStatusMojo}: the read-only goal that prints a distributed run's state.
 * The report's own content is covered by {@code DistributedRunStatusReportTest} in {@code tia-core},
 * which both build tools share; what is left for this class is the wiring only Maven has - that the
 * goal reads the branch's datastore, passes its two parameters through, and never fails a build.
 *
 * <p>Driven end to end through {@code execute()} against a real embedded-H2 {@link JdbcDataStore},
 * with only the VCS reader and the datastore construction substituted, the same shape {@code
 * AbstractTiaDistCompleteMojoDistributedTest} uses.
 */
class AbstractTiaDistStatusMojoTest {

    private static final String BRANCH = "main";

    @TempDir
    File tempDir;

    private File dbDir;

    /**
     * Create the embedded database's directory and bootstrap its schema, so every test starts from
     * an isolated store with no run planned.
     */
    @BeforeEach
    void setUp() {
        dbDir = new File(tempDir, "db");
        dbDir.mkdirs();
        try (DataStore dataStore = openStore()) {
            dataStore.getTiaData(true);
        }
    }

    /**
     * Verify the goal explains itself against a branch that has never planned a distributed run,
     * rather than failing. A pipeline that runs this goal unconditionally - including on a build
     * that never went distributed - must not have it be the thing that fails.
     */
    @Test
    void shouldReportThatNothingIsPlannedRatherThanFailing() {
        // given - no run planned

        // when
        String output = run(mojo());

        // then
        assertTrue(output.contains("No distributed run has been planned on this branch"), output);
    }

    /**
     * Verify the goal reports the run named by {@code -DtiaRunId}, the same parameter the plan and
     * agent goals key their own work by, so a pipeline needs no goal-specific property to point this
     * one at its build.
     */
    @Test
    void shouldReportTheRunNamedByTiaRunId() {
        // given - two planned runs, one of which is named by tiaRunId
        persistPlan("build-99");
        TestMojo mojo = mojo();
        mojo.tiaRunId = "build-99";

        // when
        String output = run(mojo);

        // then
        assertTrue(output.contains("Distributed run 'build-99'"), output);
        assertTrue(output.contains("Status:     OPEN - 0 of 2 group(s) completed"), output);
    }

    /**
     * Verify the goal falls back to the most recently planned run when no {@code -DtiaRunId} is
     * given, which is what a developer inspecting their own branch gets - each plan write clears the
     * previous run's rows, so there is normally only one to find.
     */
    @Test
    void shouldReportTheMostRecentlyPlannedRunWhenNoRunIdIsGiven() {
        // given - a planned run and a mojo with no tiaRunId set
        persistPlan("build-99");

        // when
        String output = run(mojo());

        // then
        assertTrue(output.contains("Distributed run 'build-99'"), output);
    }

    /**
     * Verify the assigned suite names are printed only when {@code -DtiaDistStatusSuites=true} is
     * set. The list is unbounded - a large project's plan can assign thousands of names to one group
     * - so it must not be the default even though the names are read either way.
     */
    @Test
    void shouldListAssignedSuiteNamesOnlyWhenTheSuitesParameterIsSet() {
        // given - a planned run whose groups carry suite names
        persistPlan("build-99");

        // when the goal runs with the parameter unset, then set
        String withoutParameter = run(mojo());
        TestMojo withSuites = mojo();
        withSuites.tiaDistStatusSuites = true;
        String withParameter = run(withSuites);

        // then
        assertFalse(withoutParameter.contains("com.example.ATest"), withoutParameter);
        assertTrue(withParameter.contains("Assigned suites:"), withParameter);
        assertTrue(withParameter.contains("com.example.ATest"), withParameter);
    }

    /**
     * Open an embedded-H2 datastore over this test's temp database directory, on the fixed
     * {@link #BRANCH}'s schema - the same construction the mojo's own datastore build performs, so
     * the test and the mojo read the same rows.
     *
     * @return an open datastore the caller must close
     */
    private JdbcDataStore openStore() {
        return new JdbcDataStore(new H2Dialect(),
                new H2ConnectionProvider(H2ConnectionSettings.embedded(dbDir.getAbsolutePath())),
                BranchSchema.schemaName(BRANCH, null));
    }

    /**
     * Persist a two-group run plan into the store the mojo will read, so the report has real rows to
     * describe rather than a stub's answers.
     *
     * @param runId the run identifier to plan under
     */
    private void persistPlan(final String runId) {
        Map<Integer, List<String>> suitesByGroup = new LinkedHashMap<>();
        suitesByGroup.put(0, Arrays.asList("com.example.ATest", "com.example.BTest"));
        suitesByGroup.put(1, Collections.singletonList("com.example.CTest"));
        List<DistributedRunGroup> groups = new ArrayList<>();
        for (int groupNumber = 0; groupNumber < suitesByGroup.size(); groupNumber++) {
            groups.add(DistributedRunGroup.pending(runId, groupNumber, 1000L));
        }
        DistributedRun run = DistributedRun.open(runId, BRANCH, "commit-1", groups.size(), null, 2000L,
                System.currentTimeMillis(), false);
        try (DataStore dataStore = openStore()) {
            dataStore.persistDistributedRunPlan(new DistributedRunPlan(run, groups, suitesByGroup, null));
        }
    }

    /**
     * Build a mojo wired to this test's embedded datastore, with Tia enabled and no run id set.
     *
     * @return a mojo ready to execute
     */
    private TestMojo mojo() {
        TestMojo mojo = new TestMojo();
        mojo.tiaEnabled = true;
        return mojo;
    }

    /**
     * Execute the goal with stdout captured, since the goal's whole output is what it prints.
     *
     * @param mojo the mojo to execute
     * @return everything the goal wrote to stdout
     */
    private static String run(final TestMojo mojo) {
        PrintStream originalOut = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(captured));
            mojo.execute();
        } finally {
            System.setOut(originalOut);
        }
        return captured.toString();
    }

    /**
     * The goal under test, with its VCS reader and datastore construction pointed at this test's
     * fixtures.
     */
    private final class TestMojo extends AbstractTiaDistStatusMojo {

        /**
         * @return a stub VCS reader reporting this test's fixed branch
         */
        @Override
        public VCSReader getVCSReader() {
            return new StubVCSReader();
        }

        /**
         * Build the datastore over this test's temp directory, so the goal reads the rows the test
         * wrote.
         *
         * @param branch the VCS branch name whose schema the store selects
         * @return an embedded datastore the mojo owns and closes
         */
        @Override
        protected DataStore buildDataStore(final String branch) {
            return openStore();
        }
    }

    /** Fixed-value VCS reader, so the goal resolves a branch without needing a repo on disk. */
    private static final class StubVCSReader implements VCSReader {

        /**
         * @return the fixed branch name these tests plan against
         */
        @Override public String getBranchName() { return BRANCH; }

        /**
         * @return the fixed head commit {@code "commit-1"}
         */
        @Override public String getHeadCommit() { return "commit-1"; }

        /**
         * Report no diffs at all; this goal performs no test selection, so nothing consumes them.
         *
         * @param baseChangeNum unused by this stub
         * @param sourceFilesDirs unused by this stub
         * @param testFilesDirs unused by this stub
         * @param checkLocalChanges unused by this stub
         * @return an empty set
         */
        @Override
        public Set<SourceFileDiffContext> getDiffFiles(String baseChangeNum, List<String> sourceFilesDirs,
                                                        List<String> testFilesDirs, boolean checkLocalChanges) {
            return Collections.emptySet();
        }

        /**
         * No-op: this stub returns no diffs from {@link #getDiffFiles}, so there is never anything
         * to load content for.
         *
         * @param diffs unused by this stub
         * @param baseChangeNum unused by this stub
         * @param checkLocalChanges unused by this stub
         */
        @Override
        public void loadContentForDiffs(Collection<SourceFileDiffContext> diffs, String baseChangeNum,
                                         boolean checkLocalChanges) {
            // no-op: this stub returns no diffs
        }

        /**
         * Report no changed file paths; this goal performs no test selection.
         *
         * @param baseChangeNum unused by this stub
         * @param checkLocalChanges unused by this stub
         * @return an empty set
         */
        @Override
        public Set<String> getChangedFilePaths(String baseChangeNum, boolean checkLocalChanges) {
            return Collections.emptySet();
        }

        /**
         * No-op: this stub holds no resources to release.
         */
        @Override public void close() { }
    }
}
