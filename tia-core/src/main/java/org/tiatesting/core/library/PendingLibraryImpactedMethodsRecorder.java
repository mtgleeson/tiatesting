package org.tiatesting.core.library;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tiatesting.core.model.LibraryBuildMetadata;
import org.tiatesting.core.model.PendingLibraryImpactedMethod;
import org.tiatesting.core.model.TrackedLibrary;
import org.tiatesting.core.persistence.DataStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * Stamps and persists pending impacted source method IDs for a library change.
 * Called after method-impact analysis identifies which source methods were changed
 * in a library's source directory. The stamp captures the library's declared HEAD
 * version (and JAR content hash for SNAPSHOT versions) so the drainer can later
 * determine when the consuming project has caught up.
 */
public class PendingLibraryImpactedMethodsRecorder {

    private static final Logger log = LoggerFactory.getLogger(PendingLibraryImpactedMethodsRecorder.class);

    /**
     * Stamp and persist pending impacted method IDs for a library change.
     * Reads the library's declared version from its build file, computes
     * a JAR hash for SNAPSHOT versions, and merges the new method IDs into
     * any existing pending batch for the same {@code (groupArtifact, stampVersion)}.
     *
     * @param dataStore the persistence layer.
     * @param trackedLibrary the tracked library whose source changed.
     * @param impactedMethodIds the source method IDs impacted by the library change.
     * @param libraryConfig the library impact analysis configuration.
     */
    public void recordPendingImpactedMethods(DataStore dataStore, TrackedLibrary trackedLibrary,
                                             Set<Integer> impactedMethodIds,
                                             LibraryImpactAnalysisConfig libraryConfig) {
        if (impactedMethodIds == null || impactedMethodIds.isEmpty()) {
            log.debug("No impacted methods for library '{}', skipping pending stamp.",
                    trackedLibrary.getGroupArtifact());
            return;
        }

        String stampVersion = readDeclaredVersionForLibrary(trackedLibrary, libraryConfig);
        if (stampVersion == null) {
            log.warn("Could not determine declared version for library '{}' —  skipping pending stamp.",
                    trackedLibrary.getGroupArtifact());
            return;
        }

        String stampJarHash = null;
        boolean unknownNextVersion = false;

        if (isSnapshotVersion(stampVersion)) {
            stampJarHash = computeSnapshotStampJarHash(trackedLibrary, libraryConfig);
        } else {
            unknownNextVersion = classifyReleaseStampAndAdvanceHwm(stampVersion, trackedLibrary,
                    libraryConfig.getVersionPolicy(), dataStore);
        }

        persistPendingMethods(dataStore, trackedLibrary.getGroupArtifact(),
                stampVersion, stampJarHash, unknownNextVersion, impactedMethodIds);
    }

    /**
     * For release-version stamps, classify the stamp against the library's high water mark
     * and policy, advance the high water mark when the stamp exceeds it (only under
     * {@link LibraryVersionPolicy#BUMP_AT_RELEASE}), and return the value of
     * {@code unknownNextVersion} for the stamp row.
     *
     * <p>The high water mark is only maintained under {@code BUMP_AT_RELEASE} — that policy is
     * the only consumer. Under {@code BUMP_AFTER_RELEASE} the field is left {@code null} for
     * the lifetime of the library to avoid surfacing data that would grow stale as new releases
     * happen between stamp events. See {@code WIKI.md} for the full model.
     */
    private boolean classifyReleaseStampAndAdvanceHwm(String stampVersion, TrackedLibrary trackedLibrary,
                                                       LibraryVersionPolicy policy, DataStore dataStore) {
        if (policy != LibraryVersionPolicy.BUMP_AT_RELEASE) {
            return false;
        }

        String priorHwm = trackedLibrary.getLastReleasedLibraryVersion();
        int cmp = (priorHwm == null) ? 1
                : PendingLibraryImpactedMethodsDrainer.compareVersions(stampVersion, priorHwm);

        boolean unknownNextVersion;
        if (cmp > 0) {
            unknownNextVersion = false;
        } else if (cmp == 0) {
            unknownNextVersion = true;
        } else {
            log.warn("Library '{}' version '{}' regressed below observed high water mark '{}' — holding stamp conservatively.",
                    trackedLibrary.getGroupArtifact(), stampVersion, priorHwm);
            unknownNextVersion = true;
        }

        if (cmp > 0) {
            trackedLibrary.setLastReleasedLibraryVersion(stampVersion);
            dataStore.persistTrackedLibrary(trackedLibrary);
            log.debug("Advanced lastReleasedLibraryVersion for '{}' to '{}'.",
                    trackedLibrary.getGroupArtifact(), stampVersion);
        }

        return unknownNextVersion;
    }

