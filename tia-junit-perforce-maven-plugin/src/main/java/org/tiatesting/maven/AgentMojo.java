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
        return new P4Reader(true, getTiaVcsServerUri(), getTiaVcsUserName(), getTiaVcsPassword(), getTiaVcsClientName());
    }

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        super.execute();
    }


}