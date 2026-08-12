package org.tiatesting.core.distributed;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tiatesting.core.agent.ForkSystemProperties;
import org.tiatesting.core.model.ClassImpactTracker;
import org.tiatesting.core.model.DistributedRun;
import org.tiatesting.core.model.DistributedRunGroup;
import org.tiatesting.core.model.DistributedRunGroupStatus;
import org.tiatesting.core.model.DistributedRunPlan;
import org.tiatesting.core.model.DistributedRunStatus;
import org.tiatesting.core.model.MethodImpactTracker;
import org.tiatesting.core.model.TestRunHistoryEntry;
import org.tiatesting.core.model.TestStats;
import org.tiatesting.core.model.TestSuiteTracker;
import org.tiatesting.core.model.TiaData;
import org.tiatesting.core.persistence.BranchSchema;
import org.tiatesting.core.persistence.DataStore;
import org.tiatesting.core.persistence.JdbcDataStore;
import org.tiatesting.core.persistence.connection.H2ConnectionProvider;
import org.tiatesting.core.persistence.dialect.H2Dialect;
import org.tiatesting.core.persistence.h2.H2ConnectionSettings;
import org.tiatesting.core.testrunner.TestRunResult;
import org.tiatesting.core.testrunner.TestRunnerService;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The whole distributed lifecycle, driven through the wiring a real build uses rather than through
 * contexts built by hand: plan a run, have two runners claim from it, carry each runner's claim into
 * its persist the way its build tool does, and let the second one seal.
 *
 * <p>Every other test in this feature starts somewhere in the middle - a context constructed
 * directly, or a group completed by calling the datastore. This one starts where a build starts,
 * and it is the only place the two handoffs are exercised for what they are:
 *
 * <ul>
 *   <li>the <b>Maven/JUnit</b> runner claims in the build JVM and forwards its claim through the
 *       fork properties file, which the Tia agent republishes as system properties in the forked
 *       test JVM - so this test writes that file and reads it back;</li>
 *   <li>the <b>Gradle/Spock</b> runner claims inside the test JVM and converts the assignment it
 *       already holds, since claiming again would take a second group.</li>
 * </ul>
 *
 * <p>What it asserts is what the whole stage exists for: exactly one seal for the build, exactly
 * one history row, and a method reachable only from the group that finished <em>last</em> still in
 * the catalogue afterwards. Break any part of the wiring and both runners take the single-host
 * path, each sealing from an edge set holding only its own group's suites - and every one of those
 * three assertions fails.
 *
 * <p>One runner also persists <b>twice</b>, as a Surefire retry makes it: the barrier is released
 * when a runner's JVM exits, not when one of its test plans finishes, and a method covered only by
 * the second test plan has to survive the seal too.
 *
 * <p>Runs against a real datastore. {@code PostgresDistributedRunWiringEndToEndTest} re-runs the
 * same lifecycle against Postgres, which is what a real distributed build actually uses, since the
 * distributed preconditions reject an embedded database outright.
 */
class DistributedRunWiringEndToEndTest {

    static final String BRANCH = "main";
    static final String RUN_ID = "run-1";
    static final String PLAN_COMMIT = "plan-commit";
    static final String PRIOR_COMMIT = "prior-commit";
    static final String SUITE_A = "com.example.ATest";
    static final String SUITE_B = "com.example.BTest";
    static final String SUITE_RETRY = "com.example.RetryOnlyTest";
    static final int METHOD_A = 101;
    static final int METHOD_B = 202;
    static final int METHOD_RETRY = 303;

    private static final String[] MANAGED_PROPERTIES = {
            DistributedForkProperties.PROP_DISTRIBUTED, DistributedForkProperties.PROP_RUN_ID,
            DistributedForkProperties.PROP_RUNNER_KEY, DistributedForkProperties.PROP_GROUP_NUMBER
    };

    private Map<String, String> savedProperties;
    private File tempDir;

    /** The datastore every runner in the test's build shares, as they must in a real one. */
    DataStore dataStore;

