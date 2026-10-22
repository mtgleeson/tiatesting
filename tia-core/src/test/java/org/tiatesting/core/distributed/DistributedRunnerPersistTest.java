package org.tiatesting.core.distributed;

import org.tiatesting.core.model.SelectionMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.Opcodes;
import org.tiatesting.core.model.DistributedRun;
import org.tiatesting.core.model.DistributedRunGroup;
import org.tiatesting.core.model.DistributedRunPlan;
import org.tiatesting.core.persistence.BranchSchema;
import org.tiatesting.core.persistence.DataStore;
import org.tiatesting.core.persistence.JdbcDataStore;
import org.tiatesting.core.persistence.connection.H2ConnectionProvider;
import org.tiatesting.core.persistence.dialect.H2Dialect;
import org.tiatesting.core.persistence.h2.H2ConnectionSettings;
import org.tiatesting.core.testrunner.ClassFileFixture;
import org.tiatesting.core.testrunner.TestSuiteRunnabilityClassifier;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cover the runner-side persist operations directly, against a real embedded H2 store: the claim
 * re-verification that decides whether a runner may write at all, and the late guard on the
 * completion that catches a supersession landing after that check.
 * {@code TestRunnerServiceDistributedPersistTest} covers how the persist flow uses them.
 */
class DistributedRunnerPersistTest {

    private static final String RUN_ID = "run-1";
    private static final Set<String> SUITE_0 = Collections.singleton("com.example.Suite0Test");
    private static final String RUNNER_KEY = "runner-a";

    private JdbcDataStore dataStore;
    private File tempDir;

    /**
     * Create a fresh embedded H2 store in its own temp directory so each test starts with no
     * distributed run planned.
     *
     * @throws Exception if the temp directory cannot be created or the schema bootstrap fails
     */
    @BeforeEach
    void setUp() throws Exception {
        tempDir = File.createTempFile("tia-runner-persist-", "");
        tempDir.delete();
        tempDir.mkdirs();
        dataStore = new JdbcDataStore(new H2Dialect(),
                new H2ConnectionProvider(H2ConnectionSettings.embedded(tempDir.getAbsolutePath())),
                BranchSchema.schemaName("test", null));
        dataStore.getTiaData();
    }

    /**
     * Close the store so embedded H2 releases its file lock, then remove the temp directory.
     */
    @AfterEach
    void tearDown() {
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
    }

    /**
     * A runner still holding its claim is cleared to write. This is the case every healthy runner
     * in a build takes, so it is asserted explicitly rather than inferred from the failure cases.
     */
    @Test
    void claimIsLiveForTheRunnerHoldingTheGroup() {
        // given
        persistPlan(RUN_ID, 2);
        dataStore.claimNextPendingGroup(RUN_ID, RUNNER_KEY, 5000L);

        // when
        boolean live = persistFor(0).claimIsLive();

        // then
        assertTrue(live, "the runner holding the claim must be cleared to write its mapping rows");
    }

    /**
     * A runner whose run was superseded - a newer build's plan write cleared these rows - is not
     * cleared to write. Writing anyway would leave rows describing this build's older commit under
     * the commit the newer build stores.
     */
    @Test
    void claimIsNotLiveWhenTheRunWasSuperseded() {
        // given
        persistPlan(RUN_ID, 2);
        dataStore.claimNextPendingGroup(RUN_ID, RUNNER_KEY, 5000L);
        persistPlan("run-2", 1);

        // when
        boolean live = persistFor(0).claimIsLive();

        // then
        assertFalse(live, "a runner whose plan rows are gone must not write");
    }

    /**
     * A runner asking about a group another runner key holds is not cleared to write either: the
     * guard is on the claim's identity, not merely on the run still existing.
     */
    @Test
    void claimIsNotLiveWhenAnotherRunnerHoldsTheGroup() {
        // given
        persistPlan(RUN_ID, 2);
        dataStore.claimNextPendingGroup(RUN_ID, "runner-b", 5000L);

        // when
        boolean live = persistFor(0).claimIsLive();

        // then
        assertFalse(live, "a runner that does not hold the group must not write");
    }

