package org.tiatesting.spock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spockframework.runtime.extension.IGlobalExtension;
import org.spockframework.runtime.model.SpecInfo;
import org.tiatesting.core.agent.ForkSystemProperties;
import org.tiatesting.core.agent.RunSelectionDetailsCodec;
import org.tiatesting.core.agent.SelectionHandoff;
import org.tiatesting.core.distributed.DistributedForkProperties;
import org.tiatesting.core.distributed.DistributedRunConfig;
import org.tiatesting.core.distributed.DistributedRunnerAssignment;
import org.tiatesting.core.distributed.DistributedRunnerContext;
import org.tiatesting.core.library.LibraryImpactDrainResult;
import org.tiatesting.core.library.LibraryImpactDrainResultSerializer;
import org.tiatesting.core.model.TestRunSelectionDetails;
import org.tiatesting.core.persistence.DataStore;
import org.tiatesting.core.persistence.DataStoreFactory;
import org.tiatesting.core.testrunner.TestJvmSequence;
import org.tiatesting.core.testrunner.TestRunnerService;

import java.io.File;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class TiaSpockGlobalExtension implements IGlobalExtension {

    private static final Logger log = LoggerFactory.getLogger(TiaSpockGlobalExtension.class);
    private final boolean tiaEnabled;
    private final boolean tiaUpdateDBMapping;
    private final boolean tiaUpdateDBTestRunHistory;
    private final TiaSpockRunListener tiaTestingSpockRunListener;
    private final DataStore dataStore;
    private final SpecificationUtil specificationUtil;
    private Set<String> ignoredTests = new HashSet<>();
    /*
    Track all the test suites that were executed by the test runner. This includes those that were skipped/ignored.
     */
    private Set<String> runnerTestSuites = ConcurrentHashMap.newKeySet();
    private long testRunStartTime;

    /**
     * Work out which test suites this Spock test JVM must skip, and build the run listener that
     * records what it ran. Registered through {@code tia-spock}'s {@code IGlobalExtension} service
     * descriptor, so it is constructed by Spock with no arguments.
     *
     * <p>This JVM never selects and never reads a version control system. The Gradle daemon's test
     * task action does both, once per test task, and hands the result over:
     * <ul>
     *     <li>An ordinary build: the daemon runs the test selection and writes the hand-off files
     *     ({@link SelectionHandoff}), naming them in system properties. This constructor reads the
     *     suites to skip and run, the library-impact drain result and the selection breakdown from
     *     them.</li>
     *     <li>A distributed build: the daemon claims this runner's group from the shared plan, and
     *     forwards the claim's result. This constructor resolves it via {@link
     *     DistributedForkProperties#contextFromSystemProperties()} and re-derives the two suite sets
     *     with {@link DistributedRunnerAssignment#forClaimedRunner}, the same derivation the claim
     *     used. Claiming a second time here would take a second group and leave the first open
     *     forever, so the run would never seal.</li>
     * </ul>
     * The branch and the commit come from system properties the daemon resolved. See the "How Tia
     * exchanges data with the test runner" chapter in {@code WIKI.md}.
     *
     * @throws IllegalStateException if Tia is enabled for an ordinary build but the daemon handed
     *                               over no selection, or if this build is a distributed runner
     *                               but the shared plan its group was claimed from is no longer
     *                               readable - a runner that cannot tell whether its share of the
     *                               suite ran must never report green
     */
    public TiaSpockGlobalExtension(){
        this.specificationUtil = new SpecificationUtil();
        tiaEnabled = Boolean.parseBoolean(System.getProperty("tiaEnabled"));

        if (tiaEnabled){
            // Null for every ordinary build.
            DistributedRunnerContext distributedRunnerContext =
                    DistributedForkProperties.contextFromSystemProperties();
            tiaUpdateDBMapping = Boolean.parseBoolean(System.getProperty("tiaUpdateDBMapping"));
            // updateDBTestRunHistory defaults to TRUE - log unless explicitly switched off.
            tiaUpdateDBTestRunHistory = !"false".equalsIgnoreCase(System.getProperty("tiaUpdateDBTestRunHistory"));
            String branch = ForkSystemProperties.branchFromSystemProperties();
            String headCommit = ForkSystemProperties.commitValueFromSystemProperties();
            dataStore = DataStoreFactory.fromSystemProperties(branch);

            Set<String> testsToRun;
            LibraryImpactDrainResult drainResult;
            // The per-run selection breakdown that TestRunResult carries through to the history
            // row. Left empty() for a distributed runner - the build-level breakdown for that case
            // is written by the sealer, and this fork's own share is not the figure to show.
            TestRunSelectionDetails selectionDetails = TestRunSelectionDetails.empty();

            if (distributedRunnerContext != null){
                // forRunner, not validated: this config exists only to key the derivation's reads
                // by the run id the group was claimed under. No drain result is carried: the plan
                // already ran the drain once, and applying its cleanup belongs to the sealer.
                DistributedRunConfig config = DistributedRunConfig.forRunner(
                        distributedRunnerContext.getRunId(), distributedRunnerContext.getRunnerKey());
                // A surplus runner (null group number) ignores every suite and runs none.
                DistributedRunnerAssignment assignment = DistributedRunnerAssignment.forClaimedRunner(
                        dataStore, config, distributedRunnerContext.getRunnerKey(),
                        distributedRunnerContext.getGroupNumber());
                testsToRun = assignment.getTestsToRun();
                ignoredTests = assignment.getTestsToIgnore();
                drainResult = null;
            } else {
                String ignoredTestsFile = System.getProperty(SelectionHandoff.PROP_IGNORED_TESTS_FILE);
                String selectedTestsFile = System.getProperty(SelectionHandoff.PROP_SELECTED_TESTS_FILE);
                if (ignoredTestsFile == null || selectedTestsFile == null) {
                    throw new IllegalStateException("Tia is enabled but the Gradle plugin handed this test "
                            + "JVM no test selection (" + SelectionHandoff.PROP_IGNORED_TESTS_FILE + " / "
                            + SelectionHandoff.PROP_SELECTED_TESTS_FILE + " are not set). Apply the Tia "
                            + "Gradle plugin to the project running this test task.");
                }
                ignoredTests = SelectionHandoff.readSuiteNames(ignoredTestsFile);
                testsToRun = SelectionHandoff.readSuiteNames(selectedTestsFile);
                drainResult = LibraryImpactDrainResultSerializer.deserialize(
                        System.getProperty(SelectionHandoff.PROP_DRAIN_RESULT_FILE));
                String selectionDetailsFile = System.getProperty(SelectionHandoff.PROP_SELECTION_DETAILS_FILE);
                if (selectionDetailsFile != null) {
                    selectionDetails = RunSelectionDetailsCodec.read(new File(selectionDetailsFile));
                }
            }

            if (tiaUpdateDBMapping || tiaUpdateDBTestRunHistory){
                // the listener is used for collecting coverage, updating the stored mapping,
                // and/or recording the run in the history log
                int ignoredTestSuiteCount = ignoredTests != null ? ignoredTests.size() : 0;
                this.tiaTestingSpockRunListener = new TiaSpockRunListener(branch, headCommit,
                        dataStore, testsToRun,
                        ignoredTestSuiteCount,
                        tiaUpdateDBMapping, tiaUpdateDBTestRunHistory,
                        drainResult, selectionDetails, distributedRunnerContext,
                        // A Gradle test-retry round is a fresh JVM - the counter the Gradle plugin
                        // resets per task execution tells it apart from the real run.
                        TestJvmSequence.attemptFromSystemProperties());
            } else {
                // not updating the DB, no need to use the Spock listener
                this.tiaTestingSpockRunListener = null;
            }
        } else {
            tiaUpdateDBMapping = false;
            tiaUpdateDBTestRunHistory = false;
            dataStore = null;
            this.tiaTestingSpockRunListener = null;
        }

        log.info("Tia: enabled: {}, update mapping (and stats): {}, update test run history: {}",
                tiaEnabled, tiaUpdateDBMapping, tiaUpdateDBTestRunHistory);
    }

    @Override
    public void start() {
        if (tiaEnabled) {
            testRunStartTime = System.currentTimeMillis();
        }
    }

    @Override
    public void visitSpec(SpecInfo spec){
        if (tiaEnabled){
            if (tiaUpdateDBMapping || tiaUpdateDBTestRunHistory){
                runnerTestSuites.add(specificationUtil.getSpecName(spec));
                spec.addListener(tiaTestingSpockRunListener);
            }

            if (ignoredTests.contains(specificationUtil.getSpecName(spec))){
                spec.skip("Test not selected to run based on the changes analyzed by Tia");
            }
        }
    }

    @Override
    public void stop(){
        if (tiaEnabled && (tiaUpdateDBMapping || tiaUpdateDBTestRunHistory)) {
            tiaTestingSpockRunListener.finishAllTests(knownTestSuites(), testRunStartTime);
        }
    }

    /**
     * The test suites this run treats as still existing in the project.
     *
     * <p>Read from the compiled test-classes directories when the build tool names them, and only
     * from the specs this JVM visited when it does not. The distinction decides whether a suite
     * absent from this JVM's view is <em>deleted</em> or merely <em>not run here</em>, and the
     * persist deletes the stored mapping of anything it concludes is deleted.
     *
     * <p>Visited specs are the right answer for one JVM running the whole project, and the wrong one
     * the moment a run is split - {@code maxParallelForks > 1} or {@code forkEvery > 0} give each
     * JVM a share of the classes, so each would conclude that every suite the others own has been
     * deleted and remove it. The directories are identical for every fork, so answering from them
     * makes the question independent of how the run was split. This is the same override the JUnit
     * listeners have always had, which is why the Maven path was never exposed to this.
     *
     * @return the suites to treat as present, from the directory scan when configured
     */
    private Set<String> knownTestSuites() {
        String testClassesDirs = System.getProperty(ForkSystemProperties.PROP_TEST_CLASSES_DIRS);
        if (testClassesDirs == null || testClassesDirs.trim().isEmpty()) {
            return runnerTestSuites;
        }
        return new TestRunnerService(dataStore).getTestClassesFromDirs(testClassesDirs);
    }

}
