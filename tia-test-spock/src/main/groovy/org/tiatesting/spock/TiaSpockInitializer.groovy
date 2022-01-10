package org.tiatesting.spock

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.tiatesting.core.coverage.ClassImpactTracker
import org.tiatesting.core.coverage.MethodImpactTracker
import org.tiatesting.core.diff.SourceFileDiffContext
import org.tiatesting.diffanalyze.FileImpactAnalyzer
import org.tiatesting.diffanalyze.MethodImpactAnalyzer
import org.tiatesting.persistence.DataStore
import org.tiatesting.persistence.MapDataStore
import org.tiatesting.persistence.StoredMapping
import org.tiatesting.vcs.git.GitReader

class TiaSpockInitializer {

    private static final Logger log = LoggerFactory.getLogger(TiaSpockInitializer.class);

    Set<String> getTestsToRun(final String projectDir, final dbFilePath, final List<String> sourceFilesDirs){
        GitReader gitReader = new GitReader(projectDir);
        DataStore dataStore = new MapDataStore(dbFilePath, gitReader.getBranchName());
        StoredMapping storedMapping = dataStore.getStoredMapping();
        log.info("Store commit: " + storedMapping.getCommitValue());

        if (storedMapping.getCommitValue() == null) {
            log.info("No stored commit value found. Tia hasn't previously run. Running all tests.");
            return;
        }

        // build the list of source files that have been changed since the previously analyzed commit
        List<SourceFileDiffContext> impactedSourceFiles = gitReader.buildDiffFilesContext(storedMapping.getCommitValue());

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
        Set<String> ignoredTests = new HashSet<>();
        storedMapping.getClassesImpacted().keySet().forEach( (testSuite) -> {
            if (!testsToRun.contains(testSuite)){
                ignoredTests.add(testSuite);
            }
        });

        // ignoredTests.add("com.example.CarServiceTest");

        log.debug("Ignoring tests: {}", ignoredTests);
        return ignoredTests
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
