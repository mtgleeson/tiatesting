package org.tiatesting.spock.git.plugin;

import org.gradle.api.Action;
import org.gradle.api.Task;
import org.gradle.api.logging.Logging;
import org.gradle.api.tasks.testing.Test;
import org.gradle.process.JavaForkOptions;
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension;
import org.slf4j.Logger;

import java.util.Objects;

public class TiaSpockGitPluginExtension {
    private static final Logger LOGGER = Logging.getLogger(TiaSpockGitPluginExtension.class);

    public <T extends Task & JavaForkOptions> void applyTo(final T task) {
        String taskName = task.getName();
        LOGGER.debug("Applying Tia to " + taskName);
        TiaSpockGitTaskExtension tiaTaskExtension = task.getExtensions().create("tia", TiaSpockGitTaskExtension.class);
        JacocoTaskExtension jacocoTaskExtension = task.getExtensions().findByType(JacocoTaskExtension.class);
        Objects.requireNonNull(tiaTaskExtension);

        Action<Task> action = new Action<Task>() {
            @Override
            public void execute(Task task) {
                boolean isTiaEnabled = tiaTaskExtension.isEnabled();
                if (isTiaEnabled){
                    Test testTask = (Test)task;
                    // set the system properties needed by TIA passed in as configuration from the Gradle plugin
                    testTask.systemProperty("tiaEnabled", tiaTaskExtension.isEnabled());
                    testTask.systemProperty("tiaProjectDir", tiaTaskExtension.getProjectDir());
                    testTask.systemProperty("tiaClassFilesDir", tiaTaskExtension.getClassFilesDir());
                    testTask.systemProperty("tiaSourceFilesDirs", tiaTaskExtension.getSourceFilesDirs());
                    testTask.systemProperty("tiaDBFilePath", tiaTaskExtension.getDbFilePath());
                    testTask.systemProperty("tiaDBPersistenceStrategy", tiaTaskExtension.getDbPersistenceStrategy());

                    LOGGER.debug("Enabling Jacoco in TCP server mode");
                    jacocoTaskExtension.setEnabled(true);
                    jacocoTaskExtension.setOutput(JacocoTaskExtension.Output.TCP_SERVER);
                }
            }
        };

        task.doFirst(action);
    }
/*
    public <T extends Task & JavaForkOptions> void applyTo(TaskCollection<T> tasks) {
        ((TaskCollection) tasks).withType(JavaForkOptions.class, (Action<T>) this::applyTo);
    }
*/
}
