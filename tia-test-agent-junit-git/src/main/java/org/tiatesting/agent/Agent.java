package org.tiatesting.agent;

import org.junit.Ignore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tiatesting.agent.instrumentation.IgnoreTestInstrumentor;
import org.tiatesting.core.agent.AgentOptions;
import org.tiatesting.core.coverage.ClassImpactTracker;
import org.tiatesting.core.coverage.MethodImpactTracker;
import org.tiatesting.core.diff.SourceFileDiffContext;
import org.tiatesting.diffanalyze.FileImpactAnalyzer;
import org.tiatesting.diffanalyze.MethodImpactAnalyzer;
import org.tiatesting.persistence.DataStore;
import org.tiatesting.persistence.MapDataStore;
import org.tiatesting.persistence.StoredMapping;
import org.tiatesting.vcs.git.GitReader;

import java.lang.instrument.Instrumentation;
import java.util.*;

public class Agent {

    private static final Logger log = LoggerFactory.getLogger(Agent.class);

    public static void premain(String agentArgs, Instrumentation instrumentation) {
        final AgentOptions agentOptions = new AgentOptions(agentArgs);

        //final Agent agent = Agent.getInstance(agentOptions);

        GitReader gitReader = new GitReader(agentOptions.getProjectDir());
        DataStore dataStore = new MapDataStore(agentOptions.getDBFilePath(), gitReader.getBranchName());
        StoredMapping storedMapping = dataStore.getStoredMapping();
        log.info("Stored DB commit: " + storedMapping.getCommitValue());

        if (storedMapping.getCommitValue() == null) {
            log.info("No stored commit value found. Tia hasn't previously run. Running all tests.");
            return;
        }

        // build the list of source files that have been changed since the previously analyzed commit
        List<SourceFileDiffContext> impactedSourceFiles = gitReader.buildDiffFilesContext(storedMapping.getCommitValue());

        // For the source files that have changed, do a diff to find the methods that have changed
        FileImpactAnalyzer fileImpactAnalyzer = new FileImpactAnalyzer(new MethodImpactAnalyzer());
        List<String> sourceFilesDirs = agentOptions.getSourceFilesDirs() != null ? Arrays.asList(agentOptions.getSourceFilesDirs().split(",")) : null;
        Set<String> methodsImpacted = fileImpactAnalyzer.getMethodsForFilesChanged(impactedSourceFiles, storedMapping,
                sourceFilesDirs);

        // build the list of test suites that need to be run based on the tracked methods that have been changed
        Map<String, Set<String>> methodTestSuites = buildMethodToTestSuiteMap(storedMapping);
        Set<String> testsToRun = new HashSet<>();
        methodsImpacted.forEach( ( methodImpacted ) -> {
            testsToRun.addAll(methodTestSuites.get(methodImpacted));
        });
        log.debug("Selected tests to run: {}", testsToRun);

        // add the tests that failed on the previous run - force them to be re-run
        testsToRun.addAll(storedMapping.getTestSuitesFailed());
        log.info("Running previously failed tests: {}", storedMapping.getTestSuitesFailed());

        // find the list of all known tracked test suites that are in the list of tests to run - this is the ignore list.
        // i.e. only ignore test suites that we have previously tracked and know haven't been impacted by the source changes.
        // this ensures any new test suites are executed.
        Set<String> ignoredTests = new HashSet<>();
        storedMapping.getClassesImpacted().keySet().forEach( (testSuite) -> {
            if (!testsToRun.contains(testSuite)){
                ignoredTests.add(testSuite);
            }
        });

        log.debug("Ignoring tests: {}", ignoredTests);
        new IgnoreTestInstrumentor().ignoreTests(ignoredTests, instrumentation, Ignore.class);
    }

    /**
     * Convert to a map containing a list of test suites for each impacted method.
     * Use this for convenience lookup when finding the list of test suites to ignore for previously tracked methods
     * that have been changed in the diff.
     *
     * @param storedMapping
     * @return
     */
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