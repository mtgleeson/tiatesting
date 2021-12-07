package org.tiatesting.agent;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.description.annotation.AnnotationDescription;
import net.bytebuddy.matcher.ElementMatchers;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.junit.Ignore;
import org.tiatesting.MethodImpactAnalyzer;
import org.tiatesting.persistence.DataStore;
import org.tiatesting.persistence.MapDataStore;
import org.tiatesting.persistence.StoredMapping;
import org.tiatesting.FileImpactAnalyzer;
import org.tiatesting.vcs.SourceFileDiffContext;
import org.tiatesting.vcs.git.GitReader;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class Agent {

    private static Log log = LogFactory.getLog(Agent.class);

    public static void premain(String agentArgs, Instrumentation instrumentation) {
        //DataStore dataStore = new MapDataStore(System.getProperty("tiaDBFilePath"), System.getProperty("tiaDBFileSuffix"));
        GitReader gitReader = new GitReader("/Users/mgleeson/Documents/misc/test-java-project/.git");
        DataStore dataStore = new MapDataStore("/Users/mgleeson/Documents/misc/test-java-project", gitReader.getBranchName());
        StoredMapping storedMapping = dataStore.getStoredMapping();

        log.info("Store commit: " + dataStore.getStoredMapping().getCommitValue());

        if (storedMapping.getCommitValue() == null) {
            log.info("No stored commit value found. Tia hasn't previously run. Running all tests.");
            return;
        }

        List<SourceFileDiffContext> impactedSourceFiles = gitReader.buildDiffFilesContext(storedMapping.getCommitValue());
        File commitFromProjectDir = gitReader.checkoutSourceAtVersion(storedMapping.getCommitValue());

        new FileImpactAnalyzer(new MethodImpactAnalyzer()).getMethodsForFilesChanged(impactedSourceFiles, commitFromProjectDir);

        // TODO - delete temp folder created for checking out the source files at the old version -> commitFromProjectDir


        //Iterable<RevCommit> commits = gitReader.getCommitsSince(storedMapping.getCommitValue());
        //gitReader.getJavaFilesForCommits(commits);

        //itReader.listDiff(storedMapping.getCommitValue());
        // TODO for each commit, read the list of file changes, for each java file, read the diff and identify the methods changed.

        Set<String> ignoredTests = new HashSet<>();
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
}