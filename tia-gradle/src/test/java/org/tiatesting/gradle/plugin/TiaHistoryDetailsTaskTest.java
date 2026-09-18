package org.tiatesting.gradle.plugin;

import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tiatesting.core.model.RunOrigin;
import org.tiatesting.core.model.TestRunHistoryEntry;
import org.tiatesting.core.model.TestRunTrigger;
import org.tiatesting.core.persistence.DataStore;
import org.tiatesting.core.persistence.DataStoreFactory;
import org.tiatesting.core.vcs.WorkspaceIdentity;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link TiaHistoryDetailsTask} and its registration by {@link TiaBasePlugin}.
 *
 * <p>Runs against a real embedded-H2 datastore rooted in a temp directory, rather than a stubbed
 * one, so the task's own resolution of the datastore and branch is exercised rather than bypassed -
 * a task that read from the wrong store would still pass against a stub.
 */
class TiaHistoryDetailsTaskTest {

    /**
     * Verify the task prints the full selection breakdown - summary fields and triggers - when the
     * requested id matches a row in the datastore.
     */
    @Test
    void printsTheFullBreakdownForAMatchingId(@TempDir File projectDir) {
        // given a datastore seeded with one history entry and its triggers
        String branch = "main";
        TestRunHistoryEntry entry = seedHistoryEntry(projectDir, branch);
        TiaHistoryDetailsTask task = createTask(projectDir, branch);
        task.setId(entry.getId());

        // when
        String output = runTask(task);

        // then
        assertTrue(output.contains("Test run " + entry.getId()), output);
        assertTrue(output.contains("com.example.Foo#bar"), output);
    }

    /**
     * Verify the task prints the formatter's not-found message, rather than failing the build,
     * when no schema holds a row matching the requested id.
     */
    @Test
    void printsNotFoundForAMissingId(@TempDir File projectDir) {
        // given a datastore with no row matching the id that will be looked up
        String branch = "main";
        seedHistoryEntry(projectDir, branch);
        TiaHistoryDetailsTask task = createTask(projectDir, branch);
        task.setId("not-a-real-id");

        // when
        String output = runTask(task);

        // then
        assertTrue(output.contains("No test run found with id 'not-a-real-id'"), output);
    }

    /**
     * Verify a missing {@code --id} fails the build with a clear message, rather than the task
     * silently doing nothing or throwing a null pointer exception.
     */
    @Test
    void requiresTheIdOption(@TempDir File projectDir) {
        // given a task with no --id supplied
        TiaHistoryDetailsTask task = createTask(projectDir, "main");

        // when / then
        GradleException e = assertThrows(GradleException.class, task::run);
        assertTrue(e.getMessage().contains("--id is required"), e.getMessage());
    }

    /**
     * Verify a blank {@code --id} is treated the same as a missing one, rather than being looked up
     * literally.
     */
    @Test
    void rejectsABlankId(@TempDir File projectDir) {
        // given a task with a blank --id
        TiaHistoryDetailsTask task = createTask(projectDir, "main");
        task.setId("   ");

        // when / then
        assertThrows(GradleException.class, task::run);
    }

    /**
     * Persist one history entry with a source-method trigger into the branch's embedded-H2 schema
     * rooted at {@code projectDir}, so a test can look it up through the same datastore factory the
     * task under test uses.
     *
     * @param projectDir the temp directory the embedded datastore is rooted in
     * @param branch the branch whose schema the entry is written to
     * @return the persisted entry, for the caller to assert against
     */
    private static TestRunHistoryEntry seedHistoryEntry(final File projectDir, final String branch) {
        TestRunHistoryEntry entry = TestRunHistoryEntry.create(branch, "abc123", System.currentTimeMillis(),
                5, 1, 0, 1000L, true, 200L, 10,
                RunOrigin.of(RunOrigin.SOURCE_LOCAL, "host1"), null);
        List<TestRunTrigger> triggers = Collections.singletonList(
                new TestRunTrigger(TestRunTrigger.Type.SOURCE_METHOD, "com.example.Foo#bar", 3));

        try (DataStore dataStore = buildDataStore(projectDir, branch)) {
            dataStore.persistTestRunHistoryEntry(entry);
            dataStore.persistTestRunTriggers(entry.getId(), triggers);
        }
        return entry;
    }

    /**
     * Build a {@link TiaHistoryDetailsTask}, injecting a fixed-branch workspace identity supplier
     * (so no VCS reader is needed), a datastore factory rooted at {@code projectDir}, and a
     * single-schema suffix supplier - matching how {@code TiaBasePlugin.createHistoryDetailsTask}
     * wires the real task, minus the plugin's own extension resolution.
     *
     * @param projectDir the temp directory the embedded datastore is rooted in
     * @param branch the branch the workspace identity resolves to
     * @return the constructed, fully-wired task, ready for {@link #runTask}
     */
    private static TiaHistoryDetailsTask createTask(final File projectDir, final String branch) {
        Project project = ProjectBuilder.builder().withProjectDir(projectDir).build();
        TiaHistoryDetailsTask task = project.getTasks().create("historyDetails", TiaHistoryDetailsTask.class);
        task.setWorkspaceIdentitySupplier(() -> WorkspaceIdentity.resolving(branch, null, () -> {
            throw new IllegalStateException("a configured branch must not need a VCS reader");
        }));
        task.setDataStoreFactory((b, suffix) -> buildDataStore(projectDir, b));
        task.setSchemaSuffixes(() -> Collections.singleton(null));
        return task;
    }

    /**
     * Open the embedded-H2 datastore rooted at {@code projectDir} for {@code branch}, with no
     * schema suffix - the same construction the seeding helper and the injected datastore factory
     * both use, so seeded rows and looked-up rows land in the same schema.
     *
     * @param projectDir the temp directory the embedded datastore is rooted in
     * @param branch the branch whose schema is opened
     * @return an open datastore the caller owns and closes
     */
    private static DataStore buildDataStore(final File projectDir, final String branch) {
        return DataStoreFactory.fromConfig(projectDir.getAbsolutePath(), null, null, null, null, branch, null);
    }

    /**
     * Run the task with stdout captured, since the task's whole output is what it prints.
     *
     * @param task the task to run
     * @return everything the task wrote to stdout
     */
    private static String runTask(final TiaHistoryDetailsTask task) {
        PrintStream originalOut = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(captured));
            task.run();
        } finally {
            System.setOut(originalOut);
        }
        return captured.toString();
    }
}