    /**
     * A group already completed is not live, so a second persist by the same runner (a listener
     * firing twice, say) does not re-open it or write its mapping rows a second time.
     */
    @Test
    void claimIsNotLiveOnceTheGroupIsCompleted() {
        // given
        persistPlan(RUN_ID, 2);
        dataStore.claimNextPendingGroup(RUN_ID, RUNNER_KEY, 5000L);
        DistributedRunnerPersist runnerPersist = persistFor(0);
        assertTrue(runnerPersist.reportGroupProgress(1000L, 2, 0, SUITE_0, 0L));
        assertNotNull(runnerPersist.completeGroup(6000L, TestSuiteRunnabilityClassifier.disabled()));

        // when
        boolean live = persistFor(0).claimIsLive();

        // then
        assertFalse(live, "an already-completed group must not be written to again");
    }

    /**
     * The late guard: a supersession that lands after {@code claimIsLive} passed is caught by the
     * completion's own guard, which returns null. The group is then never marked complete, so the
     * superseded run can never elect a sealer and can never advance the stored commit value.
     */
    @Test
    void completeGroupReturnsNullWhenTheClaimDiedAfterTheCheck() {
        // given
        persistPlan(RUN_ID, 2);
        dataStore.claimNextPendingGroup(RUN_ID, RUNNER_KEY, 5000L);
        DistributedRunnerPersist runnerPersist = persistFor(0);
        assertTrue(runnerPersist.claimIsLive());
        persistPlan("run-2", 1);

        // when
        DistributedRunGroup completed = runnerPersist.completeGroup(6000L,
                TestSuiteRunnabilityClassifier.disabled());

        // then
        assertNull(completed, "a completion whose claim died must report it rather than appear to succeed");
    }

    /**
     * The dead-claim requirement, driven through the persist wrapper rather than the raw data store: a
     * progress report on a group whose claim has already died - the run was superseded between the
     * claim re-verification and this call - writes nothing and says so via its boolean return,
     * exactly mirroring what a failed {@link DistributedRunnerPersist#completeGroup(long)} reports.
     */
    @Test
    void reportGroupProgressWritesNothingWhenTheClaimHasDied() {
        // given
        persistPlan(RUN_ID, 2);
        dataStore.claimNextPendingGroup(RUN_ID, RUNNER_KEY, 5000L);
        DistributedRunnerPersist runnerPersist = persistFor(0);
        assertTrue(runnerPersist.claimIsLive());
        persistPlan("run-2", 1);

        // when
        boolean applied = runnerPersist.reportGroupProgress(1000L, 2, 0, SUITE_0, 0L);

        // then
        assertFalse(applied, "a progress report on a dead claim must report failure rather than "
                + "appear to succeed");
    }

    /**
     * A rejected completion says which of its causes actually happened. The run being superseded -
     * a newer build's plan write having cleared these rows - is the case a reader must be able to
     * tell apart from the others, since it is the only one that is business as usual for a
     * straggler and not a sign of something wrong with the run.
     */
    @Test
    void aRejectedCompletionReportsThatTheRunWasSuperseded() {
        // given
        persistPlan(RUN_ID, 2);
        dataStore.claimNextPendingGroup(RUN_ID, RUNNER_KEY, 5000L);
        persistPlan("run-2", 1);

        // when
        String description = persistFor(0).describeRejectedCompletion(Collections.<String>emptySet());

        // then
        assertTrue(description.contains("superseded"),
                "a completion rejected because the run's rows are gone must say so, was: " + description);
    }

    /**
     * A completion rejected because another runner now holds the group names that runner, so the
     * log points at the identity that took it rather than leaving a reader to guess.
     */
    @Test
    void aRejectedCompletionNamesTheRunnerThatNowHoldsTheGroup() {
        // given
        persistPlan(RUN_ID, 2);
        dataStore.claimNextPendingGroup(RUN_ID, "runner-b", 5000L);

        // when
        String description = persistFor(0).describeRejectedCompletion(Collections.<String>emptySet());

        // then
        assertTrue(description.contains("runner-b"),
                "a completion rejected because another runner holds the group must name it, was: "
                        + description);
    }

