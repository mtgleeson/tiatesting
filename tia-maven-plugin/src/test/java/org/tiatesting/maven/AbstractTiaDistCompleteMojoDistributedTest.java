package org.tiatesting.maven;

import org.apache.maven.plugin.MojoExecutionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tiatesting.core.agent.ForkSystemProperties;
import org.tiatesting.core.diff.SourceFileDiffContext;
import org.tiatesting.core.model.DistributedRun;
import org.tiatesting.core.model.DistributedRunGroup;
import org.tiatesting.core.model.DistributedRunGroupStatus;
import org.tiatesting.core.model.DistributedRunPlan;
import org.tiatesting.core.persistence.BranchSchema;
import org.tiatesting.core.persistence.DataStore;
import org.tiatesting.core.persistence.JdbcDataStore;
import org.tiatesting.core.persistence.connection.H2ConnectionProvider;
import org.tiatesting.core.persistence.dialect.H2Dialect;
import org.tiatesting.core.persistence.h2.H2ConnectionSettings;
import org.tiatesting.core.vcs.VCSReader;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cover {@link AbstractTiaDistCompleteMojo}: the goal that replaces the JVM shutdown hook a
 * distributed runner used to release its barrier from. Only the build tool knows when Surefire's
 * retries are finished, so this goal - not the forked test JVM - is what completes a runner's
 * claimed group and stands it for election to seal the build.
 *
 * <p>The mojo is driven end to end through {@code execute()} against a real embedded-H2
 * {@link JdbcDataStore}, with only the VCS reader and the datastore construction substituted, the
 * same shape {@code AbstractTiaAgentMojoDistributedTest} uses. Every test sets up its group's claim
 * and progress directly through the datastore - the way {@code DistributedRunnerPersistTest} does -
 * rather than through {@code prepare-agent}, since this goal only ever reads back what {@code
 * prepare-agent} would have written to {@code fork.properties}.
 */
class AbstractTiaDistCompleteMojoDistributedTest {

    private static final String BRANCH = "main";
    private static final String PLAN_COMMIT = "commit-1";

    @TempDir
    File tempDir;

    private File dbDir;
    private File buildDir;

