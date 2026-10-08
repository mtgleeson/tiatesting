package org.tiatesting.gradle.plugin;

import org.gradle.api.Project;
import org.gradle.api.tasks.testing.Test;
import org.gradle.testfixtures.ProjectBuilder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Covers how the configurer tells that a test task runs its suites in more than one JVM - the
 * setting both the distributed refusal and the mapping warning name.
 */
class TiaTestTaskConfigurerForkingTest {

    /**
     * Gradle's defaults run one JVM, so nothing is reported.
     */
    @org.junit.jupiter.api.Test
    void aSingleForkTaskReportsNoSetting() {
        // given
        Test testTask = newTestTask();

        // when
        String setting = TiaTestTaskConfigurer.multiJvmForkingSetting(testTask);

        // then
        assertNull(setting);
    }

    /**
     * Parallel forks are named with their count.
     */
    @org.junit.jupiter.api.Test
    void parallelForksAreReported() {
        // given
        Test testTask = newTestTask();
        testTask.setMaxParallelForks(2);

        // when
        String setting = TiaTestTaskConfigurer.multiJvmForkingSetting(testTask);

        // then
        assertEquals("maxParallelForks = 2", setting);
    }

    /**
     * Restarting the fork periodically also splits the suites across JVMs.
     */
    @org.junit.jupiter.api.Test
    void periodicForkRestartsAreReported() {
        // given
        Test testTask = newTestTask();
        testTask.setForkEvery(10L);

        // when
        String setting = TiaTestTaskConfigurer.multiJvmForkingSetting(testTask);

        // then
        assertEquals("forkEvery = 10", setting);
    }

    /**
     * @return a {@code test} task on a fresh project with the {@code java} plugin applied
     */
    private static Test newTestTask() {
        Project project = ProjectBuilder.builder().build();
        project.getPlugins().apply("java");
        return (Test) project.getTasks().getByName("test");
    }
}