    /**
     * A completion rejected because this same runner key already completed the group says exactly
     * that, rather than reporting a supersession that did not happen. The two look identical from
     * the guarded write's row count and only the group row tells them apart.
     */
    @Test
    void aRejectedCompletionReportsAGroupThisRunnerAlreadyCompleted() {
        // given
        persistPlan(RUN_ID, 2);
        dataStore.claimNextPendingGroup(RUN_ID, RUNNER_KEY, 5000L);
        DistributedRunnerPersist runnerPersist = persistFor(0);
        assertTrue(runnerPersist.reportGroupProgress(1000L, 2, 0, SUITE_0, 0L));
        assertNotNull(runnerPersist.completeGroup(6000L, TestSuiteRunnabilityClassifier.disabled()));

        // when
        String description = persistFor(0).describeRejectedCompletion(Collections.<String>emptySet());

        // then
        assertTrue(description.contains("already"),
                "a group this runner already completed must be reported as such, was: " + description);
        assertFalse(description.contains("superseded"),
                "a group this runner completed normally was not superseded, was: " + description);
    }

    /**
     * A completion rejected because the group has not yet observed enough of its assigned suites
     * says so with the observed-versus-assigned counts, so a reader is not left guessing whether
     * the completeness guard or the straggler guard is what actually blocked it - the two miss the
     * same {@code WHERE} clause and only the group row (plus the assigned suite count) tells them
     * apart. Asserts the exact "0 of 1" substring rather than the two digits independently, so the
     * assertion cannot pass with the counts swapped or embedded in unrelated text.
     */
    @Test
    void aRejectedCompletionReportsTheObservedVersusAssignedCountsWhenTheGroupIsIncomplete() {
        // given - one suite assigned to the group, and no progress reported at all
        persistPlan(RUN_ID, 2);
        dataStore.claimNextPendingGroup(RUN_ID, RUNNER_KEY, 5000L);
        DistributedRunnerPersist runnerPersist = persistFor(0);
        assertNull(runnerPersist.completeGroup(6000L, TestSuiteRunnabilityClassifier.disabled()),
                "test setup expects the completion to be rejected as incomplete");

        // when
        String description = runnerPersist.describeRejectedCompletion(Collections.<String>emptySet());

        // then
        assertTrue(description.contains("0 of 1"),
                "the rejection must name the observed-versus-assigned counts (0 of 1), was: "
                        + description);
        assertFalse(description.contains("superseded"),
                "a group this runner still holds was not superseded, was: " + description);
        assertFalse(description.contains("already"),
                "a group never completed was not completed twice, was: " + description);
    }

    /**
     * <b>The case this completion step exists for.</b> A group assigned three real suites and one
     * helper class that a new file added under the test dir - with no test methods, so the engine
     * never discovers it - observes the three. The helper is classified as never runnable, named in
     * the WARN, and the group completes.
     *
     * @param classesDir the temp test-classes dir the classifier reads
     * @throws Exception if a fixture class file cannot be written
     */
    @Test
    void completesWhenTheOnlyUnobservedSuiteIsAHelperWithNoTestMethods(@TempDir Path classesDir)
            throws Exception {
        // given - 3 real suites observed, plus a helper the engine never discovers
        persistSingleGroupPlan(RUN_ID, SelectionMode.SELECTIVE, Arrays.asList("com.example.FooApiClient",
                "com.example.Suite0Test", "com.example.Suite1Test", "com.example.Suite2Test"));
        dataStore.claimNextPendingGroup(RUN_ID, RUNNER_KEY, 5000L);
        DistributedRunnerPersist runnerPersist = persistFor(0);
        assertTrue(runnerPersist.reportGroupProgress(1000L, 3, 0, suites(0, 3), 0L));
        ClassFileFixture.classIn(classesDir, "com.example.FooApiClient").method("fetch", null).write();

        // when
        String[] log = new String[1];
        DistributedRunGroup completed = captureLog(log, () -> runnerPersist.completeGroup(6000L,
                classifierOver(classesDir)));

        // then
        assertNotNull(completed, "the only suite not observed can never run, so the group is complete");
        assertTrue(log[0].contains("1 assigned suite(s) are not runnable tests and are counted as observed: "
                        + "[com.example.FooApiClient (no test methods)]"),
                "the WARN must name the excused suite and why, was: " + log[0]);
    }