    /**
     * Read the library's declared HEAD version from its build file via the metadata reader.
     */
    private String readDeclaredVersionForLibrary(TrackedLibrary trackedLibrary,
                                                  LibraryImpactAnalysisConfig libraryConfig) {
        if (trackedLibrary.getProjectDir() == null) {
            return null;
        }

        List<String> coordinates = Collections.singletonList(trackedLibrary.getGroupArtifact());
        List<LibraryBuildMetadata> metadata = libraryConfig.getMetadataReader()
                .readLibraryBuildMetadata(trackedLibrary.getProjectDir(), coordinates);

        if (metadata.isEmpty()) {
            return null;
        }

        return metadata.get(0).getDeclaredVersion();
    }

    /**
     * For SNAPSHOT versions, compute a SHA-256 hash of the resolved JAR file so the
     * drainer can detect when the JAR content has changed even if the version string
     * remains the same.
     */
    private String computeSnapshotStampJarHash(TrackedLibrary trackedLibrary,
                                                LibraryImpactAnalysisConfig libraryConfig) {
        List<String> coordinates = Collections.singletonList(trackedLibrary.getGroupArtifact());
        List<ResolvedSourceProjectLibrary> resolved = libraryConfig.getMetadataReader()
                .resolveLibrariesInSourceProject(libraryConfig.getSourceProjectDir(), coordinates);

        if (resolved.isEmpty() || resolved.get(0).getJarFilePath() == null) {
            log.debug("Could not resolve JAR for SNAPSHOT stamp of '{}', hash will be null.",
                    trackedLibrary.getGroupArtifact());
            return null;
        }

        return computeSha256Hash(new File(resolved.get(0).getJarFilePath()));
    }

    /**
     * Persist new impacted method IDs as pending rows. The data store uses MERGE
     * semantics so duplicate {@code (groupArtifact, stampVersion, methodId)} rows
     * are handled without error.
     */
    private void persistPendingMethods(DataStore dataStore, String groupArtifact,
                                       String stampVersion, String stampJarHash,
                                       boolean unknownNextVersion, Set<Integer> newMethodIds) {
        PendingLibraryImpactedMethod pending = new PendingLibraryImpactedMethod(
                groupArtifact, stampVersion, stampJarHash, newMethodIds);
        pending.setUnknownNextVersion(unknownNextVersion);

        dataStore.persistPendingLibraryImpactedMethods(pending);
        log.info("Stamped {} pending impacted methods for library '{}' at version '{}' (unknownNextVersion={}).",
                newMethodIds.size(), groupArtifact, stampVersion, unknownNextVersion);
    }

    /**
     * Check whether a version string represents a SNAPSHOT build.
     */
    private boolean isSnapshotVersion(String version) {
        return version != null && version.toUpperCase().endsWith("-SNAPSHOT");
    }

    /**
     * Compute the SHA-256 hash of a file's contents, returned as a lowercase hex string.
     */
    static String computeSha256Hash(File file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (FileInputStream fis = new FileInputStream(file)) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = fis.read(buffer)) != -1) {
                    digest.update(buffer, 0, bytesRead);
                }
            }
            byte[] hashBytes = digest.digest();
            StringBuilder hex = new StringBuilder(hashBytes.length * 2);
            for (byte b : hashBytes) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException | IOException e) {
            log.warn("Failed to compute SHA-256 hash for {}: {}", file.getAbsolutePath(), e.getMessage());
            return null;
        }
    }
}
