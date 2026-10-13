package org.tiatesting.core.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tiatesting.core.model.DistributedRunGroup;
import org.tiatesting.core.model.DistributedRunGroupStatus;

import java.nio.file.Path;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Exercises {@link DistributedRunGroupRepository} directly, without the {@link JdbcDataStore}
 * facade.
 */
class DistributedRunGroupRepositoryTest {

    @Test
    void claimsThePlannedGroupOnceAndReadsItsSuites(@TempDir Path dir) {
        try (H2RepositoryFixture db = new H2RepositoryFixture(dir).withSchema()) {
            // given a persisted one-group plan
            new DistributedRunRepository(db.dialect, db.connections, db.schemaManager)
                    .persistDistributedRunPlan(DistributedRunRepositoryTest.onePlan("run-1"));
            DistributedRunGroupRepository repository =
                    new DistributedRunGroupRepository(db.dialect, db.connections, db.schemaManager);

            // when two runners claim in turn
            DistributedRunGroup claimed = repository.claimNextPendingGroup("run-1", "runner-a", 5000L);
            DistributedRunGroup none = repository.claimNextPendingGroup("run-1", "runner-b", 5100L);

            // then the first gets group 0 with its suite and the second finds nothing left
            assertEquals(0, claimed.getGroupNumber());
            assertEquals(DistributedRunGroupStatus.CLAIMED, claimed.getStatus());
            assertNull(none);
            assertEquals(Collections.singletonList("com.example.SuiteTest"),
                    repository.readDistributedRunGroupSuites("run-1", 0));
            assertEquals("runner-a", repository.readDistributedRunGroups("run-1").get(0).getRunnerKey());
        }
    }
}
