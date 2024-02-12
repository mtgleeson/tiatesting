package org.tiatesting.maven;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.tiatesting.core.vcs.VCSReader;

public abstract class AbstractTiaMojo extends AbstractMojo {

    /**
     * Maven project.
     */
    @Parameter(property = "project", readonly = true)
    private MavenProject project;

    @Parameter(property = "tiaBuildDir", defaultValue = "${project.build.directory}/tia")
    String tiaBuildDir;

    /**
     * The file path to the root folder of the project being analyzed.
     *
     */
    @Parameter(property = "tiaProjectDir")
    String tiaProjectDir;

    /**
     * The file path for the saved DB containing the previous analysis of the project.
     */
    @Parameter(property = "tiaDBFilePath")
    String tiaDBFilePath;

    /**
     * The source files directories for the project being analyzed.
     */
    @Parameter(property = "tiaSourceFilesDirs")
    String tiaSourceFilesDirs;

    /**
     * The test files directories for the project being analyzed.
     */
    @Parameter(property = "tiaTestFilesDirs")
    String tiaTestFilesDirs;

    /**
     * Is TIA enabled?
     */
    @Parameter(property = "tiaEnabled")
    boolean tiaEnabled;

    /**
     * Should the mapping data in the TIA DB be updated with this test run?
     */
    @Parameter(property = "tiaUpdateDBMapping")
    boolean tiaUpdateDBMapping;

    /**
     * Should the stats data in the TIA DB be updated with this test run?
     */
    @Parameter(property = "tiaUpdateDBStats")
    boolean tiaUpdateDBStats;

    /**
     * Specifies the default option for whether Tia should analyse local changes when selecting tests.
     */
    @Parameter(property = "tiaCheckLocalChanges")
    boolean tiaCheckLocalChanges;

    /**
     * Specifies the server URI of the VCS system.
     */
    @Parameter(property = "tiaVcsServerUri")
    String tiaVcsServerUri;

    /**
     * Specifies the username for connecting to the VCS system.
     */
    @Parameter(property = "tiaVcsUserName")
    String tiaVcsUserName;

    /**
     * Specifies the password for connecting to the VCS system.
     */
    @Parameter(property = "tiaVcsPassword")
    String tiaVcsPassword;

    /**
     * Specifies the client name used when connecting to the VCS system.
     */
    @Parameter(property = "tiaVcsClientName")
    String tiaVcsClientName;

    public MavenProject getProject(){
        return project;
    }

    public String getTiaBuildDir() {
        return tiaBuildDir;
    }

    public String getTiaProjectDir(){
        return tiaProjectDir;
    }

    public String getTiaDBFilePath(){
        return tiaDBFilePath;
    }

    public String getTiaSourceFilesDirs() {
        return tiaSourceFilesDirs;
    }

    public String getTiaTestFilesDirs() {
        return tiaTestFilesDirs;
    }

    public boolean isTiaEnabled() {
        return tiaEnabled;
    }

    public boolean isTiaUpdateDBMapping() {
        return tiaUpdateDBMapping;
    }

    public boolean isTiaUpdateDBStats() {
        return tiaUpdateDBStats;
    }

    public boolean isTiaCheckLocalChanges() {
        return tiaCheckLocalChanges;
    }

    public String getTiaVcsServerUri() {
        return tiaVcsServerUri;
    }

    public String getTiaVcsUserName() {
        return tiaVcsUserName;
    }

    public String getTiaVcsPassword() {
        return tiaVcsPassword;
    }

    public String getTiaVcsClientName() {
        return tiaVcsClientName;
    }

    public abstract VCSReader getVCSReader();
}
