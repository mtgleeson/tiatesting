package org.tiatesting.core.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tiatesting.core.model.RunOrigin;
import org.tiatesting.core.model.SelectionMode;
import org.tiatesting.core.model.TestRunHistoryEntry;
import org.tiatesting.core.model.TestRunSelectionDetails;
import org.tiatesting.core.model.TestRunTrigger;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link TestRunHistoryRepository} directly, without the {@link JdbcDataStore} facade.
 */
class TestRunHistoryRepositoryTest {

    @Test
    void persistsAndReadsHistoryRowsAndTheirTriggers(@TempDir Path dir) {
        try (H2RepositoryFixture db = new H2RepositoryFixture(dir).withSchema()) {
            // given a history repository and a run with two triggers
            TestRunHistoryRepository repository =
                    new TestRunHistoryRepository(db.dialect, db.connections, db.schemaManager);
            List<TestRunTrigger> triggers = Arrays.asList(
                    new TestRunTrigger(TestRunTrigger.Type.SOURCE_METHOD, "Foo.save", 3, 5),
                    new TestRunTrigger(TestRunTrigger.Type.STATIC_RULE, "R", null, 9));
            TestRunSelectionDetails details = new TestRunSelectionDetails(triggers, 1, 2, 0, 3, 4, 5, SelectionMode.SELECTIVE);
            TestRunHistoryEntry entry = TestRunHistoryEntry.create("main", "c1", 1000L,
                    10, 20, 0, 5000L, true, 0L, 0, RunOrigin.of(RunOrigin.SOURCE_LOCAL, "host"), details, false);

            // when the row and its triggers are persisted and read back
            repository.persistTestRunHistoryEntry(entry);
            repository.persistTestRunTriggers(entry.getId(), triggers);
            List<TestRunHistoryEntry> history = repository.readTestRunHistory();
            Map<String, List<TestRunTrigger>> byId =
                    repository.readTestRunTriggersByHistoryId(Collections.singleton(entry.getId()));

            // then the row and its triggers round-trip, triggers ordered by suite count descending
            assertEquals(1, history.size());
            assertEquals(entry.getId(), history.get(0).getId());
            assertEquals(Integer.valueOf(5), history.get(0).getNumPendingLibrary());
            List<TestRunTrigger> read = byId.get(entry.getId());
            assertEquals(2, read.size());
            assertEquals("R", read.get(0).getName());
            assertEquals("Foo.save", read.get(1).getName());
        }
    }

    @Test
    void readsNoTriggersForAnEmptyIdSet(@TempDir Path dir) {
        try (H2RepositoryFixture db = new H2RepositoryFixture(dir).withSchema()) {
            // given a history repository with no rows
            TestRunHistoryRepository repository =
                    new TestRunHistoryRepository(db.dialect, db.connections, db.schemaManager);

            // when triggers are read for no history ids
            Map<String, List<TestRunTrigger>> byId = repository.readTestRunTriggersByHistoryId(Collections.emptySet());

            // then nothing is returned
            assertTrue(byId.isEmpty());
        }
    }
}
