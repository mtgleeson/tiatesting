package org.tiatesting.spock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spockframework.runtime.AbstractRunListener;
import org.spockframework.runtime.model.ErrorInfo;
import org.spockframework.runtime.model.FeatureInfo;
import org.spockframework.runtime.model.IterationInfo;
import org.spockframework.runtime.model.SpecInfo;
import org.tiatesting.core.coverage.MethodImpactTracker;
import org.tiatesting.core.coverage.TestSuiteTracker;
import org.tiatesting.core.coverage.client.JacocoClient;
import org.tiatesting.core.coverage.result.CoverageResult;
import org.tiatesting.core.vcs.VCSReader;
import org.tiatesting.persistence.DataStore;
import org.tiatesting.report.ReportGenerator;
import org.tiatesting.report.TextFileReportGenerator;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class TiaSpockRunListener extends AbstractRunListener {

    private static final Logger log = LoggerFactory.getLogger(TiaSpockRunListener.class);

    private final JacocoClient coverageClient;
    private final DataStore dataStore;
    private final VCSReader vcsReader;
    private final Map<String, TestSuiteTracker> testSuiteTrackers;
    private final Set<String> testSuitesFailed;
    private final Set<String> testSuitesProcessed;
    private final Map<Integer, MethodImpactTracker> testRunMethodsImpacted;
    private final SpecificationUtil specificationUtil;
    private boolean stopStepRan;

    public TiaSpockRunListener(final VCSReader vcsReader, final DataStore dataStore){
        this.coverageClient = new JacocoClient();
        this.coverageClient.initialize();
        this.testSuiteTrackers = new ConcurrentHashMap<>();
        this.testSuitesFailed = ConcurrentHashMap.newKeySet();
        this.testSuitesProcessed = ConcurrentHashMap.newKeySet();
        this.testRunMethodsImpacted = new ConcurrentHashMap<>();
        this.specificationUtil = new SpecificationUtil();
        this.vcsReader = vcsReader;
        this.dataStore = dataStore;
    }

    @Override
    public void afterSpec(SpecInfo spec) {
        String specName = specificationUtil.getSpecName(spec);

        if (spec.isSkipped() || this.testSuitesProcessed.contains(specName)) {
            return;
        }

        log.debug("Collecting coverage and adding the mapping for the test suite: " + specName);
        TestSuiteTracker testSuiteTracker = new TestSuiteTracker(specName);
        testSuiteTracker.setSourceFilename(specificationUtil.getSpecSourceFileName(spec));
        try {
            CoverageResult coverageResult = this.coverageClient.collectCoverage();
            testSuiteTracker.setClassesImpacted(coverageResult.getClassesInvoked());
            testRunMethodsImpacted.putAll(coverageResult.getAllMethodsClassesInvoked());
        } catch (IOException e) {
            log.error("Error while collecting coverage", e);
            throw new RuntimeException(e);
        }

        this.testSuiteTrackers.put(specName, testSuiteTracker);
        testSuitesProcessed.add(specName); // this method is called twice for some reason - avoid processing it twice.
    }

    @Override
    public void error(ErrorInfo error) {
        this.testSuitesFailed.add(specificationUtil.getSpecName(error.getMethod().getFeature().getSpec()));
    }

    @Override
    public void specSkipped(SpecInfo spec) {
        log.info(specificationUtil.getSpecName(spec) + " was skipped!");
    }

    public void finishAllTests(Set<String> runnerTestSuites){
        if (stopStepRan){
            return;
        }

        stopStepRan = true; // this method is called twice for some reason - avoid processing it twice.
        log.info("Test run finished. Persisting the test mapping.");
        this.dataStore.persistTestMapping(this.testSuiteTrackers, this.testSuitesFailed, runnerTestSuites,
                testRunMethodsImpacted, vcsReader.getHeadCommit());

        // TODO temp. Create a new maven/gradle task/mojo that generates the file
        ReportGenerator reportGenerator = new TextFileReportGenerator(this.vcsReader.getBranchName());
        reportGenerator.generateReport(this.dataStore);
    }

}
