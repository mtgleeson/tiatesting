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
 *     <li><b>New</b> — coordinate present in config but not in DB: inserts a new row.</li>
 *     <li><b>Updated</b> — coordinate present in both: updates project dir / source dirs if changed.</li>
 *     <li><b>Removed</b> — coordinate present in DB but not in config: deletes the row (cascades pending rows).</li>
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
     * Cascade-deletes any pending impacted method rows for the removed library.
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
                    updated.setLastSourceProjectVersion(existing.getLastSourceProjectVersion());
                    updated.setLastSourceProjectJarHash(existing.getLastSourceProjectJarHash());
                    log.info("Library '{}' config changed — updating tracked library row.", coord);
                    dataStore.persistTrackedLibrary(updated);
                }
            }
        }
    }

    /**
     * Build a {@link TrackedLibrary} from a coordinate and the current config.
     * Project dir and source dirs are derived from the config's source project directory.
     */
    private TrackedLibrary buildTrackedLibraryFromConfig(String coordinate, LibraryImpactAnalysisConfig config) {
        return new TrackedLibrary(coordinate, config.getSourceProjectDir(), null, null, null);
    }

    /**
     * Check whether the project directory or source directories CSV has changed between
     * the persisted row and the current config-derived row.
     */
    private boolean hasConfigChanged(TrackedLibrary existing, TrackedLibrary updated) {
        return !Objects.equals(existing.getProjectDir(), updated.getProjectDir())
                || !Objects.equals(existing.getSourceDirsCsv(), updated.getSourceDirsCsv());
    }
}