    /**
     * <b>Crash safety is kept.</b> A real test that was never observed - its runner died before
     * reaching it - is not excused, so the group stays open, and the ERROR names the missing suite.
     *
     * @param classesDir the temp test-classes dir the classifier reads
     * @throws Exception if a fixture class file cannot be written
     */
    @Test
    void refusesAndNamesTheMissingSuiteWhenARealTestWasNotObserved(@TempDir Path classesDir)
            throws Exception {
        // given - 3 real suites, 2 observed; the third has a @Test method
        persistSingleGroupPlan(RUN_ID, SelectionMode.SELECTIVE, new ArrayList<>(suites(0, 3)));
        dataStore.claimNextPendingGroup(RUN_ID, RUNNER_KEY, 5000L);
        DistributedRunnerPersist runnerPersist = persistFor(0);
        assertTrue(runnerPersist.reportGroupProgress(1000L, 2, 0, suites(0, 2), 0L));
        ClassFileFixture.classIn(classesDir, "com.example.Suite2Test")
                .method("works", "org.junit.jupiter.api.Test").write();

        // when
        String[] log = new String[1];
        DistributedRunGroup completed = captureLog(log, () -> runnerPersist.completeGroup(6000L,
                classifierOver(classesDir)));

        // then
        assertNull(completed, "a real test the runner never reached must keep the group open");
        assertTrue(log[0].contains("not observed: [com.example.Suite2Test]"),
                "the ERROR must name the missing suite, was: " + log[0]);
    }

    /**
     * With some suites excused and a real one still missing, the refusal reports both, so a reader
     * sees that the excused ones were not the problem.
     *
     * @param classesDir the temp test-classes dir the classifier reads
     * @throws Exception if a fixture class file cannot be written
     */
    @Test
    void aRefusalNamesTheExcusedCountApartFromTheMissingSuites(@TempDir Path classesDir) throws Exception {
        // given - an abstract base excused, a real test missing
        persistSingleGroupPlan(RUN_ID, SelectionMode.SELECTIVE, Arrays.asList("com.example.AbstractBase",
                "com.example.Suite0Test", "com.example.Suite1Test"));
        dataStore.claimNextPendingGroup(RUN_ID, RUNNER_KEY, 5000L);
        DistributedRunnerPersist runnerPersist = persistFor(0);
        assertTrue(runnerPersist.reportGroupProgress(1000L, 1, 0, suites(0, 1), 0L));
        ClassFileFixture.classIn(classesDir, "com.example.AbstractBase").access(Opcodes.ACC_ABSTRACT).write();
        ClassFileFixture.classIn(classesDir, "com.example.Suite1Test")
                .method("works", "org.junit.jupiter.api.Test").write();

        // when
        String[] log = new String[1];
        DistributedRunGroup completed = captureLog(log, () -> runnerPersist.completeGroup(6000L,
                classifierOver(classesDir)));

        // then
        assertNull(completed);
        assertTrue(log[0].contains("[com.example.AbstractBase (abstract class)]"),
                "the WARN must still name the excused suite, was: " + log[0]);
        assertTrue(log[0].contains("observed only 1 of 3 assigned suite(s) so far (1 more are not runnable "
                        + "tests), so the group is not complete enough to close - not observed: "
                        + "[com.example.Suite1Test]"),
                "the ERROR must count the excused suite apart from the missing one, was: " + log[0]);
    }

