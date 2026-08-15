package org.tiatesting.maven;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.model.Build;
import org.apache.maven.model.Model;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tiatesting.core.agent.ForkSystemProperties;
import org.tiatesting.core.diff.SourceFileDiffContext;
import org.tiatesting.core.distributed.DistributedForkProperties;
import org.tiatesting.core.distributed.DistributedRunnerContext;
import org.tiatesting.core.model.DistributedRun;
import org.tiatesting.core.model.DistributedRunGroup;
import org.tiatesting.core.model.DistributedRunPlan;
import org.tiatesting.core.model.TestSuiteTracker;
import org.tiatesting.core.persistence.BranchSchema;
import org.tiatesting.core.persistence.DataStore;
import org.tiatesting.core.persistence.JdbcDataStore;
import org.tiatesting.core.persistence.connection.H2ConnectionProvider;
import org.tiatesting.core.persistence.dialect.H2Dialect;
import org.tiatesting.core.persistence.h2.H2ConnectionSettings;
import org.tiatesting.core.vcs.VCSReader;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cover the distributed branch of {@link AbstractTiaAgentMojo}: the goal that runs before surefire
 * forks the test JVM. In distributed mode it must claim a group from the plan rather than run the
 * selection again - re-running it would repeat the VCS diff every runner has already paid for once
 * and, worse, re-run the library-impact drain, which races when several runners do it at the same
 * time.
 *
 * <p>The mojo is driven end to end through {@code execute()} against a real embedded-H2
 * {@link JdbcDataStore}, with only the two things a unit test cannot supply substituted: the VCS
 * reader (stubbed to a fixed branch and commit) and the datastore construction (pointed at a temp
 * directory). {@code tiaDBUrl} is still set to a server-mode URL because the distributed
 * preconditions reject an embedded datastore by inspecting that string, and those preconditions are
 * part of what these tests exercise.
 */
class AbstractTiaAgentMojoDistributedTest {

    private static final String BRANCH = "main";
    private static final String PLAN_COMMIT = "commit-1";
    private static final String SHARED_DB_URL = "jdbc:h2:tcp://localhost:9092/tiadb";

    @TempDir
    File tempDir;

    private File dbDir;
    private File buildDir;

    /**
     * Create the two temp directories each test needs - the embedded database's directory and the
     * mojo's {@code tiaBuildDir} - and bootstrap the database schema, so every test starts from an
     * isolated store with no run planned and no suite tracked.
     */
    @BeforeEach
    void setUp() {
        dbDir = new File(tempDir, "db");
        buildDir = new File(tempDir, "build");
        dbDir.mkdirs();
        buildDir.mkdirs();
        try (DataStore dataStore = openStore(BRANCH)) {
            dataStore.getTiaData(true);
        }
    }

    /**
     * Open an embedded-H2 datastore over this test's temp database directory, on the per-branch
     * schema the given branch resolves to - the same construction the mojo's own datastore build
     * performs, so the test and the mojo read the same rows.
     *
     * @param branch the VCS branch name whose schema the store selects
     * @return an open datastore the caller must close
     */
    private JdbcDataStore openStore(final String branch) {
        return new JdbcDataStore(new H2Dialect(),
                new H2ConnectionProvider(H2ConnectionSettings.embedded(dbDir.getAbsolutePath())),
                BranchSchema.schemaName(branch));
    }

    /**
     * Build a bare {@link MavenProject} with the given artifact id, sufficient for {@code
     * AbstractTiaMojo#withReactorProjectNamesIfRelevant} to name in a rejection message - no build
     * section or dependencies are needed since the reactor-rule failure path never reads them. No
     * real reactor project lacks an artifact id, so a fixture project must have one too for a test
     * asserting on the naming behaviour to mean anything.
     *
     * @param artifactId the artifact id the project should report
     * @return a bare Maven project with only its artifact id set
     */
    private static MavenProject projectNamed(final String artifactId) {
        Model model = new Model();
        model.setArtifactId(artifactId);
        return new MavenProject(model);
    }

