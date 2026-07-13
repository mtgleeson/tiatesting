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
        PendingLibraryImpactedMethod pending = buildPendingBatch(trackedLibrary, impactedMethodIds, libraryConfig);
        if (pending == null) {
            return;
        }

        // Release stamps may advance the library's high water mark; SNAPSHOT stamps never do.
        if (!isSnapshotVersion(pending.getStampVersion())) {
            advanceHwmIfNeeded(pending.getStampVersion(), trackedLibrary,
                    libraryConfig.getVersionPolicy(), dataStore);
        }

        dataStore.persistPendingLibraryImpactedMethods(pending);
        log.info("Stamped {} pending impacted methods for library '{}' at version '{}' (unknownNextVersion={}).",
                pending.getSourceMethodIds().size(), pending.getGroupArtifact(),
                pending.getStampVersion(), pending.isUnknownNextVersion());
    }

    /**
     * Build the pending impacted-methods batch for a library change without persisting it or
     * mutating any tracked-library state. Reads the library's declared HEAD version, computes the
     * JAR content hash for SNAPSHOT versions, and classifies the {@code unknownNextVersion} flag
     * for release versions against the library's current (un-advanced) high water mark.
     *
     * <p>This is the pure, side-effect-free core shared by the persisting record path
     * ({@link #recordPendingImpactedMethods}) and the in-memory preview path used by
     * {@code select-tests}, which must not write to the data store. It deliberately does not advance
     * the high water mark; callers that persist are responsible for calling {@link #advanceHwmIfNeeded}.
     *
     * @param trackedLibrary the tracked library whose source changed.
     * @param impactedMethodIds the source method IDs impacted by the library change.
     * @param libraryConfig the library impact analysis configuration.
     * @return the constructed pending batch, or {@code null} when there are no impacted methods or
     *         the library's declared version cannot be determined.
     */
    public PendingLibraryImpactedMethod buildPendingBatch(TrackedLibrary trackedLibrary,
                                                          Set<Integer> impactedMethodIds,
                                                          LibraryImpactAnalysisConfig libraryConfig) {
        if (impactedMethodIds == null || impactedMethodIds.isEmpty()) {
            log.debug("No impacted methods for library '{}', skipping pending stamp.",
                    trackedLibrary.getGroupArtifact());
            return null;
        }

        String stampVersion = readDeclaredVersionForLibrary(trackedLibrary, libraryConfig);
        if (stampVersion == null) {
            log.warn("Could not determine declared version for library '{}' —  skipping pending stamp.",
                    trackedLibrary.getGroupArtifact());
            return null;
        }

        String stampJarHash = null;
        boolean unknownNextVersion = false;
        if (isSnapshotVersion(stampVersion)) {
            stampJarHash = computeSnapshotStampJarHash(trackedLibrary, libraryConfig);
        } else {
            unknownNextVersion = classifyReleaseStamp(stampVersion, trackedLibrary,
                    libraryConfig.getVersionPolicy());
        }

        PendingLibraryImpactedMethod pending = new PendingLibraryImpactedMethod(
                trackedLibrary.getGroupArtifact(), stampVersion, stampJarHash, impactedMethodIds);
        pending.setUnknownNextVersion(unknownNextVersion);
        return pending;
    }

    /**
     * Classify a release-version stamp against the library's current high water mark and version
     * policy to determine the {@code unknownNextVersion} flag, without advancing or persisting the
     * high water mark. Under {@link LibraryVersionPolicy#BUMP_AFTER_RELEASE} every release stamp is
     * known ({@code false}). Under {@link LibraryVersionPolicy#BUMP_AT_RELEASE} a stamp above the
     * high water mark is known, a stamp at the mark is destined for the next unknown release
     * ({@code true}), and a stamp below the mark is held conservatively ({@code true}).
     *
     * <p>The high water mark is only consumed under {@code BUMP_AT_RELEASE}. Under
     * {@code BUMP_AFTER_RELEASE} the field is left untouched for the lifetime of the library to
     * avoid surfacing data that would grow stale as releases happen between stamp events.
     *
     * @param stampVersion the library's declared release version being stamped.
     * @param trackedLibrary the tracked library, source of the current high water mark.
     * @param policy the library's version-increment policy.
     * @return {@code true} when the batch must be held as an unknown-next-version stamp.
     */
    private boolean classifyReleaseStamp(String stampVersion, TrackedLibrary trackedLibrary,
                                         LibraryVersionPolicy policy) {
        if (policy != LibraryVersionPolicy.BUMP_AT_RELEASE) {
            return false;
        }

        String priorHwm = trackedLibrary.getLastReleasedLibraryVersion();
        int cmp = (priorHwm == null) ? 1
                : PendingLibraryImpactedMethodsDrainer.compareVersions(stampVersion, priorHwm);

        if (cmp > 0) {
            return false;
        }
        if (cmp < 0) {
            log.warn("Library '{}' version '{}' regressed below observed high water mark '{}' — holding stamp conservatively.",
                    trackedLibrary.getGroupArtifact(), stampVersion, priorHwm);
        }
        return true;
    }

    /**
     * Advance and persist the library's high water mark when a release stamp under
     * {@link LibraryVersionPolicy#BUMP_AT_RELEASE} exceeds the currently recorded mark. This is the
     * persisting counterpart to {@link #classifyReleaseStamp}; it is a no-op under
     * {@code BUMP_AFTER_RELEASE} (which does not maintain the mark) and when the stamp does not
     * exceed the current mark.
     *
     * @param stampVersion the library's declared release version being stamped.
     * @param trackedLibrary the tracked library whose high water mark may be advanced.
     * @param policy the library's version-increment policy.
     * @param dataStore the persistence layer used to store the advanced high water mark.
     */
    private void advanceHwmIfNeeded(String stampVersion, TrackedLibrary trackedLibrary,
                                    LibraryVersionPolicy policy, DataStore dataStore) {
        if (policy != LibraryVersionPolicy.BUMP_AT_RELEASE) {
            return;
        }

        String priorHwm = trackedLibrary.getLastReleasedLibraryVersion();
        int cmp = (priorHwm == null) ? 1
                : PendingLibraryImpactedMethodsDrainer.compareVersions(stampVersion, priorHwm);
        if (cmp > 0) {
            trackedLibrary.setLastReleasedLibraryVersion(stampVersion);
            dataStore.persistTrackedLibrary(trackedLibrary);
            log.debug("Advanced lastReleasedLibraryVersion for '{}' to '{}'.",
                    trackedLibrary.getGroupArtifact(), stampVersion);
        }
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