    /**
     * Open the datastore this test's runners share. Overridden by the Postgres mirror so the same
     * lifecycle runs against the database a real distributed build uses.
     *
     * @return an open datastore the fixture owns and closes
     * @throws Exception if the store cannot be opened
     */
    DataStore openStore() throws Exception {
        tempDir = File.createTempFile("tia-distributed-wiring-", "");
        tempDir.delete();
        tempDir.mkdirs();
        return new JdbcDataStore(new H2Dialect(),
                new H2ConnectionProvider(H2ConnectionSettings.embedded(tempDir.getAbsolutePath())),
                BranchSchema.schemaName(BRANCH));
    }

    /**
     * Open the shared datastore, bootstrap its schema, seed the state a build runs against - a
     * prior commit stamp, the two tracked suites and their stored mapping - and clear the system
     * properties the Maven/JUnit handoff publishes.
     *
     * @throws Exception if the store cannot be opened or seeded
     */
    @BeforeEach
    void setUp() throws Exception {
        savedProperties = new LinkedHashMap<>();
        for (String key : MANAGED_PROPERTIES) {
            savedProperties.put(key, System.getProperty(key));
            System.clearProperty(key);
        }

        dataStore = openStore();
        dataStore.getTiaData(true);

        TiaData tiaData = dataStore.getTiaCore();
        tiaData.setCommitValue(PRIOR_COMMIT);
        tiaData.setBranch(BRANCH);
        tiaData.setLastUpdated(Instant.now());
        dataStore.persistCoreData(tiaData);

        // The mapping a previous build left behind: each suite reaches one method nothing else
        // reaches, which is what makes the catalogue assertion at the end meaningful.
        Map<String, TestSuiteTracker> tracked = new LinkedHashMap<>();
        tracked.put(SUITE_A, suiteTracker(SUITE_A, "com/example/A.java", METHOD_A));
        tracked.put(SUITE_B, suiteTracker(SUITE_B, "com/example/B.java", METHOD_B));
        dataStore.persistTestSuites(tracked);

        Map<Integer, MethodImpactTracker> catalogue = new HashMap<>();
        catalogue.put(Integer.valueOf(METHOD_A), new MethodImpactTracker("com/example/A.a.()V", 1, 5));
        catalogue.put(Integer.valueOf(METHOD_B), new MethodImpactTracker("com/example/B.b.()V", 1, 5));
        dataStore.persistSourceMethods(catalogue);
    }

    /**
     * Drop anything a test recorded for a runner's JVM exit, close the shared datastore, remove the
     * temp directory if the fixture created one, and restore the system properties saved in
     * {@link #setUp()}.
     */
    @AfterEach
    void tearDown() {
        DistributedRunCompletion.discardPendingCompletions();
        if (dataStore != null) {
            dataStore.close();
        }
        if (tempDir != null && tempDir.exists()) {
            File[] files = tempDir.listFiles();
            if (files != null) {
                for (File file : files) {
                    file.delete();
                }
            }
            tempDir.delete();
        }
        for (Map.Entry<String, String> entry : savedProperties.entrySet()) {
            if (entry.getValue() == null) {
                System.clearProperty(entry.getKey());
            } else {
                System.setProperty(entry.getKey(), entry.getValue());
            }
        }
    }

    /**
     * Build a stored suite tracker covering one method of one source file.
     *
     * @param suiteName the suite's name
     * @param sourceFile the covered source file's mapping key
     * @param methodId the covered method's id
     * @return the tracker to persist
     */
    private static TestSuiteTracker suiteTracker(final String suiteName, final String sourceFile,
                                                  final int methodId) {
        TestSuiteTracker tracker = new TestSuiteTracker(suiteName);
        tracker.setClassesImpacted(Collections.singletonList(new ClassImpactTracker(sourceFile,
                new HashSet<>(Collections.singletonList(Integer.valueOf(methodId))))));
        return tracker;
    }

    /**
     * Plan the build the way {@code tia-dist-plan} does: two groups, one suite each, pinned to the
     * commit the runners' workspaces are on.
     */
    private void planTheBuild() {
        List<DistributedRunGroup> groups = new ArrayList<>();
        groups.add(DistributedRunGroup.pending(RUN_ID, 0, 1000L));
        groups.add(DistributedRunGroup.pending(RUN_ID, 1, 1000L));
        Map<Integer, List<String>> suitesByGroup = new HashMap<>();
        suitesByGroup.put(0, Collections.singletonList(SUITE_A));
        suitesByGroup.put(1, Collections.singletonList(SUITE_B));
        dataStore.persistDistributedRunPlan(new DistributedRunPlan(
                DistributedRun.open(RUN_ID, BRANCH, PLAN_COMMIT, 2, null, 2000L, 1200L),
                groups, suitesByGroup, null));
    }