    /**
     * Persist a run plan with an exact, caller-chosen suite-to-group assignment, so a test asserts
     * against the grouping it wrote rather than one the balancer chose.
     *
     * @param runId the run identifier to plan under
     * @param commitValue the VCS commit the plan is pinned to
     * @param suitesByGroup the suite names each group number owns; group numbers must run from 0
     *                      upwards with no gaps, since the group rows are derived from this map
     */
    private void persistPlan(final String runId, final String commitValue,
                              final Map<Integer, List<String>> suitesByGroup) {
        List<DistributedRunGroup> groups = new ArrayList<>();
        for (int groupNumber = 0; groupNumber < suitesByGroup.size(); groupNumber++) {
            groups.add(DistributedRunGroup.pending(runId, groupNumber, 1000L));
        }
        DistributedRun run = DistributedRun.open(runId, BRANCH, commitValue, groups.size(), null,
                1000L * groups.size(), 5000L);
        try (DataStore dataStore = openStore(BRANCH)) {
            dataStore.persistDistributedRunPlan(new DistributedRunPlan(run, groups, suitesByGroup, null));
        }
    }

    /**
     * Record the given suite names as tracked by Tia, so a claimed runner's ignore list has a
     * tracked set to subtract its own group from.
     *
     * @param suiteNames the suite names to persist as tracked
     */
    private void persistTracked(final String... suiteNames) {
        Map<String, TestSuiteTracker> trackers = new LinkedHashMap<>();
        for (String suiteName : suiteNames) {
            trackers.put(suiteName, new TestSuiteTracker(suiteName));
        }
        try (DataStore dataStore = openStore(BRANCH)) {
            dataStore.persistTestSuites(trackers);
        }
    }

    /**
     * Build the suite-to-group assignment most of these tests plan with: group 0 owns
     * {@code ATest} and {@code BTest}, group 1 owns {@code CTest}.
     *
     * @return a two-group suite assignment
     */
    private static Map<Integer, List<String>> twoGroupAssignment() {
        Map<Integer, List<String>> suitesByGroup = new HashMap<>();
        suitesByGroup.put(0, Arrays.asList("com.example.ATest", "com.example.BTest"));
        suitesByGroup.put(1, Collections.singletonList("com.example.CTest"));
        return suitesByGroup;
    }

    /**
     * Build a mojo configured for a distributed runner: Tia enabled, a shared database URL, and
     * the run id the plan was written under. No runner key is configured, so the claim protocol
     * derives one - which is the case that matters, since the derived key is the one the forked
     * JVM cannot work out for itself.
     *
     * @param runId the distributed run id the runner claims from
     * @param workspaceCommit the commit the stubbed VCS reader reports this workspace is on
     * @return a mojo ready for {@code execute()}
     */
    private TestMojo distributedMojo(final String runId, final String workspaceCommit) {
        TestMojo mojo = new TestMojo(workspaceCommit);
        mojo.tiaEnabled = true;
        mojo.tiaBuildDir = buildDir.getAbsolutePath();
        mojo.tiaDBUrl = SHARED_DB_URL;
        mojo.tiaDistributed = true;
        mojo.tiaRunId = runId;
        mojo.tiaDistributedGroupCount = 2;
        return mojo;
    }

    /**
     * Build a distributed runner mojo claiming under an explicit runner key, so a test can stand
     * up several distinct runners inside one JVM. Without an explicit key every runner here would
     * derive the same one from the shared run id, host and process id, and the second claim would
     * be treated as the first runner's job retry re-claiming its own group.
     *
     * @param runId the distributed run id the runner claims from
     * @param runnerKey the identity this runner claims under
     * @return a mojo ready for {@code execute()}
     */
    private TestMojo runnerWithKey(final String runId, final String runnerKey) {
        TestMojo mojo = distributedMojo(runId, PLAN_COMMIT);
        mojo.tiaDistributedRunnerKey = runnerKey;
        return mojo;
    }

