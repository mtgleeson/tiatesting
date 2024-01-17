package org.tiatesting.spock.git.plugin;

import org.gradle.api.Project;
import org.gradle.api.artifacts.dsl.DependencyHandler;
import org.gradle.api.logging.Logging;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.tasks.testing.Test;
import org.slf4j.Logger;
import org.tiatesting.core.vcs.VCSReader;
import org.tiatesting.gradle.plugin.TiaBasePlugin;
import org.tiatesting.vcs.git.GitReader;

import java.util.Iterator;

public class TiaSpockGitPlugin extends TiaBasePlugin {

    private static final Logger LOGGER = Logging.getLogger(TiaSpockGitPlugin.class);

    private Project project;

    @Override
    public void apply(Project project) {
        super.apply(project);
        this.project = project;
        applyPluginToTestTasks(project);
    }

    @Override
    public VCSReader getVCSReader() {
        return new GitReader(getProjectDir());
    }

    private void applyPluginToTestTasks(Project project) {
        TiaSpockGitPluginTestExtension tiaTestExtension = project.getExtensions().create("tiaTest", TiaSpockGitPluginTestExtension.class);

        // if any of the test plugins have Tia enabled and are updating the DB then when need to load/apply the jacoco plugin
        for (final Iterator<Test> i = project.getTasks().withType(Test.class).iterator(); i.hasNext();) {
            Test testTask = i.next();
            String enabledPropName = testTask.getName()+ ".tiaEnabled";
            String updateDBMappingPropName = testTask.getName()+ ".tiaUpdateDBMapping";

            if (project.hasProperty(enabledPropName) && project.hasProperty(updateDBMappingPropName)){
                boolean tiaEnabled = Boolean.valueOf((String) project.property(enabledPropName));
                boolean tiaUpdateDBMapping = Boolean.valueOf((String) project.property(updateDBMappingPropName));

                if (tiaEnabled && tiaUpdateDBMapping){
                    LOGGER.warn("TiaSpockGitPlugin: applying Jacoco plugin");
                    project.getPluginManager().apply("jacoco");
                    break;
                }
            }
        }

        // add the plugin itself as a test dependency for the calling project. This is required so the Spock IGlobalExtension
        // file under META-INF is added to the test class path.
        DependencyHandler dependencies = project.getDependencies();
        dependencies.add(JavaPlugin.TEST_IMPLEMENTATION_CONFIGURATION_NAME, "org.tiatesting:tia-spock-git-gradle:1.1.0");

        applyToDefaultTasks(tiaTestExtension);
    }

    private void applyToDefaultTasks(TiaSpockGitPluginTestExtension extension) {
        project.getTasks().withType(Test.class).configureEach(extension::applyTo);
    }
}