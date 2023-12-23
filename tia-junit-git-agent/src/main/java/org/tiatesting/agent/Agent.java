package org.tiatesting.agent;

import org.junit.Ignore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tiatesting.agent.instrumentation.IgnoreTestInstrumentor;
import org.tiatesting.core.agent.AgentOptions;
import org.tiatesting.diffanalyze.selector.TestSelector;
import org.tiatesting.persistence.DataStore;
import org.tiatesting.persistence.MapDataStore;
import org.tiatesting.vcs.git.GitReader;

import java.lang.instrument.Instrumentation;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

public class Agent {

    private static final Logger log = LoggerFactory.getLogger(Agent.class);

    public static void premain(String agentArgs, Instrumentation instrumentation) {
        final AgentOptions agentOptions = new AgentOptions(agentArgs);

        //final Agent agent = Agent.getInstance(agentOptions);

        GitReader gitReader = new GitReader(agentOptions.getProjectDir());
        DataStore dataStore = new MapDataStore(agentOptions.getDBFilePath(), gitReader.getBranchName());
        TestSelector testSelector = new TestSelector();
        List<String> sourceFilesDirs = agentOptions.getSourceFilesDirs() != null ? Arrays.asList(agentOptions.getSourceFilesDirs().split(",")) : null;
        Set<String> testsToIgnore = testSelector.selectTestsToIgnore(dataStore.getStoredMapping(), gitReader, sourceFilesDirs);

        new IgnoreTestInstrumentor().ignoreTests(testsToIgnore, instrumentation, Ignore.class);
    }

}