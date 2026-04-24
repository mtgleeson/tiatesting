package org.tiatesting.core.library;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tiatesting.core.model.LibraryBuildMetadata;
import org.tiatesting.core.model.TrackedLibrary;
import org.tiatesting.core.persistence.DataStore;

import java.io.File;
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
                seedBaselineVersionState(newLib, config);
                seedLastReleasedLibraryVersion(newLib, config);
                log.info("Library '{}' added to tiaSourceLibs — inserting new tracked library row " +
                        "(baseline version='{}', baseline jarHash='{}', lastReleasedLibraryVersion='{}').",
                        coord, newLib.getLastSourceProjectVersion(), newLib.getLastSourceProjectJarHash(),
                        newLib.getLastReleasedLibraryVersion());
                dataStore.persistTrackedLibrary(newLib);
            } else {
                TrackedLibrary updated = buildTrackedLibraryFromConfig(coord, config);
                if (hasConfigChanged(existing, updated)) {
                    updated.setLastSourceProjectVersion(existing.getLastSourceProjectVersion());
                    updated.setLastSourceProjectJarHash(existing.getLastSourceProjectJarHash());
                    updated.setLastReleasedLibraryVersion(existing.getLastReleasedLibraryVersion());
                    log.info("Library '{}' config changed — updating tracked library row.", coord);
                    dataStore.persistTrackedLibrary(updated);
                }
            }
        }
    }

    private TrackedLibrary buildTrackedLibraryFromConfig(String coordinate, LibraryImpactAnalysisConfig config) {
        String projectDir = config.getLibraryProjectDir(coordinate);
        String sourceDirsCsv = readSourceDirsCsv(projectDir, config);
        return new TrackedLibrary(coordinate, projectDir, sourceDirsCsv, null, null);
    }

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
     */
    private boolean hasConfigChanged(TrackedLibrary existing, TrackedLibrary updated) {
        return !Objects.equals(existing.getProjectDir(), updated.getProjectDir())
                || !Objects.equals(existing.getSourceDirsCsv(), updated.getSourceDirsCsv());
    }

    /**
     * Seed the baseline {@code lastSourceProjectVersion} and {@code lastSourceProjectJarHash}
     * for a newly tracked library by resolving it on the source project's classpath. This
     * prevents the drainer from immediately draining pending batches on the first run — without
     * a baseline, the drain rules would compare against {@code null} and always evaluate to
     * "changed", producing a false green by running tests against the old JAR.
     *
     * <p>If the library cannot be resolved (e.g. not yet a dependency of the source project),
     * the baseline fields remain {@code null} and the drainer will correctly skip the library
     * because {@code resolvedJarHash} / {@code resolvedVersion} will also be {@code null}.
     */
    private void seedBaselineVersionState(TrackedLibrary newLib, LibraryImpactAnalysisConfig config) {
        if (config.getMetadataReader() == null) {
            return;
        }

        ResolvedSourceProjectLibrary resolved = resolveLibraryOnSourceProject(newLib.getGroupArtifact(), config);
        if (resolved == null) {
            log.debug("Could not resolve library '{}' on source project — baseline version/hash will be null.",
                    newLib.getGroupArtifact());
            return;
        }

        newLib.setLastSourceProjectVersion(resolved.getResolvedVersion());
        newLib.setLastSourceProjectJarHash(computeBaselineJarHash(resolved));
    }

    /**
     * Seed {@code lastReleasedLibraryVersion} (the HWM of observed released library versions)
     * from the library's current build-file version when the library is first onboarded. The
     * HWM advances strictly forward from this seed as future stamps observe higher build-file
     * versions. Leaves the field {@code null} when the library's project directory is not
     * configured or its build metadata cannot be read — the stamper handles {@code null}
     * defensively by treating the first observed version as the HWM.
     */
    private void seedLastReleasedLibraryVersion(TrackedLibrary newLib, LibraryImpactAnalysisConfig config) {
        String libraryProjectDir = config.getLibraryProjectDir(newLib.getGroupArtifact());
        if (libraryProjectDir == null || config.getMetadataReader() == null) {
            return;
        }
        List<LibraryBuildMetadata> metadata = config.getMetadataReader()
                .readLibraryBuildMetadata(libraryProjectDir, Collections.singletonList(newLib.getGroupArtifact()));
        if (metadata == null || metadata.isEmpty()) {
            log.debug("Could not read build metadata for library '{}' — lastReleasedLibraryVersion will be null.",
                    newLib.getGroupArtifact());
            return;
        }
        newLib.setLastReleasedLibraryVersion(metadata.get(0).getDeclaredVersion());
    }

    /**
     * Resolve a single library coordinate on the source project's classpath via the metadata reader.
     *
     * @return the resolved library, or {@code null} if it could not be resolved.
     */
    private ResolvedSourceProjectLibrary resolveLibraryOnSourceProject(String coordinate,
                                                                       LibraryImpactAnalysisConfig config) {
        List<ResolvedSourceProjectLibrary> resolved = config.getMetadataReader()
                .resolveLibrariesInSourceProject(config.getSourceProjectDir(),
                        Collections.singletonList(coordinate));
        return resolved.isEmpty() ? null : resolved.get(0);
    }

    /**
     * Compute a SHA-256 content hash of the resolved JAR for use as the baseline
     * {@code lastSourceProjectJarHash}. Returns {@code null} if the JAR path is not available.
     */
    private String computeBaselineJarHash(ResolvedSourceProjectLibrary resolved) {
        if (resolved.getJarFilePath() == null) {
            return null;
        }
        return PendingLibraryImpactedMethodsRecorder.computeSha256Hash(new File(resolved.getJarFilePath()));
    }
}
