package org.tiatesting.gradle.plugin;

import org.gradle.api.Project;
import org.gradle.api.tasks.testing.Test;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.BeforeEach;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Resolution of the schemas a project's test tasks use.
 *
 * <p>The two callers need the same resolution for opposite reasons and count different tasks: the
 * collision guard asks which tasks could corrupt each other, so it counts only mapping owners; the
 * reporting tasks ask where there is data to show, so they count every enabled task. Both rules are
 * pinned here, because getting either wrong fails silently - a guard that counts too much refuses
 * safe builds, and reporting that counts too little hides a schema's data entirely.
 */
class TiaSchemaResolverTest {

    private Project project;
    private TiaBaseTaskExtension projectExtension;

    @BeforeEach
    void setUp() {
        // No java plugin: it would pre-create a 'test' task and these tests register their own.
        project = ProjectBuilder.builder().build();
        projectExtension = project.getExtensions().create("tia", TiaBaseTaskExtension.class);
        projectExtension.setEnabled(Boolean.TRUE);
    }

    /**
     * A project with one test task and no declared suffix reports over exactly one schema - the
     * unsuffixed one - so every reporting task behaves as it always has.
     */
    @org.junit.jupiter.api.Test
    void aSingleTestTaskYieldsTheUnsuffixedSchema() {
        // given
        testTask("test", null, true);

        // when
        Set<String> suffixes = TiaSchemaResolver.schemaSuffixes(project, projectExtension);

        // then
        assertEquals(1, suffixes.size());
        assertNull(suffixes.iterator().next());
    }

    @org.junit.jupiter.api.Test
    void distinctSuffixesAreAllReportedOver() {
        // given
        testTask("test", "unit", true);
        testTask("integrationTest", "integration", true);

        // when
        Set<String> suffixes = TiaSchemaResolver.schemaSuffixes(project, projectExtension);

        // then
        assertEquals(2, suffixes.size());
        assertTrue(suffixes.contains("unit"));
        assertTrue(suffixes.contains("integration"));
    }

    /**
     * Reporting counts a task that does not own mapping updates, because it still writes history
     * rows to its own schema - the local developer runs the history report exists to surface.
     * Excluding it would make that population invisible.
     */
    @org.junit.jupiter.api.Test
    void reportingCountsANonMappingTask() {
        // given
        testTask("test", "unit", true);
        testTask("smokeTest", "smoke", false);

        // when
        Set<String> suffixes = TiaSchemaResolver.schemaSuffixes(project, projectExtension);

        // then
        assertTrue(suffixes.contains("smoke"),
                "a history-only task's schema still holds runs worth reporting");
    }

    /**
     * The guard counts only mapping owners, because a task that writes no mapping deletes nothing
     * and cannot corrupt what it shares a schema with. Counting it would refuse a safe build.
     */
    @org.junit.jupiter.api.Test
    void theGuardIgnoresANonMappingTaskSharingASchema() {
        // given - both resolve to the same schema, but only one owns the mapping
        testTask("test", null, true);
        testTask("smokeTest", null, false);

        // when
        Map<String, List<String>> bySchema =
                TiaSchemaResolver.taskPathsBySchema(project, projectExtension, "main");

        // then
        assertEquals(1, bySchema.size());
        assertEquals(1, bySchema.values().iterator().next().size(),
                "only the mapping-owning task counts toward a collision");
    }

    @org.junit.jupiter.api.Test
    void theGuardGroupsTwoMappingTasksSharingASchema() {
        // given
        testTask("test", null, true);
        testTask("integrationTest", null, true);

        // when
        Map<String, List<String>> bySchema =
                TiaSchemaResolver.taskPathsBySchema(project, projectExtension, "main");

        // then
        assertEquals(1, bySchema.size(), "both tasks resolve to one schema");
        assertEquals(2, bySchema.values().iterator().next().size());
    }

    @org.junit.jupiter.api.Test
    void theGuardSeparatesTwoMappingTasksWithDistinctSuffixes() {
        // given
        testTask("test", "unit", true);
        testTask("integrationTest", "integration", true);

        // when
        Map<String, List<String>> bySchema =
                TiaSchemaResolver.taskPathsBySchema(project, projectExtension, "main");

        // then
        assertEquals(2, bySchema.size());
        for (List<String> paths : bySchema.values()) {
            assertEquals(1, paths.size());
        }
    }

    /**
     * The daemon-side distributed tasks derive their schema from the one distributed test task -
     * they must address the schema the runner's fork persists to, or the plan is written where no
     * runner looks for it.
     */
    @org.junit.jupiter.api.Test
    void theDistributedSuffixComesFromTheDistributedTestTask() {
        // given
        testTask("test", "unit", true);
        Test distributed = testTask("integrationTest", "integration", true);
        distributed.getExtensions().getByType(TiaBaseTaskExtension.class).setDistributed(Boolean.TRUE);

        // when / then
        assertEquals("integration",
                TiaSchemaResolver.distributedSchemaSuffix(project, projectExtension));
    }

    @org.junit.jupiter.api.Test
    void thereIsNoDistributedSuffixWhenNoTaskIsDistributed() {
        // given
        testTask("test", "unit", true);

        // when / then
        assertNull(TiaSchemaResolver.distributedSchemaSuffix(project, projectExtension));
    }

    /**
     * The report folder keeps its existing name when no suffix is declared, so an upgrade does not
     * relocate an existing project's report tree.
     */
    @org.junit.jupiter.api.Test
    void theReportFolderIsUnchangedWithoutASuffix() {
        // given / when / then
        assertEquals("main", TiaSchemaResolver.reportFolderName("main", null));
        assertEquals("main", TiaSchemaResolver.reportFolderName("main", ""));
        assertEquals("main_unit", TiaSchemaResolver.reportFolderName("main", "unit"));
    }

    /**
     * Register a Tia-enabled test task with the given suffix and mapping ownership.
     *
     * @param name the task name
     * @param schemaSuffix the schema suffix to declare, or null for none
     * @param ownsMapping whether the task updates the mapping DB
     * @return the registered task
     */
    private Test testTask(final String name, final String schemaSuffix, final boolean ownsMapping) {
        Test task = project.getTasks().create(name, Test.class);
        TiaBaseTaskExtension extension = task.getExtensions().create("tia", TiaBaseTaskExtension.class);
        extension.setSchemaSuffix(schemaSuffix);
        extension.setUpdateDBMapping(Boolean.valueOf(ownsMapping));
        return task;
    }
}
