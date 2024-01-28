package org.tiatesting.spock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spockframework.runtime.extension.IGlobalExtension;
import org.spockframework.runtime.model.SpecInfo;
import org.tiatesting.core.vcs.VCSReader;
import org.tiatesting.persistence.DataStore;
import org.tiatesting.persistence.h2.H2DataStore;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class TiaSpockGlobalExtension implements IGlobalExtension {

    private static final Logger log = LoggerFactory.getLogger(TiaSpockGlobalExtension.class);
    private final boolean tiaEnabled;
    private final boolean tiaUpdateDBMapping;
    private final boolean tiaUpdateDBStats;
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
    /*
    The set of tests selected to run by Tia.
     */
    private final Set<String> selectedTests;
    private final VCSReader vcsReader;
    private long testRunStartTime;

    public TiaSpockGlobalExtension(final VCSReader vcsReader){
        this.vcsReader = vcsReader;
        this.specificationUtil = new SpecificationUtil();
        tiaEnabled = Boolean.parseBoolean(System.getProperty("tiaEnabled"));

        if (tiaEnabled){
            tiaUpdateDBMapping = Boolean.parseBoolean(System.getProperty("tiaUpdateDBMapping"));
            tiaUpdateDBStats = Boolean.parseBoolean(System.getProperty("tiaUpdateDBStats"));
            String dbFilePath = System.getProperty("tiaDBFilePath");
            dataStore = new H2DataStore(dbFilePath, vcsReader.getBranchName());
            sourceFilesDirs = System.getProperty("tiaSourceFilesDirs") != null ? Arrays.asList(System.getProperty("tiaSourceFilesDirs").split(",")) : null;
            testFilesDirs = System.getProperty("tiaTestFilesDirs") != null ? Arrays.asList(System.getProperty("tiaTestFilesDirs").split(",")) : null;
            boolean checkLocalChanges = Boolean.parseBoolean(System.getProperty("tiaCheckLocalChanges"));
            selectedTests = setSelectedTests();

            if (tiaUpdateDBMapping || tiaUpdateDBStats){
                // the listener is used for collecting coverage and updating the stored test mapping
                this.tiaTestingSpockRunListener = new TiaSpockRunListener(vcsReader, dataStore, selectedTests,
                        tiaUpdateDBMapping, tiaUpdateDBStats);
            } else {
                // not updating the DB, no need to use the Spock listener
                this.tiaTestingSpockRunListener = null;
            }

            if (tiaUpdateDBMapping && checkLocalChanges){
                // Don't check for local changes. We shouldn't update the DB mapping using unsubmitted changes.
                this.checkLocalChanges = false;

                // user was trying to check for local changes - let them know they can't
                log.info("Disabling the check for local changes as Tia is configured to update the DB.");
            }else{
                // only check for local changes when not updating the DB.
                this.checkLocalChanges = checkLocalChanges;
            }
        } else {
            tiaUpdateDBMapping = false;
            tiaUpdateDBStats = false;
            dataStore = null;
            sourceFilesDirs = null;
            testFilesDirs = null;
            this.tiaTestingSpockRunListener = null;
            this.checkLocalChanges = false;
            selectedTests = new HashSet<>();
        }

        log.info("Tia: enabled: {}, update mapping: {}, update stats: {}", tiaEnabled, tiaUpdateDBMapping, tiaUpdateDBStats);
    }

    @Override
    public void start() {
        if (tiaEnabled) {
            TiaSpockTestRunInitializer tiaSpockTestRunInitializer = new TiaSpockTestRunInitializer(vcsReader, dataStore);
            ignoredTests = tiaSpockTestRunInitializer.getTestsToIgnore(sourceFilesDirs, testFilesDirs, checkLocalChanges);
            testRunStartTime = System.currentTimeMillis();
        }
    }

    @Override
    public void visitSpec(SpecInfo spec){
        if (tiaEnabled){
            if (tiaUpdateDBMapping || tiaUpdateDBStats){
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
        if (tiaEnabled && (tiaUpdateDBMapping || tiaUpdateDBStats)) {
            tiaTestingSpockRunListener.finishAllTests(runnerTestSuites, testRunStartTime);
        }
    }

    private Set<String> setSelectedTests(){
        String selectedTestsStr = System.getProperty("tiaSelectedTests");
        if (selectedTestsStr != null && !selectedTestsStr.trim().isEmpty()){
            log.trace("Reading system property tiaSelectedTests: {}", selectedTestsStr);
            return Stream.of(selectedTestsStr.split(",")).collect(Collectors.toSet());
        }
        return new HashSet<>();
    }
}
