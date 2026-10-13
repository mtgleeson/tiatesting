package org.tiatesting.core.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tiatesting.core.model.ClassImpactTracker;
import org.tiatesting.core.model.CoreStatsIncrement;
import org.tiatesting.core.model.MethodImpactTracker;
import org.tiatesting.core.model.TestSuiteTracker;
import org.tiatesting.core.model.TiaData;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link SealRepository} directly, without the {@link JdbcDataStore} facade.
 */
class SealRepositoryTest {

    @Test
    void sealWritesCatalogueAndCommitAndClearsUnsealedFlags(@TempDir Path dir) {
        try (H2RepositoryFixture db = new H2RepositoryFixture(dir).withSchema()) {
            // given an unsealed suite written by an earlier mapping persist
            MappingReader reader = new MappingReader(db.dialect, db.connections, db.schemaManager);
            MappingWriter writer = new MappingWriter(db.dialect, db.connections, db.schemaManager);
            LibraryRepository libraries = new LibraryRepository(db.dialect, db.connections, db.schemaManager);
            SealRepository seal = new SealRepository(db.dialect, db.connections, db.schemaManager,
                    reader, writer, libraries);
            TestSuiteTracker suite = new TestSuiteTracker("FooTest");
            suite.setClassesImpacted(Collections.singletonList(
                    new ClassImpactTracker("com/Foo.java", Collections.singletonList(1))));
            writer.persistTestSuites(Collections.singletonMap("FooTest", suite));
            boolean unsealedBeforeSeal = reader.getTestSuitesTracked().get("FooTest").isUnsealed();
            TiaData core = new TiaData();
            core.setCommitValue("c1");
            core.setBranch("main");
            core.setLastUpdated(Instant.now());

            // when the run is sealed
            seal.persistSealedRunData(new SealedRunData(core,
                    Collections.singletonMap(1, new MethodImpactTracker("com.Foo.a()V", 10, 12)),
                    Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                    CoreStatsIncrement.none(), false));

            // then the catalogue and commit land together and the flag is cleared
            assertTrue(unsealedBeforeSeal);
            assertEquals("c1", reader.getTiaCore().getCommitValue());
            assertEquals("com.Foo.a()V", reader.getMethodsTracked().get(1).getMethodName());
            assertFalse(reader.getTestSuitesTracked().get("FooTest").isUnsealed());
        }
    }
}
