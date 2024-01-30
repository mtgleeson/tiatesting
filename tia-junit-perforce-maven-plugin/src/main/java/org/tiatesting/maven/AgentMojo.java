package org.tiatesting.maven;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.tiatesting.core.vcs.VCSReader;
import org.tiatesting.diffanalyze.selector.TestSelector;
import org.tiatesting.persistence.DataStore;
import org.tiatesting.persistence.h2.H2DataStore;
import org.tiatesting.vcs.perforce.P4Reader;

import java.io.FileWriter;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Mojo(name = "prepare-agent", defaultPhase = LifecyclePhase.INITIALIZE, requiresDependencyResolution = ResolutionScope.RUNTIME, threadSafe = true)
public class AgentMojo extends AbstractTiaAgentMojo {

    /**
     * Name of the Tia Test Agent artifact.
     */
    static final String AGENT_ARTIFACT_NAME = "org.tiatesting:tia-junit-perforce-agent";

    /**
     * Map of plugin artifacts.
     */
    @Parameter(property = "plugin.artifactMap", required = true, readonly = true)
    protected Map<String, Artifact> pluginArtifactMap;

    public String getAgentArtifactName() {
        return AGENT_ARTIFACT_NAME;
    }

    public Map<String, Artifact> getPluginArtifactMap() {
        return pluginArtifactMap;
    }

    @Override
    public VCSReader getVCSReader() {
        return null; // not used
    }

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        super.execute();
        /*
        P4Reader gitReader = new P4Reader(true, getTiaVcsServerUri(), getTiaVcsUserName(), getTiaVcsPassword(), getTiaVcsClientName());
        DataStore dataStore = new H2DataStore(getTiaDBFilePath(), gitReader.getBranchName());
System.setProperty("hellow", "yesnoa");
        long startQueryTime = System.currentTimeMillis();
        getLog().info("reading tia db from mojo");

        List<String> sourceFilesDirs = getTiaSourceFilesDirs() != null ? Arrays.asList(getTiaSourceFilesDirs().split(",")) : null;
        List<String> testFilesDirs = getTiaTestFilesDirs() != null ? Arrays.asList(getTiaTestFilesDirs().split(",")) : null;

        TestSelector testSelector = new TestSelector(dataStore);
        Set<String> testsToIgnore = testSelector.selectTestsToIgnore(gitReader, sourceFilesDirs, testFilesDirs, isTiaCheckLocalChanges());

        FileWriter fileWriter = null;
        try {
            fileWriter = new FileWriter("E:\\p4\\fos\\mgleeson_streams_devfuture\\apps\\ut\\utas\\tests\\functional\\junit\\target\\tia\\ignored-tests.txt");
            for (String str : testsToIgnore) {
                fileWriter.write(str + System.lineSeparator());
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            try {
                fileWriter.close();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        getLog().info("finished reading tia db from mojo: " + (System.currentTimeMillis() - startQueryTime) / 1000);
         */
    }


}