    /**
     * Claim a group the way both build tools do, through the shared assignment.
     *
     * @param runnerKey the identity to claim under
     * @return the runner's assignment
     */
    private DistributedRunnerAssignment claim(final String runnerKey) {
        DistributedRunnerAssignment assignment = DistributedRunnerAssignment.claim(dataStore,
                DistributedRunConfig.forRunner(RUN_ID, runnerKey), PLAN_COMMIT,
                System.currentTimeMillis());
        assertTrue(assignment.isClaimed(), "the plan must have a group left for " + runnerKey);
        return assignment;
    }

    /**
     * Carry a claim across the Maven boundary for real: write the fork properties file the goal
     * writes, publish it as the Tia agent's {@code premain} does, and resolve the context from the
     * system properties exactly as the JUnit listener does.
     *
     * @param assignment the claim the build JVM made
     * @return the context the forked test JVM's listener would persist under
     * @throws IOException if the fork properties file cannot be written or read
     */
    private DistributedRunnerContext throughTheMavenForkBoundary(
            final DistributedRunnerAssignment assignment) throws IOException {
        File forkProperties = File.createTempFile("tia-fork-", ".properties");
        forkProperties.deleteOnExit();
        ForkSystemProperties.write(DistributedForkProperties.forkProperties(RUN_ID,
                assignment.getRunnerKey(), assignment.getGroupNumber()), forkProperties);
        for (String key : MANAGED_PROPERTIES) {
            System.clearProperty(key);
        }
        ForkSystemProperties.applyToSystemProperties(forkProperties.getAbsolutePath());
        return DistributedForkProperties.contextFromSystemProperties();
    }

    /**
     * Build the result one runner reports: its own suite's coverage of the one method only that
     * suite reaches, plus the full discovered suite set every runner sees. A runner that reported
     * only its own group's suites would have the persist treat the other group's as deleted.
     *
     * @param suiteName the suite this runner executed
     * @param sourceFile the source file it covered
     * @param methodId the covered method's id
     * @param methodName the covered method's name
     * @return the result to hand to the persist
     */
    private TestRunResult runResultFor(final String suiteName, final String sourceFile,
                                        final int methodId, final String methodName) {
        Map<String, TestSuiteTracker> trackers = new HashMap<>();
        trackers.put(suiteName, suiteTracker(suiteName, sourceFile, methodId));

        Map<Integer, MethodImpactTracker> methodTrackers = new HashMap<>();
        // Fresh line numbers, so a method resolved from staging is distinguishable from one carried
        // forward off the stored catalogue's seeded 1-5.
        methodTrackers.put(Integer.valueOf(methodId), new MethodImpactTracker(methodName, 40, 50));

        return new TestRunResult(trackers, new HashSet<String>(), discoveredSuites(),
                new HashSet<>(Collections.singletonList(suiteName)), methodTrackers, new TestStats(),
                null, 1, 1);
    }

    /**
     * Build the result the first runner's <em>second</em> test plan reports - the shape a Surefire
     * retry produces. It is cumulative, as the per-JVM shared run data makes it: the suite the
     * first test plan ran is still in it, and a suite whose coverage only this test plan saw has
     * been added.
     *
     * @return the result to hand to the second persist of the first runner's JVM
     */
    private TestRunResult retryRunResultForTheFirstRunner() {
        Map<String, TestSuiteTracker> trackers = new HashMap<>();
        trackers.put(SUITE_A, suiteTracker(SUITE_A, "com/example/A.java", METHOD_A));
        trackers.put(SUITE_RETRY, suiteTracker(SUITE_RETRY, "com/example/RetryOnly.java",
                METHOD_RETRY));

        Map<Integer, MethodImpactTracker> methodTrackers = new HashMap<>();
        methodTrackers.put(Integer.valueOf(METHOD_A),
                new MethodImpactTracker("com/example/A.a.()V", 40, 50));
        methodTrackers.put(Integer.valueOf(METHOD_RETRY),
                new MethodImpactTracker("com/example/RetryOnly.r.()V", 40, 50));

        return new TestRunResult(trackers, new HashSet<String>(), discoveredSuites(),
                new HashSet<>(Arrays.asList(SUITE_A, SUITE_RETRY)), methodTrackers, new TestStats(),
                null, 1, 2);
    }

