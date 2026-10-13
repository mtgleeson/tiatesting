package org.tiatesting.core.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tiatesting.core.model.TrackedLibrary;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link LibraryRepository} directly, without the {@link JdbcDataStore} facade.
 */
class LibraryRepositoryTest {

    @Test
    void readsNoLibrariesBeforeTheLibraryTableExists(@TempDir Path dir) {
        try (H2RepositoryFixture db = new H2RepositoryFixture(dir)) {
            // given a library repository over a database with no Tia tables
            LibraryRepository repository = new LibraryRepository(db.dialect, db.connections, db.schemaManager);

            // when the tracked libraries are read
            Map<String, TrackedLibrary> libraries = repository.readTrackedLibraries();

            // then the missing table reads as no libraries rather than failing
            assertTrue(libraries.isEmpty());
        }
    }

    @Test
    void persistsReadsAndDeletesATrackedLibrary(@TempDir Path dir) {
        try (H2RepositoryFixture db = new H2RepositoryFixture(dir).withSchema()) {
            // given a library repository holding one tracked library
            LibraryRepository repository = new LibraryRepository(db.dialect, db.connections, db.schemaManager);
            repository.persistTrackedLibrary(new TrackedLibrary("com.acme:widget", "/projects/widget", "src/main/java"));

            // when it is read back, then deleted and read again
            Map<String, TrackedLibrary> beforeDelete = repository.readTrackedLibraries();
            repository.deleteTrackedLibrary("com.acme:widget");
            Map<String, TrackedLibrary> afterDelete = repository.readTrackedLibraries();

            // then the library round-trips and is gone after the delete
            assertEquals("/projects/widget", beforeDelete.get("com.acme:widget").getProjectDir());
            assertTrue(afterDelete.isEmpty());
        }
    }
}