    /**
     * Create the two temp directories each test needs - the embedded database's directory and the
     * mojo's {@code tiaBuildDir} - and bootstrap the database schema, so every test starts from an
     * isolated store with no run planned.
     */
    @BeforeEach
    void setUp() {
        dbDir = new File(tempDir, "db");
        buildDir = new File(tempDir, "build");
        dbDir.mkdirs();
        buildDir.mkdirs();
        try (DataStore dataStore = openStore()) {
            dataStore.getTiaData(true);
        }
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
                BranchSchema.schemaName(BRANCH));
    }

    /**
     * Persist a plan of {@code groupCount} groups, each assigned one suite, so a test has a run and
     * a claimable group to work with. Mirrors {@code DistributedRunnerPersistTest#persistPlan}.
     *
     * @param runId the run identifier to plan under
     * @param groupCount how many groups the plan is split into
     */
    private void persistPlan(final String runId, final int groupCount) {
        List<DistributedRunGroup> groups = new ArrayList<>();
        Map<Integer, List<String>> suites = new HashMap<>();
        for (int i = 0; i < groupCount; i++) {
            groups.add(DistributedRunGroup.pending(runId, i, 1000L));
            suites.put(i, Arrays.asList("com.example.Suite" + i + "Test"));
        }
        try (DataStore dataStore = openStore()) {
            dataStore.persistDistributedRunPlan(new DistributedRunPlan(
                    DistributedRun.open(runId, BRANCH, PLAN_COMMIT, groupCount, null,
                            1000L * groupCount, 1234L), groups, suites, null));
        }
    }

    /**
     * Claim group 0 of a run under the given runner key and report enough progress (its one
     * assigned suite, observed) to satisfy {@code completeGroup}'s completeness guard, so a test
     * can complete the group without first driving a real test run through it.
     *
     * @param runId the run the group belongs to
     * @param runnerKey the identity to claim under
     */
    private void claimAndObserveGroupZero(final String runId, final String runnerKey) {
        try (DataStore dataStore = openStore()) {
            dataStore.claimNextPendingGroup(runId, runnerKey, 5000L);
            dataStore.reportGroupProgress(runId, 0, runnerKey, 1000L, 1, 0, 1);
        }
    }

    /**
     * Read group 0 of a run back, so a test can assert what the goal actually left on disk.
     *
     * @param runId the run the group belongs to
     * @return the stored group
     */
    private DistributedRunGroup readGroupZero(final String runId) {
        try (DataStore dataStore = openStore()) {
            for (DistributedRunGroup group : dataStore.readDistributedRunGroups(runId)) {
                if (group.getGroupNumber() == 0) {
                    return group;
                }
            }
        }
        throw new IllegalStateException("no group 0 in run " + runId);
    }

    /**
     * Write a fork properties file carrying the distributed handoff a claimed runner's {@code
     * prepare-agent} execution would have written, plus the update-DB flags the seal must read from
     * this same file rather than from the goal's own {@code -D} parameters.
     *
     * @param runId the run id to write
     * @param runnerKey the runner key to write - the value the claim was actually recorded under
     * @param groupNumber the claimed group number, or null to write no group (a surplus runner)
     * @param updateDBMapping the mapping-update flag to write
     * @throws IOException if the file cannot be written
     */
    private void writeForkProperties(final String runId, final String runnerKey,
                                     final Integer groupNumber, final boolean updateDBMapping)
            throws IOException {
        Map<String, String> props = new LinkedHashMap<>();
        props.put("tiaDistributed", "true");
        props.put("tiaRunId", runId);
        props.put("tiaDistributedRunnerKey", runnerKey);
        if (groupNumber != null) {
            props.put("tiaDistributedGroupNumber", String.valueOf(groupNumber));
        }
        props.put("tiaUpdateDBMapping", String.valueOf(updateDBMapping));
        props.put("tiaUpdateDBStats", String.valueOf(updateDBMapping));
        props.put("tiaUpdateDBTestRunHistory", String.valueOf(updateDBMapping));
        ForkSystemProperties.write(props, new File(buildDir, "fork.properties"));
    }

    /**
     * Build a mojo pointed at this test's temp build directory, ready for {@code execute()}. Tia
     * is enabled and {@code tiaDBUrl} is set to a non-blank server-mode URL so the shared-database
     * guard passes - individual tests override either field to exercise those guards directly.
     *
     * @return a mojo configured for this test's fixtures
     */
    private TestMojo mojo() {
        TestMojo mojo = new TestMojo();
        mojo.tiaBuildDir = buildDir.getAbsolutePath();
        mojo.tiaEnabled = true;
        mojo.tiaDBUrl = "jdbc:h2:tcp://localhost:9092/mem:tia";
        return mojo;
    }

    /**
     * Verify that with no fork properties file at all, the goal logs and exits successfully rather
     * than throwing - the property that makes it safe to run unconditionally in a pipeline, even
     * for a project whose build never went distributed.
     */
    @Test
    void shouldNoOpWhenNoForkPropertiesFileExists() {
        // given - no fork.properties written under buildDir

        // when / then
        assertDoesNotThrow(() -> mojo().execute());
    }

    /**
     * Verify that a fork properties file with no {@code tiaDistributed} key - the shape an
     * ordinary, non-distributed build's {@code prepare-agent} writes - is treated the same as no
     * file at all.
     *
     * @throws IOException if the fixture file cannot be written
     */
    @Test
    void shouldNoOpWhenForkPropertiesHasNoDistributedKey() throws IOException {
        // given - a fork properties file carrying only the ordinary, non-distributed properties
        Map<String, String> props = new LinkedHashMap<>();
        props.put("tiaEnabled", "true");
        props.put("tiaUpdateDBMapping", "true");
        ForkSystemProperties.write(props, new File(buildDir, "fork.properties"));

        // when / then
        assertDoesNotThrow(() -> mojo().execute());
    }

    /**
     * Verify a surplus runner - one whose fork properties carry the distributed handoff but no
     * claimed group number - exits successfully without attempting any completion or seal. It
     * claimed nothing, so it has nothing to complete and nothing to seal.
     *
     * @throws IOException if the fixture file cannot be written
     */
    @Test
    void shouldNoOpForASurplusRunner() throws IOException {
        // given
        persistPlan("run-surplus", 1);
        writeForkProperties("run-surplus", "runner-c", null, true);

        // when / then
        assertDoesNotThrow(() -> mojo().execute());
    }

    /**
     * Verify the goal completes the claimed group and seals the build when this runner is the only
     * (and therefore last) one in the run - the ordinary single-runner-per-group happy path.
     *
     * @throws Exception if the fixture file cannot be written or the goal fails
     */
    @Test
    void shouldCompleteAndSealWhenThisRunnerFinishesLast() throws Exception {
        // given
        persistPlan("run-1", 1);
        claimAndObserveGroupZero("run-1", "runner-a");
        writeForkProperties("run-1", "runner-a", 0, true);

        // when
        mojo().execute();

        // then - the group closed, and the sealer advanced the stored commit to the plan's commit
        assertEquals(DistributedRunGroupStatus.COMPLETED, readGroupZero("run-1").getStatus());
        try (DataStore dataStore = openStore()) {
            assertEquals(PLAN_COMMIT, dataStore.getTiaCore().getCommitValue());
        }
    }

    /**
     * Verify the goal completes the group under the runner key resolved from the fork properties
     * file, not one this build's own {@code -D} parameters (or a freshly re-derived value) would
     * supply. The claim was recorded under {@code runner-file}; the mojo's own {@code
     * tiaDistributedRunnerKey} parameter is deliberately set to a different value, standing in for
     * a different process id. A goal that read its own parameter instead of the file would complete
     * under the wrong key, match no row, and leave the group open forever.
     *
     * @throws Exception if the fixture file cannot be written or the goal fails
     */
    @Test
    void shouldCompleteUsingTheRunnerKeyResolvedFromTheFileNotOneDerivedAfresh() throws Exception {
        // given - the claim was recorded under "runner-file"; this mojo's own runner-key parameter
        // names a different process entirely
        persistPlan("run-2", 1);
        claimAndObserveGroupZero("run-2", "runner-file");
        writeForkProperties("run-2", "runner-file", 0, true);
        TestMojo mojo = mojo();
        mojo.tiaDistributedRunnerKey = "runner-different-process-id";

        // when
        mojo.execute();

        // then - the group closed under the key the file carried, proving the mojo's own parameter
        // was never consulted
        assertEquals(DistributedRunGroupStatus.COMPLETED, readGroupZero("run-2").getStatus());
        assertEquals("runner-file", readGroupZero("run-2").getRunnerKey());
    }

    /**
     * Verify a rejected completion because the group was already completed - by an earlier,
     * duplicate invocation of this same goal, say - succeeds rather than fails the build. {@code
     * DistributedRunnerPersist#completeGroup} returning null for this reason is a normal outcome,
     * not an error: there is nothing further to complete or seal.
     *
     * @throws IOException if the fixture file cannot be written
     */
    @Test
    void shouldSucceedWhenTheGroupIsAlreadyCompleted() throws IOException {
        // given - the group was already completed once
        persistPlan("run-3", 1);
        claimAndObserveGroupZero("run-3", "runner-a");
        try (DataStore dataStore = openStore()) {
            dataStore.completeGroup("run-3", 0, "runner-a", 6000L);
        }
        writeForkProperties("run-3", "runner-a", 0, true);

        // when / then - a second completion attempt must not fail the build
        assertDoesNotThrow(() -> mojo().execute());
    }

    /**
     * Verify a rejected completion because the run was superseded - a newer build's plan write
     * cleared this run's rows - also succeeds rather than fails the build, for the same reason as
     * the already-completed case: it is a normal, expected outcome for a straggler.
     *
     * @throws IOException if the fixture file cannot be written
     */
    @Test
    void shouldSucceedWhenTheRunWasSuperseded() throws IOException {
        // given - this runner's plan is superseded by a later plan write under a different run id
        persistPlan("run-4", 1);
        claimAndObserveGroupZero("run-4", "runner-a");
        writeForkProperties("run-4", "runner-a", 0, true);
        persistPlan("run-5", 1);

        // when / then
        assertDoesNotThrow(() -> mojo().execute());
    }

    /**
     * Verify the goal fails loudly, naming that the build was not sealed, when the datastore it
     * needs cannot be reached - the "unexpected error" the goal must never swallow, since a
     * silently un-sealed build is the failure the user must be told about.
     *
     * @throws IOException if the fixture file cannot be written
     */
    @Test
    void shouldFailLoudlyOnABadDatastore() throws IOException {
        // given
        persistPlan("run-6", 1);
        claimAndObserveGroupZero("run-6", "runner-a");
        writeForkProperties("run-6", "runner-a", 0, true);
        TestMojo mojo = mojo();
        mojo.failDataStore = true;

        // when
        MojoExecutionException thrown = assertThrows(MojoExecutionException.class, mojo::execute);

        // then
        assertTrue(thrown.getMessage().contains("NOT be sealed"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("run-6"), thrown.getMessage());
    }

    /**
     * Verify the goal fails loudly when the fork properties file exists but cannot be read - the
     * documented "unreadable-but-present" case, distinct from an absent file, which the goal must
     * fail rather than silently treat as a no-op.
     */
    @Test
    void shouldFailLoudlyWhenTheForkPropertiesFileExistsButCannotBeRead() {
        // given - a directory sitting where the fork properties file is expected, which cannot be
        // read as a properties file
        new File(buildDir, "fork.properties").mkdirs();

        // when
        MojoExecutionException thrown = assertThrows(MojoExecutionException.class, () -> mojo().execute());

        // then
        assertTrue(thrown.getMessage().contains("NOT be sealed"), thrown.getMessage());
    }

    /**
     * Verify the goal fails loudly, naming that the run was not sealed, when it is pointed at a
     * private embedded database instead of the shared datastore the run's runners coordinate
     * through - the guard that catches a pipeline invoking this goal as a separate {@code mvn}
     * command with the DB connection settings omitted, which would otherwise silently open an
     * empty embedded database, find no claimed row, and exit as if already completed.
     *
     * @throws IOException if the fixture file cannot be written
     */
    @Test
    void shouldFailLoudlyWhenPointedAtAnEmbeddedDatabase() throws IOException {
        // given - a claimed runner, but tiaDBUrl is left blank, resolving to an embedded database
        persistPlan("run-7", 1);
        claimAndObserveGroupZero("run-7", "runner-a");
        writeForkProperties("run-7", "runner-a", 0, true);
        TestMojo mojo = mojo();
        mojo.tiaDBUrl = null;

        // when
        MojoExecutionException thrown = assertThrows(MojoExecutionException.class, mojo::execute);

        // then
        assertTrue(thrown.getMessage().contains("NOT"), thrown.getMessage());
        assertTrue(thrown.getMessage().toLowerCase().contains("embedded"), thrown.getMessage());
    }

    /**
     * Verify the goal fails loudly, naming the offending property, when the fork properties file's
     * distributed handoff is malformed - a group number that is not a number, the shape a corrupted
     * or hand-edited {@code fork.properties} could carry.
     *
     * @throws IOException if the fixture file cannot be written
     */
    @Test
    void shouldFailLoudlyOnAMalformedGroupNumber() throws IOException {
        // given - a fork properties file whose group number is not a number
        Map<String, String> props = new LinkedHashMap<>();
        props.put("tiaDistributed", "true");
        props.put("tiaRunId", "run-8");
        props.put("tiaDistributedRunnerKey", "runner-a");
        props.put("tiaDistributedGroupNumber", "abc");
        ForkSystemProperties.write(props, new File(buildDir, "fork.properties"));

        // when
        MojoExecutionException thrown = assertThrows(MojoExecutionException.class, () -> mojo().execute());

        // then - the message names the offending property, from DistributedForkProperties'
        // single copy of the group-number parsing
        assertTrue(thrown.getMessage().contains("tiaDistributedGroupNumber"), thrown.getMessage());
    }

    /**
     * Verify the goal does nothing, before even reading {@code fork.properties}, when Tia is
     * disabled - a build with {@code tiaEnabled=false} must not act on a stale handoff file left
     * over from an earlier distributed build, and must not fail on connection settings it was
     * never going to use.
     *
     * @throws IOException if the fixture file cannot be written
     */
    @Test
    void shouldNoOpWhenTiaIsDisabled() throws IOException {
        // given - a stale, fully valid distributed handoff, but Tia is switched off and tiaDBUrl
        // is deliberately left blank, so the goal must never reach the shared-database guard
        persistPlan("run-9", 1);
        claimAndObserveGroupZero("run-9", "runner-a");
        writeForkProperties("run-9", "runner-a", 0, true);
        TestMojo mojo = mojo();
        mojo.tiaEnabled = false;
        mojo.tiaDBUrl = null;

        // when / then
        assertDoesNotThrow(mojo::execute);
        assertEquals(DistributedRunGroupStatus.CLAIMED, readGroupZero("run-9").getStatus());
    }

    /**
     * Concrete dist-complete mojo for the test: stubs the VCS reader to a fixed branch and points
     * the datastore construction at this test's temp directory, or - when {@link #failDataStore} is
     * set - simulates an unreachable datastore by throwing instead of opening one.
     */
    private final class TestMojo extends AbstractTiaDistCompleteMojo {

        private boolean failDataStore;

        /**
         * @return a stub VCS reader reporting this test's fixed branch
         */
        @Override
        public VCSReader getVCSReader() {
            return new StubVCSReader();
        }

        /**
         * Build the datastore over this test's temp directory, or simulate a datastore that cannot
         * be reached when {@link #failDataStore} is set.
         *
         * @param branch the VCS branch name whose schema the store selects
         * @return an embedded datastore the mojo owns and closes
         */
        @Override
        protected DataStore buildDataStore(final String branch) {
            if (failDataStore) {
                throw new RuntimeException("simulated datastore connection failure");
            }
            return openStore();
        }
    }

    /**
     * Minimal VCS reader reporting a fixed branch. This goal never diffs, so only the branch name
     * is ever read.
     */
    private static final class StubVCSReader implements VCSReader {

        /**
         * @return the fixed branch these tests plan and claim against
         */
        @Override
        public String getBranchName() {
            return BRANCH;
        }

        /**
         * Never called by this goal.
         *
         * @return never returns
         */
        @Override
        public String getHeadCommit() {
            throw new UnsupportedOperationException("tia-dist-complete must not read the head commit");
        }

        /**
         * Never called by this goal.
         *
         * @param baseChangeNum ignored
         * @param sourceFilesDirs ignored
         * @param testFilesDirs ignored
         * @param checkLocalChanges ignored
         * @return never returns
         */
        @Override
        public Set<SourceFileDiffContext> getDiffFiles(final String baseChangeNum,
                                                        final List<String> sourceFilesDirs,
                                                        final List<String> testFilesDirs,
                                                        final boolean checkLocalChanges) {
            throw new UnsupportedOperationException("tia-dist-complete must not diff");
        }

        /**
         * Never called by this goal.
         *
         * @param diffs ignored
         * @param baseChangeNum ignored
         * @param checkLocalChanges ignored
         */
        @Override
        public void loadContentForDiffs(final Collection<SourceFileDiffContext> diffs,
                                         final String baseChangeNum, final boolean checkLocalChanges) {
            throw new UnsupportedOperationException("tia-dist-complete must not diff");
        }

        /**
         * Never called by this goal.
         *
         * @param baseChangeNum ignored
         * @param checkLocalChanges ignored
         * @return never returns
         */
        @Override
        public Set<String> getChangedFilePaths(final String baseChangeNum, final boolean checkLocalChanges) {
            throw new UnsupportedOperationException("tia-dist-complete must not diff");
        }

        /**
         * No resources to release.
         */
        @Override
        public void close() {
        }
    }
}
