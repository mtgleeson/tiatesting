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
        tiaUpdateDB = Boolean.parseBoolean(System.getProperty("tiaUpdateDB"));

        if (tiaEnabled){
            String dbFilePath = System.getProperty("tiaDBFilePath");
            dataStore = new MapDataStore(dbFilePath, vcsReader.getBranchName());
            sourceFilesDirs = System.getProperty("tiaSourceFilesDirs") != null ? Arrays.asList(System.getProperty("tiaSourceFilesDirs").split(",")) : null;
            testFilesDirs = System.getProperty("tiaTestFilesDirs") != null ? Arrays.asList(System.getProperty("tiaTestFilesDirs").split(",")) : null;

            if (tiaUpdateDB){
                // the listener is used for collecting coverage and updating the stored test mapping
                this.tiaTestingSpockRunListener = new TiaSpockRunListener(vcsReader, dataStore);
            } else {
                // not updating the DB, no need to use the Spock listener
                this.tiaTestingSpockRunListener = null;
            }
        } else {
            log.info("TIA is disabled for this test run.");
            dataStore = null;
            sourceFilesDirs = null;
            testFilesDirs = null;
            this.tiaTestingSpockRunListener = null;
        }
    }

    @Override
    public void start() {
        if (tiaEnabled) {
            ignoredTests = new TiaSpockTestRunInitializer(vcsReader, dataStore, tiaUpdateDB).getTestsToIgnore(sourceFilesDirs, testFilesDirs);
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
