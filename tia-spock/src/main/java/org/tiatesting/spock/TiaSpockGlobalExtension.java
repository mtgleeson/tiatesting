package org.tiatesting.spock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spockframework.runtime.extension.IGlobalExtension;
import org.spockframework.runtime.model.SpecInfo;
import org.tiatesting.core.diff.diffanalyze.selector.TestSelectorResult;
import org.tiatesting.core.distributed.DistributedForkProperties;
import org.tiatesting.core.distributed.DistributedRunConfig;
import org.tiatesting.core.distributed.DistributedRunnerAssignment;
import org.tiatesting.core.distributed.DistributedRunnerContext;
import org.tiatesting.core.library.LibraryImpactAnalysisConfig;
import org.tiatesting.core.library.LibraryImpactDrainResult;
import org.tiatesting.core.persistence.DataStore;
import org.tiatesting.core.persistence.DataStoreFactory;
import org.tiatesting.core.staticselection.StaticTestSelectionConfig;
import org.tiatesting.core.util.StringUtil;
import org.tiatesting.core.vcs.VCSReader;
import org.tiatesting.spock.staticselection.StaticTestSelectionSystemProperties;
import org.tiatesting.spock.library.LibraryMetadataSystemProperties;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class TiaSpockGlobalExtension implements IGlobalExtension {

    private static final Logger log = LoggerFactory.getLogger(TiaSpockGlobalExtension.class);
    private final boolean tiaEnabled;
    private final boolean tiaUpdateDBMapping;
    private final boolean tiaUpdateDBStats;
    private final boolean tiaUpdateDBTestRunHistory;
    private final List<String> sourceFilesDirs;
    private final List<String> testFilesDirs;
    private final boolean checkLocalChanges;
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
     * records what it ran. For an ordinary (non-distributed) Gradle build, selection genuinely runs
     * inside this test JVM rather than in the build JVM, so this constructor is where the whole of
     * Tia's pre-run work happens.
     *
     * <p>How the suite lists are arrived at is the one thing that differs between an ordinary and
     * a distributed build. An ordinary build runs the test selection here. A distributed build
     * must not: the plan produced by {@code tia-dist-plan} already ran the VCS diff, the static
     * rules and the library-impact drain once, for every runner, and its output is in the shared
     * database. So a distributed build does not select, and does not claim either - on both Maven
     * and Gradle the claim now happens in the build JVM, before the test JVM forks (on Gradle, the
     * daemon's test-task action - see {@code TiaSpockGitGradlePluginTestExtension#applyTo}), and
     * this constructor only resolves that claim's result from system properties, via {@link
     * DistributedForkProperties#contextFromSystemProperties()}, then re-derives the two suite sets
     * from it via {@link DistributedRunnerAssignment#forClaimedRunner}, the same derivation the
     * build JVM's claim used. Claiming a second time here would take a second group and leave the
     * first open forever, so the run would never seal.
     *
     * @param vcsReader the VCS reader for the workspace under test, or null when Tia is disabled;
     *                  supplies the branch whose mapping is read
     * @throws IllegalStateException if this build is a distributed runner but the shared plan its
     *                                group was claimed from is no longer readable - for example a
     *                                later build superseded it between the daemon's claim and this
     *                                JVM starting - since a runner that cannot tell whether its
     *                                share of the suite ran must never report green
     */
    public TiaSpockGlobalExtension(final VCSReader vcsReader){
        this.specificationUtil = new SpecificationUtil();
        tiaEnabled = Boolean.parseBoolean(System.getProperty("tiaEnabled"));

        if (tiaEnabled){
            // Resolved from the properties the Gradle daemon's test-task action already claimed
            // with, before opening the datastore, since resolving is just a system-property read
            // now - the preconditions this used to also validate here are the daemon's job (Task
            // 2a moved them there, where the real reactor size is knowable). Null for every
            // ordinary build, which therefore behaves exactly as it always did.
            DistributedRunnerContext distributedRunnerContext =
                    DistributedForkProperties.contextFromSystemProperties();
            tiaUpdateDBMapping = Boolean.parseBoolean(System.getProperty("tiaUpdateDBMapping"));
            tiaUpdateDBStats = Boolean.parseBoolean(System.getProperty("tiaUpdateDBStats"));
            // updateDBTestRunHistory defaults to TRUE - log unless explicitly switched off.
            tiaUpdateDBTestRunHistory = !"false".equalsIgnoreCase(System.getProperty("tiaUpdateDBTestRunHistory"));
            dataStore = DataStoreFactory.fromSystemProperties(vcsReader.getBranchName());
            sourceFilesDirs = System.getProperty("tiaSourceFilesDirs") != null ? Arrays.asList(System.getProperty("tiaSourceFilesDirs").split(",")) : null;
            StringUtil.sanitizeInputArray(sourceFilesDirs);
            testFilesDirs = System.getProperty("tiaTestFilesDirs") != null ? Arrays.asList(System.getProperty("tiaTestFilesDirs").split(",")) : null;
            StringUtil.sanitizeInputArray(testFilesDirs);
            boolean checkLocalChanges = Boolean.parseBoolean(System.getProperty("tiaCheckLocalChanges"));

            if (tiaUpdateDBMapping && checkLocalChanges){
                // Don't check for local changes. We shouldn't update the DB mapping using unsubmitted changes.
                this.checkLocalChanges = false;

                // user was trying to check for local changes - let them know they can't
                log.info("Disabling the check for local changes as Tia is configured to update the DB.");
            }else{
                // only check for local changes when not updating the DB.
                this.checkLocalChanges = checkLocalChanges;
            }

            TiaSpockTestRunInitializer tiaSpockTestRunInitializer = new TiaSpockTestRunInitializer(vcsReader, dataStore);
            Set<String> testsToRun;
            LibraryImpactDrainResult drainResult;

            if (distributedRunnerContext != null){
                // A distributed runner derives its suites from the group the daemon already
                // claimed, instead of selecting or claiming here. The plan already ran the diff
                // and the library-impact drain once; repeating the drain per-runner would race,
                // and applying its cleanup belongs to the run's sealer, so no drain result is
                // carried here.
                //
                // forRunner, not validated: this config exists only to key the derivation's reads
                // by the run id the group was claimed under - no group count or target run time is
                // asked for, since that shape was already decided by the plan this context was
                // resolved from.
                DistributedRunConfig config = DistributedRunConfig.forRunner(
                        distributedRunnerContext.getRunId(), distributedRunnerContext.getRunnerKey());
                // forClaimedRunner, not claim: this fork does not claim, it re-derives the same two
                // suite lists the daemon's claim already resolved, from the runner key and group
                // number the daemon forwarded. Sharing DistributedRunnerAssignment's one copy of the
                // derivation is what keeps this fork from landing on a different answer than the
                // claim already committed to - a surplus runner (null group number) ignores every
                // suite and runs none, same as before.
                DistributedRunnerAssignment assignment = DistributedRunnerAssignment.forClaimedRunner(
                        dataStore, config, distributedRunnerContext.getRunnerKey(),
                        distributedRunnerContext.getGroupNumber());
                testsToRun = assignment.getTestsToRun();
                ignoredTests = assignment.getTestsToIgnore();
                drainResult = null;
            } else {
                // The Gradle plugin pre-resolves library metadata (declared version, source dirs, resolved
                // version + JAR path) and forwards it via the tiaLibrariesMetadata system property. When
                // unset (no tiaSourceLibs configured), libraryConfig is null and library partitioning /
                // reconcile / stamp / drain are skipped - same as before.
                LibraryImpactAnalysisConfig libraryConfig = LibraryMetadataSystemProperties.fromSystemProperties();
                // Static test selection rules are pre-resolved on the Gradle side and forwarded
                // through the tiaStaticTestSelectionRules system property; absent property means
                // no rules in effect.
                StaticTestSelectionConfig staticMappingConfig = StaticTestSelectionSystemProperties.fromSystemProperties();
                TestSelectorResult testSelectorResult = tiaSpockTestRunInitializer.selectTests(sourceFilesDirs, testFilesDirs,
                        this.checkLocalChanges, tiaUpdateDBMapping, libraryConfig, staticMappingConfig);
                ignoredTests = testSelectorResult.getTestsToIgnore();
                testsToRun = testSelectorResult.getTestsToRun();
                drainResult = testSelectorResult.getLibraryImpactDrainResult();
            }

            if (tiaUpdateDBMapping || tiaUpdateDBStats || tiaUpdateDBTestRunHistory){
                // the listener is used for collecting coverage, updating the stored mapping,
                // and/or recording the run in the history log
                int ignoredTestSuiteCount = ignoredTests != null ? ignoredTests.size() : 0;
                this.tiaTestingSpockRunListener = new TiaSpockRunListener(vcsReader, dataStore, testsToRun,
                        ignoredTestSuiteCount,
                        tiaUpdateDBMapping, tiaUpdateDBStats, tiaUpdateDBTestRunHistory,
                        drainResult, distributedRunnerContext);
            } else {
                // not updating the DB, no need to use the Spock listener
                this.tiaTestingSpockRunListener = null;
            }
        } else {
            tiaUpdateDBMapping = false;
            tiaUpdateDBStats = false;
            tiaUpdateDBTestRunHistory = false;
            dataStore = null;
            sourceFilesDirs = null;
            testFilesDirs = null;
            this.tiaTestingSpockRunListener = null;
            this.checkLocalChanges = false;
        }

        log.info("Tia: enabled: {}, update mapping: {}, update stats: {}, update test run history: {}",
                tiaEnabled, tiaUpdateDBMapping, tiaUpdateDBStats, tiaUpdateDBTestRunHistory);
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
            if (tiaUpdateDBMapping || tiaUpdateDBStats || tiaUpdateDBTestRunHistory){
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
        if (tiaEnabled && (tiaUpdateDBMapping || tiaUpdateDBStats || tiaUpdateDBTestRunHistory)) {
            tiaTestingSpockRunListener.finishAllTests(runnerTestSuites, testRunStartTime);
        }
    }

}