    /**
     * A disabled classifier excuses nothing: the helper that would have been excused keeps the group
     * open, so an unknown answer never completes a group.
     */
    @Test
    void refusesWhenTheClassifierIsDisabled() {
        // given - the same helper-only gap as the completing case
        persistSingleGroupPlan(RUN_ID, SelectionMode.SELECTIVE, Arrays.asList("com.example.FooApiClient",
                "com.example.Suite0Test"));
        dataStore.claimNextPendingGroup(RUN_ID, RUNNER_KEY, 5000L);
        DistributedRunnerPersist runnerPersist = persistFor(0);
        assertTrue(runnerPersist.reportGroupProgress(1000L, 1, 0, suites(0, 1), 0L));

        // when
        DistributedRunGroup completed = runnerPersist.completeGroup(6000L,
                TestSuiteRunnabilityClassifier.disabled());

        // then
        assertNull(completed);
    }

    /**
     * A full run classifies nothing - its assignment is a disk-scan superset and its guard is already
     * loosened - so it never reads a class file. The unobserved suite's class file here is corrupt,
     * which would throw if it were read.
     *
     * @param classesDir the temp test-classes dir the classifier would read
     * @throws Exception if the fixture file cannot be written
     */
    @Test
    void aFullRunCompletesWithoutClassifyingAnything(@TempDir Path classesDir) throws Exception {
        // given - a seed run with 2 suites, 1 observed, the other's class file corrupt
        persistSingleGroupPlan(RUN_ID, SelectionMode.SEED, new ArrayList<>(suites(0, 2)));
        dataStore.claimNextPendingGroup(RUN_ID, RUNNER_KEY, 5000L);
        DistributedRunnerPersist runnerPersist = persistFor(0);
        assertTrue(runnerPersist.reportGroupProgress(1000L, 1, 0, suites(0, 1), 0L));
        Path corrupt = classesDir.resolve("com/example/Suite1Test.class");
        Files.createDirectories(corrupt.getParent());
        Files.write(corrupt, new byte[]{1, 2, 3});

        // when
        DistributedRunGroup completed = runnerPersist.completeGroup(6000L, classifierOver(classesDir));

        // then
        assertNotNull(completed, "a full run's loosened guard needs only one observed suite");
    }

    /**
     * A refusal on a group missing many suites names the first 20 and counts the rest, so the log
     * line stays readable.
     */
    @Test
    void aRefusalNamesAtMostTwentyMissingSuites() {
        // given - 25 assigned, none observed
        persistSingleGroupPlan(RUN_ID, SelectionMode.SELECTIVE, new ArrayList<>(suites(0, 25)));
        dataStore.claimNextPendingGroup(RUN_ID, RUNNER_KEY, 5000L);
        DistributedRunnerPersist runnerPersist = persistFor(0);

        // when
        String description = runnerPersist.describeRejectedCompletion(Collections.<String>emptySet());

        // then
        assertTrue(description.endsWith(" and 5 more"), "was: " + description);
        assertEquals(20, description.split("com\\.example\\.Suite").length - 1,
                "exactly 20 suites are named, was: " + description);
    }

    /**
     * A rejected completion on a seed run says the group observed nothing, rather than printing the
     * "observed N of M assigned" fraction: on a seed run M is a disk-scan count that includes
     * non-test classes, so the fraction would misrepresent a group that simply ran nothing.
     */
    @Test
    void aRejectedSeedCompletionReportsThatTheGroupObservedNothing() {
        // given - a seed run whose group has observed no suites
        persistSeedPlan(RUN_ID, 2);
        dataStore.claimNextPendingGroup(RUN_ID, RUNNER_KEY, 5000L);
        DistributedRunnerPersist runnerPersist = persistFor(0);
        assertNull(runnerPersist.completeGroup(6000L, TestSuiteRunnabilityClassifier.disabled()),
                "a seed group that observed nothing must be rejected as incomplete");

        // when
        String description = runnerPersist.describeRejectedCompletion(Collections.<String>emptySet());

        // then
        assertTrue(description.contains("seed run"),
                "a seed run's rejection must say it is a seed run, was: " + description);
        assertFalse(description.contains(" of "),
                "a seed run's rejection must not print the observed-of-assigned fraction, was: "
                        + description);
    }

