package org.tiatesting.maven;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;

import java.util.Map;

@Mojo(name = "prepare-agent", defaultPhase = LifecyclePhase.INITIALIZE, requiresDependencyResolution = ResolutionScope.RUNTIME, threadSafe = true)
public class AgentMojo extends TiaAgentMojo {

    /**
     * Name of the Tia Test Agent artifact.
     */
    static final String AGENT_ARTIFACT_NAME = "org.tiatesting:tia-test-agent-junit-git";

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

}