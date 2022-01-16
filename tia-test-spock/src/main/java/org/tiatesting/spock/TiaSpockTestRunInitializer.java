package org.tiatesting.spock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tiatesting.core.coverage.ClassImpactTracker;
import org.tiatesting.core.coverage.MethodImpactTracker;
import org.tiatesting.core.diff.SourceFileDiffContext;
import org.tiatesting.core.vcs.VCSReader;
import org.tiatesting.diffanalyze.FileImpactAnalyzer;
import org.tiatesting.diffanalyze.MethodImpactAnalyzer;
import org.tiatesting.persistence.DataStore;
import org.tiatesting.persistence.StoredMapping;

import java.util.*;

public class TiaSpockTestRunInitializer {
    private static final Logger log = LoggerFactory.getLogger(TiaSpockTestRunInitializer.class);

    private final VCSReader vcsReader;
    private final DataStore dataStore;

    public TiaSpockTestRunInitializer(final VCSReader vcsReader, final DataStore dataStore){
        this.vcsReader = vcsReader;
        this.dataStore = dataStore;
    }

    Set<String> getTestsToRun(final List<String> sourceFilesDirs){
        Set<String> ignoredTests = new HashSet<>();
        StoredMapping storedMapping = dataStore.getStoredMapping();
        log.info("Store commit: " + storedMapping.getCommitValue());

        if (storedMapping.getCommitValue() == null) {
            log.info("No stored commit value found. Tia hasn't previously run. Running all tests.");
            return ignoredTests;
        }

        // build the list of source files that have been changed since the previously analyzed commit
        List<SourceFileDiffContext> impactedSourceFiles = vcsReader.buildDiffFilesContext(storedMapping.getCommitValue());

        // For the source files that have changed, do a diff to find the methods that have changed
        FileImpactAnalyzer fileImpactAnalyzer = new FileImpactAnalyzer(new MethodImpactAnalyzer());
        Set<String> methodsImpacted = fileImpactAnalyzer.getMethodsForFilesChanged(impactedSourceFiles, storedMapping,
                sourceFilesDirs);

        // build the list of test suites that need to be run based on the tracked methods that have been changed
        Map<String, Set<String>> methodTestSuites = buildMethodToTestSuiteMap(storedMapping);
        Set<String> testsToRun = new HashSet<>();
        methodsImpacted.forEach( ( methodImpacted ) -> {
            testsToRun.addAll(methodTestSuites.get(methodImpacted));
        });
        log.debug("Selected tests to run: {}", testsToRun);

        // find the list of all known tracked test suites that are in the list of tests to run - this is the ignore list.
        // i.e. only ignore test suites that we have previously tracked and know haven't been impacted by the source changes.
        // this ensures any new test suites are executed.
        storedMapping.getClassesImpacted().keySet().forEach( (testSuite) -> {
            if (!testsToRun.contains(testSuite)){
                ignoredTests.add(testSuite);
            }
        });

        // ignoredTests.add("com.example.CarServiceTest");

        log.debug("Ignoring tests: {}", ignoredTests);
        return ignoredTests;
    }

    private static Map<String, Set<String>> buildMethodToTestSuiteMap(StoredMapping storedMapping){
        Map<String, Set<String>> methodTestSuites = new HashMap<>();

        storedMapping.getClassesImpacted().forEach((testSuiteName, classImpactTrackers) -> {
            for (ClassImpactTracker classImpacted : classImpactTrackers) {
                for (MethodImpactTracker methodImpactTracker : classImpacted.getMethodsImpacted()) {
                    if (methodTestSuites.get(methodImpactTracker.getMethodName()) == null) {
                        methodTestSuites.put(methodImpactTracker.getMethodName(), new HashSet<>());
                    }

                    methodTestSuites.get(methodImpactTracker.getMethodName()).add(testSuiteName);
                }
            }
        });

        //methodTestSuites.forEach( (key, val) -> {
        //    System.out.println(System.lineSeparator() + "key: " + key + " val: " + System.lineSeparator() + "\t" + val.stream().map( String::valueOf ).collect(Collectors.joining(System.lineSeparator() + "\t", "", "")));
        //});

        return methodTestSuites;
    }
}
