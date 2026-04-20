package org.tiatesting.core.library;

import java.io.Serializable;
import java.util.*;

/**
 * Captures the outcome of draining pending library impacted methods during test selection.
 * Carried from the selector to the post-test-run persistence step so that drained rows can
 * be deleted and {@code tia_library.last_source_project_version / last_source_project_jar_hash}
 * can be updated after the tests finish.
 */
public class LibraryImpactDrainResult implements Serializable {
    private static final long serialVersionUID = 1L;

    /**
     * The set of {@code (groupArtifact, stampVersion)} pairs that were drained in this selection run.
     * Each pair identifies a batch of pending rows that should be deleted after the test run completes.
     */
    private final List<DrainedBatchKey> drainedBatchKeys;

    /**
     * For each library that was drained, the resolved version and JAR hash observed at drain time.
     * Used to update {@code tia_library.last_source_project_version} and
     * {@code tia_library.last_source_project_jar_hash} post-test-run.
     */
    private final Map<String, ObservedLibraryState> observedLibraryStates;

    public LibraryImpactDrainResult() {
        this.drainedBatchKeys = new ArrayList<>();
        this.observedLibraryStates = new LinkedHashMap<>();
    }

    public void addDrainedBatch(String groupArtifact, String stampVersion) {
        drainedBatchKeys.add(new DrainedBatchKey(groupArtifact, stampVersion));
    }

    public void setObservedLibraryState(String groupArtifact, String resolvedVersion, String resolvedJarHash) {
        observedLibraryStates.put(groupArtifact, new ObservedLibraryState(resolvedVersion, resolvedJarHash));
    }

    public List<DrainedBatchKey> getDrainedBatchKeys() {
        return drainedBatchKeys;
    }

    public Map<String, ObservedLibraryState> getObservedLibraryStates() {
        return observedLibraryStates;
    }

    public boolean hasDrainedBatches() {
        return !drainedBatchKeys.isEmpty();
    }

    /**
     * Identifies a single pending batch that was drained: {@code (groupArtifact, stampVersion)}.
     */
    public static class DrainedBatchKey implements Serializable {
        private static final long serialVersionUID = 1L;

        private final String groupArtifact;
        private final String stampVersion;

        public DrainedBatchKey(String groupArtifact, String stampVersion) {
            this.groupArtifact = groupArtifact;
            this.stampVersion = stampVersion;
        }

        public String getGroupArtifact() {
            return groupArtifact;
        }

        public String getStampVersion() {
            return stampVersion;
        }

        @Override
        public String toString() {
            return groupArtifact + "@" + stampVersion;
        }
    }

    /**
     * The resolved state of a library at drain time: the version string and JAR content hash
     * as observed on the source project's classpath.
     */
    public static class ObservedLibraryState implements Serializable {
        private static final long serialVersionUID = 1L;

        private final String resolvedVersion;
        private final String resolvedJarHash;

        public ObservedLibraryState(String resolvedVersion, String resolvedJarHash) {
            this.resolvedVersion = resolvedVersion;
            this.resolvedJarHash = resolvedJarHash;
        }

        public String getResolvedVersion() {
            return resolvedVersion;
        }

        public String getResolvedJarHash() {
            return resolvedJarHash;
        }
    }
}