    /**
     * The accumulation contract, exercised through the persist wrapper: two progress reports
     * in the same JVM - the second reporting fewer distinct suites ran than the first, as a Gradle
     * test-retry round's fresh JVM would - sum the duration but keep the greater ran count: the ran
     * count is the distinct suites the runner executed, so a retry can re-report the same suites or
     * fewer but never adds new executions of them. The failed set is replaced outright by the later
     * report, since it is current state. The observed count is the number of suite rows the reports
     * have flagged observed (see {@link DataStore#reportGroupProgress}), so a retry naming the same
     * suites again adds nothing and naming more adds only the new ones. The ran and observed totals are deliberately kept different
     * numbers below (50 vs 53) so a getter/column transposition between the two would fail this test
     * - a shared value could not tell them apart.
     */
    @Test
    void reportGroupProgressSumsDurationReplacesFailedAndTakesTheGreaterRanAndObservedCounts() {
        // given
        persistPlanWithOneGroupOfSuites(RUN_ID, 60);
        dataStore.claimNextPendingGroup(RUN_ID, RUNNER_KEY, 5000L);
        DistributedRunnerPersist runnerPersist = persistFor(0);

        // when - the first test plan reports 50 distinct suites run (all 50 observed) with 3
        // failures; the retry reports the 2 suites it re-ran while the observed count - the JVM's
        // cumulative total, not this call's own contribution - reaches 53
        assertTrue(runnerPersist.reportGroupProgress(4000L, 50, 3, suites(0, 50), 0L));
        assertTrue(runnerPersist.reportGroupProgress(500L, 2, 0, suites(0, 53), 0L));

        // then
        DistributedRunGroup group = readGroup(RUN_ID, 0);
        assertEquals(50, group.getSuitesRan(), "a retry's smaller distinct count must not replace or add to the first");
        assertEquals(4500L, group.getActualDurationMs().longValue(),
                "the duration must sum across both reports");
        assertEquals(0, group.getSuitesFailed(),
                "the failed count must reflect only the later report, not accumulate onto the first");
        assertEquals(53, group.getSuitesObserved(),
                "the observed count must be the union of the suites the reports named, not the sum "
                        + "of the two reports' sizes");
    }

    /**
     * A surplus runner's context is rejected at construction: it holds no group, so there is
     * nothing for it to verify, stage or complete, and quietly accepting one would mean a caller
     * had asked for writes that cannot be keyed to any group.
     */
    @Test
    void aSurplusRunnerContextIsRejected() {
        // given
        DistributedRunnerContext surplus = DistributedRunnerContext.surplusRunner(RUN_ID, RUNNER_KEY);

        // when / then
        assertThrows(IllegalArgumentException.class,
                () -> new DistributedRunnerPersist(dataStore, surplus));
    }

    /**
     * Build the persist operations for a claimed group of the test's run.
     *
     * @param groupNumber the group this runner holds
     * @return the runner-side persist operations bound to the test's store
     */
    private DistributedRunnerPersist persistFor(final int groupNumber) {
        return new DistributedRunnerPersist(dataStore,
                DistributedRunnerContext.forClaimedGroup(RUN_ID, RUNNER_KEY, groupNumber));
    }

    /**
     * Read one group of a run back from the store, so a test can assert what a guarded update
     * actually left on disk.
     *
     * @param runId the run the group belongs to
     * @param groupNumber the group's zero-based index within the run
     * @return the stored group
     */
    private DistributedRunGroup readGroup(final String runId, final int groupNumber) {
        for (DistributedRunGroup group : dataStore.readDistributedRunGroups(runId)) {
            if (group.getGroupNumber() == groupNumber) {
                return group;
            }
        }
        throw new IllegalStateException("no group " + groupNumber + " in run " + runId);
    }

    /**
     * Build and persist a single-group plan assigning the given suites under the given selection
     * mode, for completion tests that need exact suite names.
     *
     * @param runId the run identifier to plan under
     * @param mode the run's selection mode; a full-run mode loosens the completeness guard
     * @param suiteNames the suites to assign to group 0
     */
    private void persistSingleGroupPlan(final String runId, final SelectionMode mode,
                                        final List<String> suiteNames) {
        Map<Integer, List<String>> suites = new HashMap<>();
        suites.put(0, suiteNames);
        dataStore.persistDistributedRunPlan(new DistributedRunPlan(
                DistributedRun.open(runId, "main", "plan-commit", 1, 1, null, 1000L, 1234L, mode, null),
                Collections.singletonList(DistributedRunGroup.pending(runId, 0, 1000L)), suites, null));
    }

