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

public class TiaSpockGlobalExtension implements IGlobalExtension {

    private static final Logger log = LoggerFactory.getLogger(TiaSpockGlobalExtension.class);
    private final boolean tiaEnabled;
    private final List<String> sourceFilesDirs;
    private final TiaSpockRunListener tiaTestingSpockRunListener;
    private final DataStore dataStore;
    private Set<String> ignoredTests = new HashSet<>();
    private final VCSReader vcsReader;

    public TiaSpockGlobalExtension(final VCSReader vcsReader){
        this.vcsReader = vcsReader;
        tiaEnabled = Boolean.parseBoolean(System.getProperty("tiaEnabled"));

        if (tiaEnabled){
            String dbPersistenceStrategy = System.getProperty("tiaDBPersistenceStrategy");
            String dbFilePath = System.getProperty("tiaDBFilePath");
            dataStore = new MapDataStore(dbFilePath, vcsReader.getBranchName(), dbPersistenceStrategy);
            sourceFilesDirs = System.getProperty("tiaSourceFilesDirs") != null ? Arrays.asList(System.getProperty("tiaSourceFilesDirs").split(",")) : null;
            this.tiaTestingSpockRunListener = new TiaSpockRunListener(vcsReader, dataStore);
        } else {
            log.error("TIA is disabled for this test run (use tia.enabled to enable TIA).");
            dataStore = null;
            sourceFilesDirs = null;
            this.tiaTestingSpockRunListener = null;
        }
    }

    @Override
    public void start() {
        if (tiaEnabled) {
            ignoredTests = new TiaSpockTestRunInitializer(vcsReader, dataStore).getTestsToRun(sourceFilesDirs);
        }
    }

    @Override
    public void visitSpec(SpecInfo spec){
        if (tiaEnabled){
            spec.addListener(tiaTestingSpockRunListener);

            if (ignoredTests.contains(tiaTestingSpockRunListener.getSpecName(spec))){
                spec.skip("Test not selected to run based on the changes analyzed by Tia");
            }
        }
    }

    @Override
    public void stop(){
        if (tiaEnabled) {
            tiaTestingSpockRunListener.finishAllTests();
        }
    }
}