    /**
     * The suites every runner in this build discovers, whichever ones its own group executes. The
     * retry-only suite is among them from the start, since discovery is a scan of the test classes
     * and does not depend on which test plan happens to run one.
     *
     * @return the discovered suite names
     */
    private Set<String> discoveredSuites() {
        return new HashSet<>(Arrays.asList(SUITE_A, SUITE_B, SUITE_RETRY));
    }

    /**
     * Drive a whole distributed build through the wiring both build tools use, and hold it to the
     * things the stage exists to guarantee: one seal, one history row, a method reachable only from
     * the last group to finish still in the catalogue, and a method reachable only from a
     * <em>later test plan of an earlier group</em> still in it too.
     *
     * <p>The first runner takes the Maven/JUnit route, its claim crossing a real fork properties
     * file; the second takes the Gradle/Spock route, converting the assignment it already holds.
     * Between the two persists the build is checked to be untouched: with the wiring broken, the
     * first runner would have sealed there and the method only the second group reaches would be
     * gone from the catalogue for good.
     *
     * <p>The first runner deliberately persists <b>twice</b>, which is what a Surefire retry of
     * failed tests produces: a second test plan in the same JVM, so a second persist. Complete the
     * group in the persist and the first of those two test plans releases the barrier - the second
     * then finds its claim dead, skips every write it had, and {@link #SUITE_RETRY}'s coverage
     * never reaches the edge table the catalogue is rebuilt from. Its method is asserted at the end
     * for exactly that reason.
     *
     * @throws Exception if the fork properties handoff fails
     */
    @Test
    void shouldSealOnceAndKeepTheLastGroupsMethodWhenBothRunnersUseTheRealWiring() throws Exception {
        // given - a planned build and two runners, each claiming its own group
        planTheBuild();
        TestRunnerService service = new TestRunnerService(dataStore);
        DistributedRunnerContext mavenRunner = throughTheMavenForkBoundary(claim("runner-a"));
        DistributedRunnerAssignment gradleAssignment = claim("runner-b");
        DistributedRunnerContext gradleRunner = gradleAssignment.toRunnerContext(RUN_ID);

        // when - the Maven runner's first test plan finishes and persists its share
        service.persistTestRunData(true, true, true, PLAN_COMMIT, BRANCH,
                System.currentTimeMillis() - 1000L,
                runResultFor(SUITE_A, "com/example/A.java", METHOD_A, "com/example/A.a.()V"),
                mavenRunner);

        // then - nothing may be sealed while the other group is still running
        assertEquals(PRIOR_COMMIT, dataStore.getTiaCore().getCommitValue(),
                "no runner may advance the stored commit while a group is still running");
        assertEquals(DistributedRunStatus.OPEN, dataStore.readDistributedRun(RUN_ID).getStatus(),
                "the run must stay open until every group has finished");
        assertTrue(dataStore.readTestRunHistory().isEmpty(),
                "a distributed runner writes no history row of its own");

        // when - Surefire retries in the same JVM, so a second test plan finishes and persists,
        //        carrying a suite the first test plan never covered. Then that JVM exits.
        service.persistTestRunData(true, true, true, PLAN_COMMIT, BRANCH,
                System.currentTimeMillis() - 1500L, retryRunResultForTheFirstRunner(), mavenRunner);
        DistributedRunCompletion.completePendingCompletions();

        // then - still nothing sealed, since the second group has not finished
        assertEquals(PRIOR_COMMIT, dataStore.getTiaCore().getCommitValue(),
                "the first runner's JVM exiting must not seal a build whose other group is running");

        // when - the Gradle runner finishes last, persists, and its JVM exits
        service.persistTestRunData(true, true, true, PLAN_COMMIT, BRANCH,
                System.currentTimeMillis() - 2000L,
                runResultFor(SUITE_B, "com/example/B.java", METHOD_B, "com/example/B.b.()V"),
                gradleRunner);
        DistributedRunCompletion.completePendingCompletions();

        // then - exactly one seal, by the runner that finished last
        DistributedRun run = dataStore.readDistributedRun(RUN_ID);
        assertEquals(DistributedRunStatus.SEALED, run.getStatus());
        assertEquals("runner-b", run.getSealedBy(),
                "the runner that finished last must be the one that sealed");
        assertEquals(PLAN_COMMIT, dataStore.getTiaCore().getCommitValue(),
                "the seal must advance the stored commit for the whole build");

        // then - exactly one history row for the build, the aggregated one
        List<TestRunHistoryEntry> history = dataStore.readTestRunHistory();
        assertEquals(1, history.size(), "one distributed build produces one history row: " + history);
        assertEquals(RUN_ID, history.get(0).getRunId());
        assertEquals(3, history.get(0).getNumSuitesRan(),
                "the row must count what both groups ran, taking the first group's figure from its "
                        + "last test plan rather than its first");
        assertEquals(Integer.valueOf(2), history.get(0).getGroupCount());

        // then - the method reachable only from the group that finished last survives
        Map<Integer, MethodImpactTracker> catalogue = dataStore.getMethodsTracked();
        assertNotNull(catalogue.get(Integer.valueOf(METHOD_B)),
                "the method reachable only from the last group to finish must survive the seal. "
                        + "Catalogue: " + catalogue);
        assertNotNull(catalogue.get(Integer.valueOf(METHOD_A)),
                "the first group's method must survive the seal. Catalogue: " + catalogue);
        assertEquals(40, catalogue.get(Integer.valueOf(METHOD_B)).getLineNumberStart(),
                "the last group's staged line numbers must be the ones the seal wrote");

        // then - and so does the method reachable only from the first runner's second test plan,
        //        which is the write a group completed by its first test plan would have skipped
        assertNotNull(catalogue.get(Integer.valueOf(METHOD_RETRY)),
                "the method covered only by the first runner's retry must survive the seal. "
                        + "Catalogue: " + catalogue);
        assertTrue(dataStore.getTestSuitesTracked().containsKey(SUITE_RETRY),
                "the suite the first runner's retry covered must be mapped. Tracked: "
                        + dataStore.getTestSuitesTracked().keySet());

        // then - both groups are recorded complete, and the staging table is cleared
        for (DistributedRunGroup group : dataStore.readDistributedRunGroups(RUN_ID)) {
            assertEquals(DistributedRunGroupStatus.COMPLETED, group.getStatus(),
                    "group " + group.getGroupNumber() + " must be complete");
        }
        assertTrue(dataStore.readStagedMethodTrackers(RUN_ID).isEmpty(),
                "the sealer must clear the staging rows it consumed");
    }

