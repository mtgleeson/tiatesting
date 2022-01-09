package org.tiatesting.spock


import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.spockframework.runtime.AbstractRunListener
import org.spockframework.runtime.model.ErrorInfo
import org.spockframework.runtime.model.FeatureInfo
import org.spockframework.runtime.model.IterationInfo
import org.spockframework.runtime.model.SpecInfo
import org.tiatesting.core.coverage.ClassImpactTracker
import org.tiatesting.core.coverage.client.JacocoClient
import org.tiatesting.core.vcs.VCSReader
import org.tiatesting.persistence.DataStore
import org.tiatesting.persistence.MapDataStore
import org.tiatesting.persistence.PersistenceStrategy
import org.tiatesting.spock.report.ReportGenerator
import org.tiatesting.spock.report.TextFileReportGenerator

import java.util.concurrent.ConcurrentHashMap

class TiaTestingSpockRunListener extends AbstractRunListener {

    private static final Logger log = LoggerFactory.getLogger(TiaTestingSpockRunListener.class);

    private final JacocoClient coverageClient;
    private final DataStore dataStore;
    private final VCSReader vcsReader;
    private final Map<String, List<ClassImpactTracker>> testMethodsCalled;
    private Set<String> testSuitesFailed;
    private Set<String> testSuitesProcessed;
    private boolean stopStepRan;

    public TiaTestingSpockRunListener(VCSReader vcsReader){
        this.coverageClient = new JacocoClient();
        this.testMethodsCalled = new ConcurrentHashMap<>();
        this.testSuitesFailed = ConcurrentHashMap.newKeySet();
        this.testSuitesProcessed = ConcurrentHashMap.newKeySet();
        this.vcsReader = vcsReader; //new GitReader(System.getProperty("tiaProjectDir"));
        this.dataStore = new MapDataStore(System.getProperty("tiaDBFilePath"), vcsReader.getBranchName(),
                System.getProperty("tiaDBPersistenceStrategy"));
    }

    @Override
    public void beforeSpec(SpecInfo spec) {
    }

    @Override
    public void beforeFeature(FeatureInfo feature) {
    }

    @Override
    public void beforeIteration(IterationInfo iteration) {
    }

    @Override
    public void afterIteration(IterationInfo iteration) {
    }

    @Override
    public void afterFeature(FeatureInfo feature) {
    }

    @Override
    public void afterSpec(SpecInfo spec) {
        if (this.testSuitesFailed.contains(getSpecName(spec)) || this.testSuitesProcessed.contains(getSpecName(spec))) {
            return;
        }

        log.debug("Collecting coverage and adding the mapping for the successful test suite: " + getSpecName(spec))
        List<ClassImpactTracker> methodsCalledForTest = this.coverageClient.collectCoverage()
        this.testMethodsCalled.put(getSpecName(spec), methodsCalledForTest)
        testSuitesProcessed.add(getSpecName(spec)) // this method is called twice for some reason - avoid processing it twice.

        if (dataStore.getDBPersistenceStrategy() == PersistenceStrategy.INCREMENTAL){
            log.info("Test suite finished for " + getSpecName(spec) + ". Persisting the incremental test mapping.")
            this.dataStore.persistTestMapping(this.testMethodsCalled, vcsReader.getHeadCommit())
            this.testMethodsCalled.remove(getSpecName(spec))
        }
    }

    @Override
    public void error(ErrorInfo error) {
        this.testSuitesFailed.add(getSpecName(error.getMethod().getFeature().getSpec()))
    }

    @Override
    public void specSkipped(SpecInfo spec) {
    }

    @Override
    public void featureSkipped(FeatureInfo feature) {
    }

    public void finishAllTests(){
        if (stopStepRan){
            return
        }

        log.info("Test run finished!!");
        stopStepRan = true; // this method is called twice for some reason - avoid processing it twice.

        if (dataStore.getDBPersistenceStrategy() == PersistenceStrategy.ALL){
            log.info("Test run finished. Persisting the test mapping.");
            this.dataStore.persistTestMapping(this.testMethodsCalled, vcsReader.getHeadCommit());
        }

        // TODO temp. Create a new maven/gradle task/mojo that generates the file
        ReportGenerator reportGenerator = new TextFileReportGenerator(this.vcsReader.getBranchName());
        reportGenerator.generateReport(this.dataStore);
    }

    private String getSpecName(SpecInfo spec){
        return spec.getPackage() + "." + spec.getName()
    }
}
