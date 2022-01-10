package org.tiatesting.maven;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.project.MavenProject;

import java.util.Map;

/**
 *
 * @goal prepare-agent
 * @requiresDependencyResolution compile
 */
public class AgentMojo extends TiaAgentMojo {

    /**
     * Name of the Tia Test Agent artifact.
     */
    static final String AGENT_ARTIFACT_NAME = "org.tiatesting:tia-test-agent-junit-git";

    /**
     * Map of plugin artifacts.
     *
     * @parameter expression="${plugin.artifactMap}"
     * @required
     * @readonly
     */
    protected Map<String, Artifact> pluginArtifactMap;

    /**
     * Allows us to specify property which will contains settings for JaCoCo Agent.
     * If not specified, then "argLine" would be used for "jar" packaging and
     * "tycho.testArgLine" for "eclipse-test-plugin".
     *
     */
    String propertyName;

    /**
     * @parameter default-value="${project}"
     * @readonly
     */
    private MavenProject project;

    /**
     * The file path for the project being analyzed.
     *
     * @parameter property="tiaProjectDir"
     */
    String tiaProjectDir;

    /**
     * The file path for to the saved DB containing the previous analysis of the project.
     *
     * @parameter property="tiaDBFilePath"
     */
    String tiaDBFilePath;

    /**
     * The source files directories for the project being analyzed.
     *
     * @parameter property="tiaSourceFilesDirs"
     */
    String tiaSourceFilesDirs;

    public String getAgentArtifactName(){
        return AGENT_ARTIFACT_NAME;
    }

    public Map<String, Artifact> getPluginArtifactMap(){
        return pluginArtifactMap;
    }

    public MavenProject getProject(){
        return project;
    }

    public String getPropertyName(){
        return propertyName;
    }

    @Override
    public String getTiaProjectDir(){
        return tiaProjectDir;
    }

    @Override
    public String getTiaDBFilePath(){
        return tiaDBFilePath;
    }

    @Override
    public String getTiaSourceFilesDirs() {
        return tiaSourceFilesDirs;
    }

}
