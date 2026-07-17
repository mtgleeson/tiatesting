package org.tiatesting.core.library;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tiatesting.core.model.TrackedLibrary;
import org.tiatesting.core.persistence.DataStore;

import java.util.*;

/**
 * Reconciles the set of libraries declared in the user's {@code tiaSourceLibs} configuration
 * against the {@code tia_library} rows persisted in the data store. Handles three cases:
 * <ol>
 *     <li><b>New</b> — coordinate present in config but not in DB: inserts a new row. The
 *         ledger state ({@code mappingBaselineCommit}, {@code lastAppliedSeq}) starts null:
 *         the baseline is seeded by the library's first publish and the applied sequence by the
 *         first drain, so no source-project resolution is needed here.</li>
 *     <li><b>Updated</b> — coordinate present in both: updates project dir / source dirs if
 *         changed, preserving the ledger state.</li>
 *     <li><b>Removed</b> — coordinate present in DB but not in config: deletes the row
 *         (cascades the publish ledger and pending stamp rows).</li>
 * </ol>
 */
public class TrackedLibraryReconciler {

    private static final Logger log = LoggerFactory.getLogger(TrackedLibraryReconciler.class);

    /**
     * Reconcile the declared library configuration against persisted tracked-library rows.
     * Inserts new rows, updates changed rows, and deletes rows for libraries no longer in config.
     *
     * @param dataStore the persistence layer to read/write tracked libraries.
     * @param config the current library impact analysis configuration.
     * @return the reconciled map of tracked libraries keyed by {@code groupArtifact}.
     */
    public Map<String, TrackedLibrary> reconcile(DataStore dataStore, LibraryImpactAnalysisConfig config) {
        Map<String, TrackedLibrary> persisted = dataStore.readTrackedLibraries();
        Set<String> declaredCoordinates = new LinkedHashSet<>(config.getCoordinates());

        deleteRemovedLibraries(dataStore, persisted, declaredCoordinates);
        insertOrUpdateDeclaredLibraries(dataStore, persisted, declaredCoordinates, config);

        return dataStore.readTrackedLibraries();
    }

    /**
     * Delete tracked libraries that are no longer declared in the user's configuration.
     * Cascade-deletes the library's publish ledger and pending impacted method rows.
     *
     * @param dataStore the persistence layer.
     * @param persisted the currently persisted tracked libraries.
     * @param declaredCoordinates the coordinates declared in the current configuration.
     */
    private void deleteRemovedLibraries(DataStore dataStore, Map<String, TrackedLibrary> persisted,
                                        Set<String> declaredCoordinates) {
        for (String existingKey : new ArrayList<>(persisted.keySet())) {
            if (!declaredCoordinates.contains(existingKey)) {
                log.info("Library '{}' removed from tiaSourceLibs — deleting tracked library row.", existingKey);
                dataStore.deleteTrackedLibrary(existingKey);
            }
        }
    }

    /**
     * For each declared coordinate, insert a new tracked library row if it does not exist,
     * or update the existing row if the project directory or source directories have changed.
     * Config-driven updates preserve the row's ledger state ({@code mappingBaselineCommit},
     * {@code lastAppliedSeq}) — those fields are owned by the publish stamper and the post-run
     * cleanup, not by configuration.
     *
     * @param dataStore the persistence layer.
     * @param persisted the currently persisted tracked libraries.
     * @param declaredCoordinates the coordinates declared in the current configuration.
     * @param config the current library impact analysis configuration.
     */
    private void insertOrUpdateDeclaredLibraries(DataStore dataStore, Map<String, TrackedLibrary> persisted,
                                                  Set<String> declaredCoordinates,
                                                  LibraryImpactAnalysisConfig config) {
        for (String coord : declaredCoordinates) {
            TrackedLibrary existing = persisted.get(coord);

            if (existing == null) {
                TrackedLibrary newLib = buildTrackedLibraryFromConfig(coord, config);
                log.info("Library '{}' added to tiaSourceLibs — inserting new tracked library row.", coord);
                dataStore.persistTrackedLibrary(newLib);
            } else {
                TrackedLibrary updated = buildTrackedLibraryFromConfig(coord, config);
                if (hasConfigChanged(existing, updated)) {
                    updated.setMappingBaselineCommit(existing.getMappingBaselineCommit());
                    updated.setLastAppliedSeq(existing.getLastAppliedSeq());
                    log.info("Library '{}' config changed — updating tracked library row.", coord);
                    dataStore.persistTrackedLibrary(updated);
                }
            }
        }
    }

    /**
     * Build a tracked-library row from the declared configuration: the coordinate, its project
     * directory and the source directories read from its build metadata.
     *
     * @param coordinate the {@code groupId:artifactId} coordinate.
     * @param config the current library impact analysis configuration.
     * @return the config-derived tracked library row (ledger state unset).
     */
    private TrackedLibrary buildTrackedLibraryFromConfig(String coordinate, LibraryImpactAnalysisConfig config) {
        String projectDir = config.getLibraryProjectDir(coordinate);
        String sourceDirsCsv = readSourceDirsCsv(projectDir, config);
        return new TrackedLibrary(coordinate, projectDir, sourceDirsCsv);
    }

    /**
     * Read the library's source directories via the metadata reader and join them to a CSV.
     *
     * @param libraryProjectDir the library's project directory, or null when not configured.
     * @param config the current library impact analysis configuration.
     * @return the CSV of source directories, or null when unavailable.
     */
    private String readSourceDirsCsv(String libraryProjectDir, LibraryImpactAnalysisConfig config) {
        if (libraryProjectDir == null || config.getMetadataReader() == null) {
            return null;
        }
        List<String> sourceDirs = config.getMetadataReader().readSourceDirectories(libraryProjectDir);
        if (sourceDirs == null || sourceDirs.isEmpty()) {
            return null;
        }
        return String.join(",", sourceDirs);
    }

    /**
     * Check whether the project directory or source directories CSV has changed between
     * the persisted row and the current config-derived row.
     *
     * @param existing the persisted tracked library row.
     * @param updated the config-derived row.
     * @return true when the config-owned fields differ.
     */
    private boolean hasConfigChanged(TrackedLibrary existing, TrackedLibrary updated) {
        return !Objects.equals(existing.getProjectDir(), updated.getProjectDir())
                || !Objects.equals(existing.getSourceDirsCsv(), updated.getSourceDirsCsv());
    }
}
