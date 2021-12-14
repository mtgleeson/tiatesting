package org.tiatesting.agent;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.description.annotation.AnnotationDescription;
import net.bytebuddy.matcher.ElementMatchers;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.junit.Ignore;
import org.tiatesting.FileImpactAnalyzer;
import org.tiatesting.MethodImpactAnalyzer;
import org.tiatesting.core.agent.AgentOptions;
import org.tiatesting.core.coverage.ClassImpactTracker;
import org.tiatesting.core.coverage.MethodImpactTracker;
import org.tiatesting.persistence.DataStore;
import org.tiatesting.persistence.MapDataStore;
import org.tiatesting.persistence.StoredMapping;
import org.tiatesting.vcs.SourceFileDiffContext;
import org.tiatesting.vcs.git.GitReader;

import java.lang.instrument.Instrumentation;
import java.util.*;
import java.util.stream.Collectors;

public class Agent {

    private static Log log = LogFactory.getLog(Agent.class);

    public static void premain(String agentArgs, Instrumentation instrumentation) {
        final AgentOptions agentOptions = new AgentOptions(agentArgs);

        //final Agent agent = Agent.getInstance(agentOptions);

        //DataStore dataStore = new MapDataStore(System.getProperty("tiaDBFilePath"), System.getProperty("tiaDBFileSuffix"));
        GitReader gitReader = new GitReader("/Users/mgleeson/Documents/misc/test-java-project/.git");
        DataStore dataStore = new MapDataStore("/Users/mgleeson/Documents/misc/test-java-project", gitReader.getBranchName());
        StoredMapping storedMapping = dataStore.getStoredMapping();

        log.info("Store commit: " + storedMapping.getCommitValue());

        if (storedMapping.getCommitValue() == null) {
            log.info("No stored commit value found. Tia hasn't previously run. Running all tests.");
            return;
        }

        List<SourceFileDiffContext> impactedSourceFiles = gitReader.buildDiffFilesContext(storedMapping.getCommitValue());
        Map<String, List<MethodImpactTracker>> classesImpacted = getClassesImpacted(storedMapping);

        FileImpactAnalyzer fileImpactAnalyzer = new FileImpactAnalyzer(new MethodImpactAnalyzer());
        Set<String> methodsImpacted = fileImpactAnalyzer.getMethodsForFilesChanged(impactedSourceFiles, classesImpacted);

        // TODO convert ClassTrackerList
        Map<String, Set<String>> methodTestSuites = buildMethodToTestSuiteMap(storedMapping);

        Set<String> testsToRun = new HashSet<>();
        Set<String> ignoredTests = new HashSet<>();

        // build the list of test suites that need to be run based on the tracked methods that have been changed
        methodsImpacted.forEach( ( methodImpacted ) -> {
            testsToRun.addAll(methodTestSuites.get(methodImpacted));
        });

        // find the list of all known tracked test suites that are in the list of tests to run - this is the ignore list.
        // i.e. only ignore test suites that we have previously tracked and know haven't been impacted by the source changes.
        // this ensures any new test suites are executed.
        storedMapping.getClassesImpacted().keySet().forEach( (testSuite) -> {
            if (!testsToRun.contains(testSuite)){
                ignoredTests.add(testSuite);
            }
        });

       // ignoredTests.add("com.example.CarServiceTest");
       // ignoredTests.add("com.example.DoorServiceTest");

        log.info("Ignoring tests: " + ignoredTests);

        AnnotationDescription ignoreDescription = AnnotationDescription.Builder.ofType(Ignore.class)
                .define("value", "Ignored by TIA testing")
                .build();

        new AgentBuilder.Default()
                .type(ElementMatchers.namedOneOf(ignoredTests.toArray(new String[ignoredTests.size()])))
                .transform((builder, typeDescription, agr3, arg4) -> builder.annotateType(ignoreDescription))
                .installOn(instrumentation);
    }

    /**
     * Convert to a map for convenience in analyzing the diff files: Tracked Class Name, List<MethodImpactTracker>
     *
     * @param storedMapping
     * @return
     */
    private static Map<String, List<MethodImpactTracker>> getClassesImpacted(StoredMapping storedMapping){
        Map<String, List<MethodImpactTracker>> classesImpacted = new HashMap<>();

        for (List<ClassImpactTracker> testSuiteClassesImpacted : storedMapping.getClassesImpacted().values()){
            for (ClassImpactTracker classImpacted : testSuiteClassesImpacted) {

                if (classesImpacted.get(classImpacted.getClassName()) == null){
                    classesImpacted.put(classImpacted.getClassName(), new ArrayList<>());
                }

                classesImpacted.get(classImpacted.getClassName()).addAll(classImpacted.getMethodsImpacted());
            }
        }

        return classesImpacted;
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