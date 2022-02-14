package org.tiatesting.spock.git.plugin;

import org.gradle.api.Action;
import org.gradle.api.Task;
import org.gradle.api.internal.tasks.testing.filter.DefaultTestFilter;
import org.gradle.api.logging.Logging;
import org.gradle.api.tasks.testing.Test;
import org.gradle.process.JavaForkOptions;
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension;
import org.slf4j.Logger;

import java.util.Objects;
import java.util.Set;

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
                Test testTask = (Test)task;
                boolean isTiaEnabled = isEnabled(tiaTaskExtension, testTask);

                if (isTiaEnabled){
                    // set the system properties needed by TIA passed in as configuration from the Gradle plugin
                    testTask.systemProperty("tiaEnabled", tiaTaskExtension.isEnabled());
                    testTask.systemProperty("tiaUpdateDB", tiaTaskExtension.isUpdateDB());
                    testTask.systemProperty("tiaProjectDir", tiaTaskExtension.getProjectDir());
                    testTask.systemProperty("tiaClassFilesDirs", tiaTaskExtension.getClassFilesDirs());
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

    /**
     * Check if TIA is enabled. Used to determine if we should load the TIA agent and analaze the
     * changes and Ignore tests not impacted by the changes.
     *
     * @param tiaTaskExtension
     * @param task
     * @return
     */
    private boolean isEnabled(final TiaSpockGitTaskExtension tiaTaskExtension, Test task){
        // TODO test this!
        boolean enabled = tiaTaskExtension.isEnabled();
        boolean updateDB = tiaTaskExtension.isUpdateDB();
        Set<String> userSpecifiedTests = ((DefaultTestFilter)task.getFilter()).getCommandLineIncludePatterns();

        /**
         * TIA is enabled but we're not updating the DB. The DB is usually updated via the CI so
         * this indicates the tests are being run locally.
         * If the user specified specific individual tests to run, disable TIA so those tests are run
         * and guaranteed to be the only tests to run.
         */
        if (enabled && !updateDB){
            boolean hasUserSpecifiedTests = userSpecifiedTests != null && !userSpecifiedTests.isEmpty();
            enabled = !hasUserSpecifiedTests; // disable TIA if the user has specified tests to run
        }

        return enabled;
    }
/*
    public <T extends Task & JavaForkOptions> void applyTo(TaskCollection<T> tasks) {
        ((TaskCollection) tasks).withType(JavaForkOptions.class, (Action<T>) this::applyTo);
    }
*/
}