    /**
     * Verify a surplus runner - one whose build JVM found every group claimed - persists nothing at
     * all and does not seal, even though it is carried through the same Maven fork boundary as a
     * claimed runner. Its context holds no group; had the wiring given it a null one instead it
     * would have taken the single-host path and sealed a build still in flight.
     *
     * @throws Exception if the fork properties handoff fails
     */
    @Test
    void shouldPersistNothingForASurplusRunnerCarriedThroughTheSameWiring() throws Exception {
        // given - both groups claimed, then a third runner arrives
        planTheBuild();
        claim("runner-a");
        claim("runner-b");
        DistributedRunnerAssignment surplus = DistributedRunnerAssignment.claim(dataStore,
                DistributedRunConfig.forRunner(RUN_ID, "runner-c"), PLAN_COMMIT,
                System.currentTimeMillis());
        DistributedRunnerContext context = throughTheMavenForkBoundary(surplus);

        // when
        new TestRunnerService(dataStore).persistTestRunData(true, true, true, PLAN_COMMIT, BRANCH,
                System.currentTimeMillis(),
                runResultFor(SUITE_A, "com/example/A.java", METHOD_A, "com/example/A.a.()V"),
                context);

        // then
        assertNotNull(context, "a surplus runner must still resolve a distributed context");
        assertEquals(PRIOR_COMMIT, dataStore.getTiaCore().getCommitValue(),
                "a runner that ran nothing must not advance the stored commit");
        assertEquals(DistributedRunStatus.OPEN, dataStore.readDistributedRun(RUN_ID).getStatus(),
                "a surplus runner must not seal a build whose runners are still going");
        assertTrue(dataStore.readTestRunHistory().isEmpty(),
                "a surplus runner writes no history row");
        assertNull(dataStore.readDistributedRunGroups(RUN_ID).get(0).getCompletedAtMs(),
                "a surplus runner must not complete anybody's group");
    }
}
