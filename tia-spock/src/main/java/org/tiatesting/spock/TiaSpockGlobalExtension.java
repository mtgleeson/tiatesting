package org.tiatesting.spock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spockframework.runtime.extension.IGlobalExtension;
import org.spockframework.runtime.model.SpecInfo;
import org.tiatesting.core.vcs.VCSReader;
import org.tiatesting.persistence.DataStore;
import org.tiatesting.persistence.MapDataStore;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class TiaSpockGlobalExtension implements IGlobalExtension {

    private static final Logger log = LoggerFactory.getLogger(TiaSpockGlobalExtension.class);
    private final boolean tiaEnabled;
    private final boolean tiaUpdateDB;
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
    private final VCSReader vcsReader;

    public TiaSpockGlobalExtension(final VCSReader vcsReader){
        this.vcsReader = vcsReader;
        this.specificationUtil = new SpecificationUtil();
        tiaEnabled = Boolean.parseBoolean(System.getProperty("tiaEnabled"));

        if (tiaEnabled){
            tiaUpdateDB = Boolean.parseBoolean(System.getProperty("tiaUpdateDB"));
            String dbFilePath = System.getProperty("tiaDBFilePath");
            dataStore = new MapDataStore(dbFilePath, vcsReader.getBranchName());
            sourceFilesDirs = System.getProperty("tiaSourceFilesDirs") != null ? Arrays.asList(System.getProperty("tiaSourceFilesDirs").split(",")) : null;
            testFilesDirs = System.getProperty("tiaTestFilesDirs") != null ? Arrays.asList(System.getProperty("tiaTestFilesDirs").split(",")) : null;
            boolean checkLocalChanges = Boolean.parseBoolean(System.getProperty("tiaCheckLocalChanges"));

            if (tiaUpdateDB){
                // the listener is used for collecting coverage and updating the stored test mapping
                this.tiaTestingSpockRunListener = new TiaSpockRunListener(vcsReader, dataStore);

                // Don't check for local changes. We shouldn't update the DB using unsubmitted changes.
                this.checkLocalChanges = false;
                if(checkLocalChanges){
                    // user was trying to check for local changes - let them know they can't
                    log.warn("Disabling the check for local changes as Tia is configured to update the DB.");
                }
            } else {
                // not updating the DB, no need to use the Spock listener
                this.tiaTestingSpockRunListener = null;

                // only check for local changes when not updating the DB.
                this.checkLocalChanges = checkLocalChanges;
            }
        } else {
            tiaUpdateDB = false;
            dataStore = null;
            sourceFilesDirs = null;
            testFilesDirs = null;
            this.tiaTestingSpockRunListener = null;
            this.checkLocalChanges = false;
        }

        log.info("Tia: enabled: {}, update DB: {}", tiaEnabled, tiaUpdateDB);
    }

    @Override
    public void start() {
        if (tiaEnabled) {
            TiaSpockTestRunInitializer tiaSpockTestRunInitializer = new TiaSpockTestRunInitializer(vcsReader, dataStore, tiaUpdateDB);
            ignoredTests = tiaSpockTestRunInitializer.getTestsToIgnore(sourceFilesDirs, testFilesDirs, checkLocalChanges);
        }
    }

    @Override
    public void visitSpec(SpecInfo spec){
        if (tiaEnabled){
            if (tiaUpdateDB){
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
        if (tiaEnabled && tiaUpdateDB) {
            tiaTestingSpockRunListener.finishAllTests(runnerTestSuites);
        }
    }
}
