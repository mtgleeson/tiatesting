package org.tiatesting.core.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tiatesting.core.model.DistributedRun;
import org.tiatesting.core.model.DistributedRunGroup;
import org.tiatesting.core.model.DistributedRunPlan;
import org.tiatesting.core.model.DistributedRunStatus;
import org.tiatesting.core.model.SelectionMode;

import java.nio.file.Path;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link DistributedRunRepository} directly, without the {@link JdbcDataStore} facade.
 */
class DistributedRunRepositoryTest {

    @Test
    void persistsAPlanAndReadsTheRunBack(@TempDir Path dir) {
        try (H2RepositoryFixture db = new H2RepositoryFixture(dir).withSchema()) {
            // given a run repository and a one-group plan
            DistributedRunRepository repository =
                    new DistributedRunRepository(db.dialect, db.connections, db.schemaManager);
            DistributedRunPlan plan = onePlan("run-1");

            // when the plan is persisted and the run read back
            repository.persistDistributedRunPlan(plan);
            DistributedRun run = repository.readDistributedRun("run-1");

            // then the run row round-trips as open, with no drain result stored
            assertEquals("main", run.getBranch());
            assertEquals(DistributedRunStatus.OPEN, run.getStatus());
            assertEquals(1, run.getGroupCount());
            assertNull(repository.readDistributedRunDrainResult("run-1"));
            assertEquals(1, repository.readAllDistributedRuns().size());
        }
    }

    @Test
    void electsOnlyOneSealer(@TempDir Path dir) {
        try (H2RepositoryFixture db = new H2RepositoryFixture(dir).withSchema()) {
            // given a persisted run with no groups left to complete
            DistributedRunRepository repository =
                    new DistributedRunRepository(db.dialect, db.connections, db.schemaManager);
            DistributedRun run = DistributedRun.open("run-1", "main", "commit-1", 0, 1, null,
                    0L, 1234L, SelectionMode.SELECTIVE, null);
            repository.persistDistributedRunPlan(new DistributedRunPlan(run,
                    Collections.emptyList(), Collections.emptyMap(), null));

            // when two runners try to become its sealer
            boolean first = repository.electSealer("run-1", "runner-a", 10L);
            boolean second = repository.electSealer("run-1", "runner-b", 11L);

            // then only the first wins, and the run records it
            assertTrue(first);
            assertFalse(second);
            assertEquals("runner-a", repository.readDistributedRun("run-1").getSealedBy());
        }
    }

    /**
     * Build a selective one-group plan carrying a single suite.
     *
     * @param runId the run id to plan under
     * @return the plan
     */
    static DistributedRunPlan onePlan(String runId) {
        DistributedRun run = DistributedRun.open(runId, "main", "commit-1", 1, 1, null,
                1000L, 1234L, SelectionMode.SELECTIVE, null);
        return new DistributedRunPlan(run,
                Collections.singletonList(DistributedRunGroup.pending(runId, 0, 1000L)),
                Collections.singletonMap(0, Collections.singletonList("com.example.SuiteTest")), null);
    }
}
