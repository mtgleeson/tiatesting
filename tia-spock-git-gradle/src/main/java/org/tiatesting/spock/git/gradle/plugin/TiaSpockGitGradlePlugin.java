package org.tiatesting.spock.git.gradle.plugin;

import org.gradle.api.Project;
import org.gradle.api.artifacts.dsl.DependencyHandler;
import org.gradle.api.logging.Logging;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.tasks.testing.Test;
import org.slf4j.Logger;
import org.tiatesting.core.vcs.VCSReader;
import org.tiatesting.gradle.plugin.TiaBasePlugin;
import org.tiatesting.vcs.git.GitReader;

import java.util.List;

// Gradle locates plugin classes via META-INF/gradle-plugins/*.properties (string reference),
// so the IDE can't see the usage and flags the class as unused.
@SuppressWarnings("unused")
public class TiaSpockGitGradlePlugin extends TiaBasePlugin {

    private static final Logger LOGGER = Logging.getLogger(TiaSpockGitGradlePlugin.class);

    private Project project;

    @Override
    public void apply(Project project) {
        super.apply(project);
        this.project = project;

        // only apply the Spock Git Plugin to the test tasks if the user is executing a test task
        List<String> taskNames =  project.getGradle().getStartParameter().getTaskNames();
        for (Test task : project.getTasks().withType(Test.class)) {
            if (taskNames.contains(task.getName())){
                applyPluginToTestTasks(project);
                break;
            }
        }
    }

    @Override
    public VCSReader getVCSReader() {
        return new GitReader(getProjectDir());
    }

    private void applyPluginToTestTasks(Project project) {
        TiaSpockGitGradlePluginTestExtension tiaTestExtension = project.getExtensions().create("tiaTest", TiaSpockGitGradlePluginTestExtension.class);

        // if any of the test plugins have Tia enabled and are updating the DB then when need to load/apply the jacoco plugin
        for (Test testTask : project.getTasks().withType(Test.class)) {
            String enabledPropName = testTask.getName()+ ".tiaEnabled";
            String updateDBMappingPropName = testTask.getName()+ ".tiaUpdateDBMapping";

            if (project.hasProperty(enabledPropName) && project.hasProperty(updateDBMappingPropName)){
                boolean tiaEnabled = Boolean.parseBoolean((String) project.property(enabledPropName));
                boolean tiaUpdateDBMapping = Boolean.parseBoolean((String) project.property(updateDBMappingPropName));

                if (tiaEnabled && tiaUpdateDBMapping){
                    LOGGER.warn("TiaSpockGitGradlePlugin: applying Jacoco plugin");
                    project.getPluginManager().apply("jacoco");
                    break;
                }
            }
        }

        // add the plugin itself as a test dependency for the calling project. This is required so the Spock IGlobalExtension
        // file under META-INF is added to the test class path.
        DependencyHandler dependencies = project.getDependencies();
        dependencies.add(JavaPlugin.TEST_IMPLEMENTATION_CONFIGURATION_NAME, "org.tiatesting:tia-spock-git-gradle:0.1.17");

        applyToDefaultTasks(tiaTestExtension);
    }

    private void applyToDefaultTasks(TiaSpockGitGradlePluginTestExtension extension) {
        project.getTasks().withType(Test.class).configureEach(extension::applyTo);
    }
}