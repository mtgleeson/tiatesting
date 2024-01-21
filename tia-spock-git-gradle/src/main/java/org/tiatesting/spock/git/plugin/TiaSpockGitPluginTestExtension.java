package org.tiatesting.spock.git.plugin;

import org.gradle.api.Action;
import org.gradle.api.Task;
import org.gradle.api.internal.tasks.testing.filter.DefaultTestFilter;
import org.gradle.api.logging.Logging;
import org.gradle.api.tasks.testing.Test;
import org.gradle.process.JavaForkOptions;
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension;
import org.slf4j.Logger;
import org.tiatesting.gradle.plugin.TiaBaseTaskExtension;

import java.util.Set;

public class TiaSpockGitPluginTestExtension {
    private static final Logger LOGGER = Logging.getLogger(TiaSpockGitPluginTestExtension.class);

    public TiaSpockGitPluginTestExtension(){
    }

    public <T extends Test & JavaForkOptions> void applyTo(final T task) {
        String taskName = task.getName();
        LOGGER.debug("Applying Tia to " + taskName);
        TiaBaseTaskExtension tiaProjectExtension = task.getProject().getExtensions().findByType(TiaBaseTaskExtension.class);
        TiaBaseTaskExtension tiaTaskExtension = task.getExtensions().create("tia", TiaBaseTaskExtension.class);
        JacocoTaskExtension jacocoTaskExtension = task.getExtensions().findByType(JacocoTaskExtension.class);

        Action<Task> action = new Action<Task>() {
            @Override
            public void execute(Task task) {
                Test testTask = (Test)task;
                populateTestTaskExtension(tiaProjectExtension, tiaTaskExtension);
                boolean isTiaEnabled = isEnabled(tiaTaskExtension, testTask);

                if (isTiaEnabled){
                    // set the system properties needed by Tia passed in as configuration from the Gradle plugin
                    testTask.systemProperty("tiaEnabled", true);
                    testTask.systemProperty("tiaUpdateDBMapping", tiaTaskExtension.getUpdateDBMapping());
                    testTask.systemProperty("tiaUpdateDBStats", tiaTaskExtension.getUpdateDBStats());
                    testTask.systemProperty("tiaProjectDir", tiaTaskExtension.getProjectDir());
                    testTask.systemProperty("tiaClassFilesDirs", tiaTaskExtension.getClassFilesDirs());
                    testTask.systemProperty("tiaSourceFilesDirs", tiaTaskExtension.getSourceFilesDirs());
                    testTask.systemProperty("tiaTestFilesDirs", tiaTaskExtension.getTestFilesDirs());
                    testTask.systemProperty("tiaDBFilePath", tiaTaskExtension.getDbFilePath());
                    testTask.systemProperty("tiaCheckLocalChanges", tiaTaskExtension.getCheckLocalChanges());

                    // only apply and configure the jacoco task extension if we're updating the tia DB
                    if (tiaTaskExtension.getUpdateDBMapping()) {
                        LOGGER.debug("Enabling Jacoco in TCP server mode");
                        jacocoTaskExtension.setEnabled(true);
                        jacocoTaskExtension.setOutput(JacocoTaskExtension.Output.TCP_SERVER);
                    }
                }else{
                    testTask.systemProperty("tiaEnabled", false);
                }
            }
        };

        task.doFirst(action);
    }

    /**
     * Override the task extension object properties with the project object extension.
     *
     * This allows the user to define the Tia configuration at the project level, and override it for each test task
     * configuration type like 'test' and 'integrationTest'.
     *
     * @param tiaProjectExt
     * @param tiaTaskExt
     */
    private void populateTestTaskExtension(TiaBaseTaskExtension tiaProjectExt, TiaBaseTaskExtension tiaTaskExt){
        if (tiaTaskExt.getEnabled() == null){
            tiaTaskExt.setEnabled(tiaProjectExt.getEnabled());
        }

        if (tiaTaskExt.getUpdateDBMapping() == null){
            tiaTaskExt.setUpdateDBMapping(tiaProjectExt.getUpdateDBMapping());
        }

        if (tiaTaskExt.getUpdateDBStats() == null){
            tiaTaskExt.setUpdateDBStats(tiaProjectExt.getUpdateDBStats());
        }

        if (tiaTaskExt.getProjectDir() == null){
            tiaTaskExt.setProjectDir(tiaProjectExt.getProjectDir());
        }

        if (tiaTaskExt.getClassFilesDirs() == null){
            tiaTaskExt.setClassFilesDirs(tiaProjectExt.getClassFilesDirs());
        }

        if (tiaTaskExt.getSourceFilesDirs() == null){
            tiaTaskExt.setSourceFilesDirs(tiaProjectExt.getSourceFilesDirs());
        }

        if (tiaTaskExt.getTestFilesDirs() == null){
            tiaTaskExt.setTestFilesDirs(tiaProjectExt.getTestFilesDirs());
        }

        if (tiaTaskExt.getDbFilePath() == null){
            tiaTaskExt.setDbFilePath(tiaProjectExt.getDbFilePath());
        }

        if (tiaTaskExt.getCheckLocalChanges() == null){
            tiaTaskExt.setCheckLocalChanges(tiaProjectExt.getCheckLocalChanges());
        }
    }

    /**
     * Check if Tia is enabled. Used to determine if we should load the Tia agent and analyse the
     * changes and @Ignore tests not impacted by the changes.
     *
     * Note: It's not ideal we need to cast to the DefaultTestFilter as it's the internals of Gradle and
     * could change in future versions. Another way of getting the command line --tests parameter is using the
     * @Option(option = "tests", description = "Sets test class or method name to be included, '*' is supported.")
     * https://github.com/gradle/gradle/blob/b131fefc8d9efb8e154abd09f7eb91c854df1310/subprojects/testing-base/src/main/java/org/gradle/api/tasks/testing/AbstractTestTask.java#L104
     * annotation. But again this is currently only intended for the internals of Gradle.
     * i.e. Gradle doesn't currently provide a good way to publicly expose the command line parameters.
     *
     * @param tiaTaskExtension
     * @param task
     * @return
     */
    private boolean isEnabled(final TiaBaseTaskExtension tiaTaskExtension, Test task){
        boolean enabled = tiaTaskExtension.getEnabled() != null ? tiaTaskExtension.getEnabled() : false;
        boolean updateDBMapping = tiaTaskExtension.getUpdateDBMapping() != null ? tiaTaskExtension.getUpdateDBMapping() : false;
        boolean updateDBStats = tiaTaskExtension.getUpdateDBStats() != null ? tiaTaskExtension.getUpdateDBStats() : false;
        LOGGER.warn("Tia plugin task ext: enabled: " + enabled + ", update mapping: " + updateDBMapping
                + ", update stats: " + updateDBStats);

        /**
         * If the user specified specific individual tests to run, disable Tia so those tests are run
         * and guaranteed to be the only tests to run.
         */
        if (enabled){
            Set<String> userSpecifiedTests = ((DefaultTestFilter)task.getFilter()).getCommandLineIncludePatterns();
            boolean hasUserSpecifiedTests = userSpecifiedTests != null && !userSpecifiedTests.isEmpty();

            if (hasUserSpecifiedTests){
                LOGGER.info("Users has specified tests, disabling Tia");
                enabled = false;
            }
        }

        return enabled;
    }

}
