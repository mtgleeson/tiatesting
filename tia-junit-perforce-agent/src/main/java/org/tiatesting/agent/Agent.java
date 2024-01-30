package org.tiatesting.agent;

import org.junit.Ignore;
import org.omg.CORBA.SystemException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tiatesting.agent.instrumentation.IgnoreTestInstrumentor;
import org.tiatesting.core.agent.AgentOptions;
import org.tiatesting.diffanalyze.selector.TestSelector;
import org.tiatesting.persistence.DataStore;
import org.tiatesting.persistence.h2.H2DataStore;
import org.tiatesting.vcs.perforce.P4Reader;

import java.lang.instrument.Instrumentation;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

public class Agent {

    private static final Logger log = LoggerFactory.getLogger(Agent.class);

    public static void premain(String agentArgs, Instrumentation instrumentation) {
        final AgentOptions agentOptions = new AgentOptions(agentArgs);
        P4Reader gitReader = new P4Reader(true, agentOptions.getVcsServerUri(), agentOptions.getVcsUserName(), agentOptions.getVcsPassword(), agentOptions.getVcsClientName());
        DataStore dataStore = new H2DataStore(agentOptions.getDBFilePath(), gitReader.getBranchName());
        List<String> sourceFilesDirs = agentOptions.getSourceFilesDirs() != null ? Arrays.asList(agentOptions.getSourceFilesDirs().split(",")) : null;
        List<String> testFilesDirs = agentOptions.getTestFilesDirs() != null ? Arrays.asList(agentOptions.getTestFilesDirs().split(",")) : null;
        boolean checkLocalChanges = Boolean.valueOf(agentOptions.getCheckLocalChanges());
        //boolean updateDB = Boolean.valueOf(agentOptions.getUpdateDB());

        TestSelector testSelector = new TestSelector(dataStore);
        Set<String> testsToIgnore = testSelector.selectTestsToIgnore(gitReader, sourceFilesDirs, testFilesDirs, checkLocalChanges);

        new IgnoreTestInstrumentor().ignoreTests(testsToIgnore, instrumentation, Ignore.class);
    }
}