    /**
     * Read one of the mojo's newline-separated test-name files back as a set.
     *
     * @param fileName the file's name under the mojo's build directory
     * @return the suite names in the file, empty when the file holds only the empty-set marker
     * @throws IOException if the file cannot be read
     */
    private Set<String> readTestsFile(final String fileName) throws IOException {
        Path file = buildDir.toPath().resolve(fileName);
        Set<String> tests = new HashSet<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (!line.trim().isEmpty()) {
                tests.add(line.trim());
            }
        }
        return tests;
    }

    /**
     * Read the properties file the mojo writes for the forked test JVM to pick up in its agent's
     * {@code premain}.
     *
     * @return the fork properties
     * @throws IOException if the file cannot be read
     */
    private Properties readForkProperties() throws IOException {
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(buildDir.toPath().resolve("fork.properties"))) {
            props.load(in);
        }
        return props;
    }

    /**
     * Verify a runner that claims a group runs exactly that group's suites: its selected list is
     * the group's suites and its ignore list is every other tracked or planned suite. Between the
     * runners of a plan this is what makes each suite run exactly once.
     *
     * @throws Exception if the goal fails or the written files cannot be read
     */
    @Test
    void shouldWriteTheClaimedGroupsSuitesAsSelectedAndTheComplementAsIgnored() throws Exception {
        // given
        persistPlan("run-1", PLAN_COMMIT, twoGroupAssignment());
        persistTracked("com.example.ATest", "com.example.BTest", "com.example.CTest",
                "com.example.DTest");
        TestMojo mojo = distributedMojo("run-1", PLAN_COMMIT);

        // when
        mojo.execute();

        // then
        assertEquals(new HashSet<>(Arrays.asList("com.example.ATest", "com.example.BTest")),
                readTestsFile("selected-tests.txt"));
        assertEquals(new HashSet<>(Arrays.asList("com.example.CTest", "com.example.DTest")),
                readTestsFile("ignored-tests.txt"));
    }

    /**
     * Verify the resolved runner key and the claimed group number reach the forked test JVM
     * through the fork properties file, and that the key written is the one the claim was actually
     * recorded under. A later stage completes the group from inside that JVM, which can reconstruct
     * neither value: a key it derived for itself would not match the claimed row and the group
     * would be orphaned.
     *
     * @throws Exception if the goal fails or the written files cannot be read
     */
    @Test
    void shouldHandTheResolvedRunnerKeyAndGroupNumberToTheForkedTestJvm() throws Exception {
        // given
        persistPlan("run-2", PLAN_COMMIT, twoGroupAssignment());
        TestMojo mojo = distributedMojo("run-2", PLAN_COMMIT);

        // when
        mojo.execute();

        // then
        String claimedRunnerKey;
        try (DataStore dataStore = openStore(BRANCH)) {
            claimedRunnerKey = dataStore.readDistributedRunGroups("run-2").get(0).getRunnerKey();
        }
        Properties forkProperties = readForkProperties();
        assertNotNull(claimedRunnerKey);
        assertEquals(claimedRunnerKey, forkProperties.getProperty("tiaDistributedRunnerKey"));
        assertEquals("0", forkProperties.getProperty("tiaDistributedGroupNumber"));
        assertEquals("true", forkProperties.getProperty("tiaDistributed"));
        assertEquals("run-2", forkProperties.getProperty("tiaRunId"));
    }

    /**
     * Verify a surplus runner - one that starts after every group has been claimed, which a
     * pipeline whose fan-out is wider than the plan's group count legitimately produces - ignores
     * every suite, selects none, and carries no group number to the forked JVM. A group number it
     * did not claim would have the later completion step report progress for another runner's work.
     *
     * @throws Exception if the goal fails or the written files cannot be read
     */
    @Test
    void shouldIgnoreEverySuiteAndCarryNoGroupNumberForASurplusRunner() throws Exception {
        // given
        persistPlan("run-3", PLAN_COMMIT, twoGroupAssignment());
        persistTracked("com.example.ATest", "com.example.BTest", "com.example.CTest",
                "com.example.DTest");
        // Distinct runner keys: these stand in for three separate CI jobs, and a claim by a key
        // that already holds a group is a job retry re-claiming it rather than a new runner.
        runnerWithKey("run-3", "runner-a").execute();
        runnerWithKey("run-3", "runner-b").execute();

        // when
        runnerWithKey("run-3", "runner-c").execute();

        // then
        assertTrue(readTestsFile("selected-tests.txt").isEmpty());
        assertEquals(new HashSet<>(Arrays.asList("com.example.ATest", "com.example.BTest",
                "com.example.CTest", "com.example.DTest")), readTestsFile("ignored-tests.txt"));
        Properties forkProperties = readForkProperties();
        assertNull(forkProperties.getProperty("tiaDistributedGroupNumber"));
        assertEquals("true", forkProperties.getProperty("tiaDistributed"));
        assertNotNull(forkProperties.getProperty("tiaDistributedRunnerKey"));
    }

    /**
     * Verify the drain result file is never written on the runner path. The library-impact drain
     * belongs to the plan, which performed it once; a runner that wrote a drain result would have
     * its test JVM apply cleanup for rows it never drained.
     *
     * @throws Exception if the goal fails
     */
    @Test
    void shouldNotWriteADrainResultFileOnTheRunnerPath() throws Exception {
        // given
        persistPlan("run-4", PLAN_COMMIT, twoGroupAssignment());
        TestMojo mojo = distributedMojo("run-4", PLAN_COMMIT);

        // when
        mojo.execute();

        // then
        assertFalse(new File(buildDir, "drain-result.ser").exists());
    }

    /**
     * Verify a runner whose run id has no plan fails the goal rather than continuing. This is a
     * straggler from a superseded build; the ignore list must not be written, because a runner that
     * cannot tell whether its share of the suite ran must never report a green build.
     */
    @Test
    void shouldFailTheGoalWhenNoRunIsPlannedUnderTheRunId() {
        // given
        TestMojo mojo = distributedMojo("run-missing", PLAN_COMMIT);

        // when
        MojoExecutionException thrown = assertThrows(MojoExecutionException.class, mojo::execute);

        // then
        assertTrue(thrown.getMessage().contains("run-missing"), thrown.getMessage());
        assertFalse(new File(buildDir, "ignored-tests.txt").exists(),
                "no ignore list may be written when the run cannot be claimed");
    }

    /**
     * Verify a runner whose workspace sits on a different commit than the plan was built for fails
     * the goal, naming both commits. Its group's suites were chosen by diffing the planned commit,
     * so running them here would test different code than they were selected for.
     */
    @Test
    void shouldFailTheGoalWhenTheWorkspaceCommitDiffersFromThePlan() {
        // given
        persistPlan("run-5", PLAN_COMMIT, twoGroupAssignment());
        TestMojo mojo = distributedMojo("run-5", "a-different-commit");

        // when
        MojoExecutionException thrown = assertThrows(MojoExecutionException.class, mojo::execute);

        // then
        assertTrue(thrown.getMessage().contains(PLAN_COMMIT), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("a-different-commit"), thrown.getMessage());
        assertFalse(new File(buildDir, "ignored-tests.txt").exists(),
                "no ignore list may be written when the run cannot be claimed");
    }

    /**
     * Verify the distributed preconditions are enforced on the runner, not only on the planner: a
     * runner pointed at an embedded database cannot see any other runner's claims, so it must fail
     * rather than claim group 0 alongside every other runner in the build.
     */
    @Test
    void shouldFailTheGoalWhenTheDatabaseIsNotShared() {
        // given
        TestMojo mojo = distributedMojo("run-6", PLAN_COMMIT);
        mojo.tiaDBUrl = null;

        // when
        MojoExecutionException thrown = assertThrows(MojoExecutionException.class, mojo::execute);

        // then
        assertTrue(thrown.getMessage().contains("tiaDBUrl"), thrown.getMessage());
    }

    /**
     * Verify a runner refuses to claim on a multi-module reactor, and that the rejection names
     * every module found in it - {@code AbstractTiaMojo.withReactorProjectNamesIfRelevant} reads
     * each {@link MavenProject#getArtifactId()}, so a fixture built with a bare {@code new
     * MavenProject(new Model())} (no artifact id set) would silently produce "null, null, null"
     * instead of exercising this naming path; {@link #projectNamed(String)} gives each project a
     * real artifact id so the assertions below are actually checking something. {@code
     * prepare-agent} is bound to the {@code INITIALIZE} phase, not to an aggregator goal, so Maven
     * runs it once per reactor module; without this rule each module would claim its own group
     * from the same plan, leaving suites assigned to a group whose runner lives in a different
     * module - nobody runs them, and the build still reports success. The precondition must reject
     * this before any group is claimed, so no ignore list is written either.
     */
    @Test
    void shouldFailTheGoalWhenTheReactorHasMoreThanOneProject() {
        // given a plan that would otherwise be claimable, but a three-module reactor
        persistPlan("run-11", PLAN_COMMIT, twoGroupAssignment());
        TestMojo mojo = distributedMojo("run-11", PLAN_COMMIT);
        mojo.reactorProjects = Arrays.asList(projectNamed("module-a"), projectNamed("module-b"),
                projectNamed("module-c"));

        // when
        MojoExecutionException thrown = assertThrows(MojoExecutionException.class, mojo::execute);

        // then - the reactor rule fired, naming the project count and every module, before any
        // group was claimed
        String message = thrown.getMessage();
        assertTrue(message.contains("3 projects"), message);
        assertTrue(message.contains("module-a"), message);
        assertTrue(message.contains("module-b"), message);
        assertTrue(message.contains("module-c"), message);
        assertFalse(new File(buildDir, "ignored-tests.txt").exists(),
                "no ignore list may be written when the reactor rule rejects the claim");
    }

    /**
     * Verify a non-distributed build is completely unaffected by the reactor-size rule, even on a
     * multi-module reactor. {@code validatedDistributedRunConfig()} - the method the reactor-size
     * rule lives in - is reached only through {@code claimDistributedRunGroup()}, which {@code
     * execute()} calls only when {@code isTiaDistributed()} is true; with distribution off,
     * {@code execute()} takes the ordinary selection branch instead, which never reads {@code
     * getReactorProjects()} at all.
     *
     * @throws Exception if the goal fails for a reason other than what this test verifies the
     *                    absence of
     */
    @Test
    void shouldNotEnforceTheReactorRuleForANonDistributedMultiModuleReactor() throws Exception {
        // given a non-distributed mojo on a three-module reactor, and no stored mapping to select
        // against - selectTestsToIgnore's "run all tests" fallback, exercised without needing a
        // working VCS reader
        TestMojo mojo = distributedMojo("run-12", PLAN_COMMIT);
        mojo.tiaDistributed = false;
        mojo.reactorProjects = Arrays.asList(new MavenProject(new Model()),
                new MavenProject(new Model()), new MavenProject(new Model()));

        // when - execute() must not throw, since the reactor rule is never reached
        mojo.execute();

        // then - the ordinary "no stored mapping" path ran, selecting every test and ignoring none
        assertTrue(readTestsFile("ignored-tests.txt").isEmpty());
    }

    /**
     * Verify a non-distributed run's fork properties carry none of the distributed handoff values,
     * so the forked test JVM of an ordinary build sees exactly the properties it saw before
     * distributed runs existed.
     *
     * @throws Exception if the properties file cannot be written or read
     */
    @Test
    void shouldWriteNoDistributedPropertiesForANonDistributedRun() throws Exception {
        // given
        TestMojo mojo = distributedMojo("run-7", PLAN_COMMIT);
        mojo.tiaDistributed = false;

        // when
        mojo.writeForkPropertiesFile(null);

        // then
        Properties forkProperties = readForkProperties();
        assertNull(forkProperties.getProperty("tiaDistributed"));
        assertNull(forkProperties.getProperty("tiaRunId"));
        assertNull(forkProperties.getProperty("tiaDistributedRunnerKey"));
        assertNull(forkProperties.getProperty("tiaDistributedGroupNumber"));
        assertEquals("true", forkProperties.getProperty("tiaEnabled"));
    }

    /**
     * Verify the fork properties this goal writes are enough for the forked test JVM to resolve the
     * distributed runner context its persist runs under - the same values, through the same file,
     * that the Tia agent republishes as system properties before any listener constructs.
     *
     * <p>Asserted end to end rather than property by property because the failure it guards against
     * is silent: a fork that resolved no context would persist as a single host, rebuild the method
     * catalogue from an edge set holding only its own group's suites, and stamp the commit while the
     * other runners were still going.
     *
     * @throws Exception if the goal fails or the properties file cannot be read
     */
    @Test
    void shouldHandTheForkedJvmEnoughToResolveItsDistributedRunnerContext() throws Exception {
        // given
        persistPlan("run-8", PLAN_COMMIT, twoGroupAssignment());
        TestMojo mojo = runnerWithKey("run-8", "runner-a");
        mojo.execute();

        // when - the agent's premain publishes the file, and the listener resolves its context
        DistributedRunnerContext context = resolveContextInAFork();

        // then
        assertNotNull(context, "a distributed fork must resolve a context, never fall through to "
                + "the single-host path");
        assertTrue(context.isClaimed());
        assertEquals("run-8", context.getRunId());
        assertEquals("runner-a", context.getRunnerKey());
        assertEquals(Integer.valueOf(0), context.getGroupNumber());
    }

    /**
     * Verify a surplus runner's fork resolves a context holding no group rather than no context.
     * A null there would put it on the single-host path, where it would seal a build whose other
     * runners are still running.
     *
     * @throws Exception if the goal fails or the properties file cannot be read
     */
    @Test
    void shouldLeaveASurplusRunnersForkWithAGrouplessContext() throws Exception {
        // given - every group already claimed by other runners
        persistPlan("run-9", PLAN_COMMIT, twoGroupAssignment());
        runnerWithKey("run-9", "runner-a").execute();
        runnerWithKey("run-9", "runner-b").execute();
        runnerWithKey("run-9", "runner-c").execute();

        // when
        DistributedRunnerContext context = resolveContextInAFork();

        // then
        assertNotNull(context, "a surplus runner is still a distributed runner");
        assertFalse(context.isClaimed(), "a surplus runner holds no group");
        assertEquals("runner-c", context.getRunnerKey());
    }

    /**
     * Verify an ordinary build's fork resolves no context at all, so its persist takes exactly the
     * single-host flow it always took.
     *
     * @throws Exception if the properties file cannot be written or read
     */
    @Test
    void shouldLeaveANonDistributedForkWithNoRunnerContext() throws Exception {
        // given
        TestMojo mojo = distributedMojo("run-10", PLAN_COMMIT);
        mojo.tiaDistributed = false;
        mojo.writeForkPropertiesFile(null);

        // when
        DistributedRunnerContext context = resolveContextInAFork();

        // then
        assertNull(context, "an ordinary build's fork must stay on the single-host persist");
    }

    /**
     * Replay what the forked test JVM does with the file this goal wrote: the Tia agent publishes
     * its entries as system properties at {@code premain} time, then the Tia test listener resolves
     * the distributed runner context from them. The four managed properties are cleared first
     * because the agent's publish deliberately does not override an already-set value, and this
     * test JVM is long-lived.
     *
     * @return the context the forked JVM's listener would persist under, or null when the build is
     *         not distributed
     * @throws IOException if the fork properties file cannot be read
     */
    private DistributedRunnerContext resolveContextInAFork() throws IOException {
        String[] managed = {DistributedForkProperties.PROP_DISTRIBUTED,
                DistributedForkProperties.PROP_RUN_ID, DistributedForkProperties.PROP_RUNNER_KEY,
                DistributedForkProperties.PROP_GROUP_NUMBER};
        Map<String, String> saved = new LinkedHashMap<>();
        for (String key : managed) {
            saved.put(key, System.getProperty(key));
            System.clearProperty(key);
        }
        try {
            ForkSystemProperties.applyToSystemProperties(
                    buildDir.toPath().resolve("fork.properties").toString());
            return DistributedForkProperties.contextFromSystemProperties();
        } finally {
            for (Map.Entry<String, String> entry : saved.entrySet()) {
                if (entry.getValue() == null) {
                    System.clearProperty(entry.getKey());
                } else {
                    System.setProperty(entry.getKey(), entry.getValue());
                }
            }
        }
    }

    /**
     * Concrete agent mojo for the test: supplies the abstract members the real wrapper plugins
     * supply, stubs the VCS reader to a fixed branch and workspace commit, and points the datastore
     * construction at this test's temp directory.
     */
    private final class TestMojo extends AbstractTiaAgentMojo {

        private final String workspaceCommit;
        private final MavenProject mavenProject;
        private List<MavenProject> reactorProjects = Collections.singletonList(new MavenProject(new Model()));

        /**
         * Build a mojo whose stubbed VCS reader reports the given workspace commit.
         *
         * @param workspaceCommit the commit the runner's workspace is reported to be on
         */
        private TestMojo(final String workspaceCommit) {
            this.workspaceCommit = workspaceCommit;
            Model model = new Model();
            model.setBuild(new Build());
            this.mavenProject = new MavenProject(model);
        }

        /**
         * @return a stub VCS reader reporting this test's branch and the configured workspace commit
         */
        @Override
        public VCSReader getVCSReader() {
            return new StubVCSReader(workspaceCommit);
        }

        /**
         * @return this test's configured reactor project list - a single bare project by default,
         *         standing in for {@link #session}'s projects so the test does not need to construct
         *         a real {@code MavenSession}; a test covering the multi-module rejection overrides
         *         it via {@link #reactorProjects}
         */
        @Override
        protected List<MavenProject> getReactorProjects() {
            return reactorProjects;
        }

        /**
         * Build the datastore over this test's temp directory rather than the configured
         * {@code tiaDBUrl}, which exists only to satisfy the shared-database precondition.
         *
         * @param branch the VCS branch name whose schema the store selects
         * @return an embedded datastore the mojo owns and closes
         */
        @Override
        protected DataStore buildDataStore(final String branch) {
            return openStore(branch);
        }

        /**
         * @return a bare Maven project carrying an empty build section and property set
         */
        @Override
        public MavenProject getProject() {
            return mavenProject;
        }

        /**
         * @return a placeholder agent jar path, since no JVM is forked in this test
         */
        @Override
        File getAgentJarFile() {
            return new File("tia-agent.jar");
        }

        /**
         * @return the agent artifact name the wrapper plugin would supply
         */
        @Override
        public String getAgentArtifactName() {
            return "org.tiatesting:tia-junit5-agent";
        }

        /**
         * @return an empty artifact map, unused because {@link #getAgentJarFile()} is overridden
         */
        @Override
        public Map<String, Artifact> getPluginArtifactMap() {
            return Collections.emptyMap();
        }
    }

    /**
     * Minimal VCS reader reporting a fixed branch and commit. The distributed runner path reads
     * only those two values; the diff methods are never reached, because claiming a group is what
     * replaces running the selection.
     */
    private static final class StubVCSReader implements VCSReader {

        private final String headCommit;

        /**
         * @param headCommit the commit this workspace is reported to be on
         */
        private StubVCSReader(final String headCommit) {
            this.headCommit = headCommit;
        }

        /**
         * @return the fixed branch these tests plan and claim against
         */
        @Override
        public String getBranchName() {
            return BRANCH;
        }

        /**
         * @return the workspace commit this reader was built with
         */
        @Override
        public String getHeadCommit() {
            return headCommit;
        }

        /**
         * Never called on the distributed runner path, which claims a planned group instead of
         * diffing.
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
            throw new UnsupportedOperationException("a distributed runner must not diff");
        }

        /**
         * Never called on the distributed runner path.
         *
         * @param diffs ignored
         * @param baseChangeNum ignored
         * @param checkLocalChanges ignored
         */
        @Override
        public void loadContentForDiffs(final Collection<SourceFileDiffContext> diffs,
                                         final String baseChangeNum, final boolean checkLocalChanges) {
            throw new UnsupportedOperationException("a distributed runner must not diff");
        }

        /**
         * Never called on the distributed runner path.
         *
         * @param baseChangeNum ignored
         * @param checkLocalChanges ignored
         * @return never returns
         */
        @Override
        public Set<String> getChangedFilePaths(final String baseChangeNum, final boolean checkLocalChanges) {
            throw new UnsupportedOperationException("a distributed runner must not diff");
        }

        /**
         * No resources to release.
         */
        @Override
        public void close() {
        }
    }
}
