package org.tiatesting.core.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tiatesting.core.model.MethodImpactTracker;

import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link DistributedMethodStagingRepository} directly, without the {@link JdbcDataStore}
 * facade.
 */
class DistributedMethodStagingRepositoryTest {

    @Test
    void stagesReadsAndDeletesMethodTrackersAndCoveredIds(@TempDir Path dir) {
        try (H2RepositoryFixture db = new H2RepositoryFixture(dir).withSchema()) {
            // given a staging repository and one runner's trackers and covered ids
            DistributedMethodStagingRepository repository =
                    new DistributedMethodStagingRepository(db.dialect, db.connections, db.schemaManager);
            Map<Integer, MethodImpactTracker> trackers =
                    Collections.singletonMap(7, new MethodImpactTracker("com.Foo.bar()V", 10, 20));

            // when they are staged, read back, then deleted
            repository.persistStagedMethodTrackers("run-1", trackers, new HashSet<>(Arrays.asList(7, 8)));
            Map<Integer, MethodImpactTracker> staged = repository.readStagedMethodTrackers("run-1");
            int coveredBeforeDelete = repository.readStagedCoveredMethodIds("run-1").size();
            repository.deleteStagedMethodTrackers("run-1");

            // then the staged rows round-trip and the delete clears both tables for the run
            assertEquals("com.Foo.bar()V", staged.get(7).getMethodName());
            assertEquals(2, coveredBeforeDelete);
            assertTrue(repository.readStagedMethodTrackers("run-1").isEmpty());
            assertTrue(repository.readStagedCoveredMethodIds("run-1").isEmpty());
        }
    }
}