    /**
     * Build a classifier over one test-classes dir.
     *
     * @param classesDir the dir to read class files from
     * @return the classifier
     */
    private static TestSuiteRunnabilityClassifier classifierOver(final Path classesDir) {
        return TestSuiteRunnabilityClassifier.forDirs(Collections.singletonList(classesDir.toString()));
    }

    /**
     * Run an action while capturing everything logged to stderr (where slf4j-simple writes), so a
     * test can assert on the WARN and ERROR lines the action produced.
     *
     * @param captured receives the captured log text at index 0
     * @param action the action to run
     * @param <T> the action's result type
     * @return the action's result
     */
    private static <T> T captureLog(final String[] captured, final Supplier<T> action) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        try {
            System.setErr(new PrintStream(buffer, true, StandardCharsets.UTF_8.name()));
            return action.get();
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        } finally {
            System.setErr(originalErr);
            captured[0] = new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Build and persist a single-group plan with {@code suiteCount} suites, for a test that needs a
     * group large enough to report a growing observed set against.
     *
     * @param runId the run identifier to plan under
     * @param suiteCount how many suites to assign to the one group
     */
    private void persistPlanWithOneGroupOfSuites(final String runId, final int suiteCount) {
        List<String> suiteNames = new ArrayList<>(suites(0, suiteCount));
        Map<Integer, List<String>> suites = new HashMap<>();
        suites.put(0, suiteNames);
        dataStore.persistDistributedRunPlan(new DistributedRunPlan(
                DistributedRun.open(runId, "main", "plan-commit", 1, 1, null, 1000L, 1234L,
                        SelectionMode.SELECTIVE, null),
                Collections.singletonList(DistributedRunGroup.pending(runId, 0, 1000L)), suites, null));
    }

    /**
     * Name suites the way the plan helpers assign them: {@link #persistPlan} gives group {@code i}
     * suite {@code i}, and {@link #persistPlanWithOneGroupOfSuites} gives its group suites {@code 0}
     * to {@code suiteCount - 1}.
     *
     * @param from the first suite index, inclusive
     * @param to the last suite index, exclusive
     * @return {@code com.example.Suite<from>Test} to {@code com.example.Suite<to-1>Test}, in order
     */
    private static Set<String> suites(final int from, final int to) {
        Set<String> names = new LinkedHashSet<>();
        for (int i = from; i < to; i++) {
            names.add("com.example.Suite" + i + "Test");
        }
        return names;
    }

    /**
     * Build and persist a distributed run plan, which also clears any previously planned run - the
     * supersession a straggler runner has to survive.
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
        dataStore.persistDistributedRunPlan(new DistributedRunPlan(
                DistributedRun.open(runId, "main", "plan-commit", groupCount, groupCount, null,
                        1000L * groupCount, 1234L, SelectionMode.SELECTIVE, null), groups, suites, null));
    }

    /**
     * Build and persist a SEED distributed run plan, one suite per group, so a test can exercise the
     * seed-run rejection wording.
     *
     * @param runId the run identifier to plan under
     * @param groupCount how many groups the plan is split into
     */
    private void persistSeedPlan(final String runId, final int groupCount) {
        List<DistributedRunGroup> groups = new ArrayList<>();
        Map<Integer, List<String>> suites = new HashMap<>();
        for (int i = 0; i < groupCount; i++) {
            groups.add(DistributedRunGroup.pending(runId, i, 1000L));
            suites.put(i, Arrays.asList("com.example.Suite" + i + "Test"));
        }
        dataStore.persistDistributedRunPlan(new DistributedRunPlan(
                DistributedRun.open(runId, "main", "plan-commit", groupCount, groupCount, null,
                        1000L * groupCount, 1234L, SelectionMode.SEED, null), groups, suites, null));
    }
}
