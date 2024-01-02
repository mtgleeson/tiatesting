package org.tiatesting.spock.git.plugin;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.dsl.DependencyHandler;
import org.gradle.api.logging.Logging;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.tasks.testing.Test;
import org.slf4j.Logger;

import java.util.Iterator;

public class TiaSpockGitPlugin implements Plugin<Project> {

    private static final Logger LOGGER = Logging.getLogger(TiaSpockGitPlugin.class);

    private Project project;

    @Override
    public void apply(Project project) {
        this.project = project;
        TiaSpockGitPluginExtension tiaExtension = project.getExtensions().create("tia", TiaSpockGitPluginExtension.class);

        // if any of the test plugins have Tia enabled and are updating the DB then when need to load/apply the jacoco plugin
        for (final Iterator<Test> i = project.getTasks().withType(Test.class).iterator(); i.hasNext();) {
            Test testTask = i.next();
            String enabledPropName = testTask.getName()+ ".tiaEnabled";
            String updateDBPropName = testTask.getName()+ ".tiaUpdateDB";

            if (project.hasProperty(enabledPropName) && project.hasProperty(updateDBPropName)){
                boolean tiaEnabled = Boolean.valueOf((String)project.property(enabledPropName));
                boolean tiaUpdateDB = Boolean.valueOf((String)project.property(updateDBPropName));

                if (tiaEnabled && tiaUpdateDB){
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

        applyToDefaultTasks(tiaExtension);
    }

    private void applyToDefaultTasks(TiaSpockGitPluginExtension extension) {
        project.getTasks().withType(Test.class).configureEach(extension::applyTo);
    }
}