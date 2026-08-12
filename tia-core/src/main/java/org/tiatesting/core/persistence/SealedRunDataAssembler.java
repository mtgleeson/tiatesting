package org.tiatesting.core.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tiatesting.core.library.LibraryImpactDrainResult;
import org.tiatesting.core.model.MethodImpactTracker;
import org.tiatesting.core.model.TiaData;
import org.tiatesting.core.model.TrackedLibrary;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Assembles the payload a run seals with: the rebuilt method catalogue and the tracked-library rows
 * whose state the seal changes, bundled into the {@link SealedRunData} the data store writes in one
 * transaction.
 *
 * <p>Shared by the two callers that seal, because they must assemble the payload identically. A
 * single-host run seals with the trackers its own process observed; a distributed build's elected
 * sealer seals with the union of every runner's staged trackers, after the barrier that makes the
 * edge table complete. Everything downstream of "here are the trackers observed for this commit" is
 * the same problem, so it is solved once here rather than twice, where the two copies could drift on
 * the one thing that must not drift - which method ids survive into the catalogue. See the
 * "Persist flow and crash safety" chapter in {@code WIKI.md}.
 */
public final class SealedRunDataAssembler {

    private static final Logger log = LoggerFactory.getLogger(SealedRunDataAssembler.class);

    private final DataStore dataStore;

    /**
     * Bind the assembler to the data store it reads the current catalogue, the edge table's id set
     * and the tracked libraries from.
     *
     * @param dataStore the data store the seal will be written to
     */
    public SealedRunDataAssembler(final DataStore dataStore) {
        this.dataStore = dataStore;
    }

    /**
     * Assemble the seal payload for a run whose mapping rows are already written.
     *
     * <p>Must be called after the suite mapping has been persisted, because the catalogue is
     * rebuilt from the method ids the edge table currently references - an id the edge table does
     * not yet carry is not in the catalogue this produces.
     *
     * @param tiaData the core data read at the start of the persist, already carrying the commit
     *                being sealed; updated in place with the rebuilt catalogue
     * @param observedMethodTrackers the trackers observed for the commit being sealed, keyed by
     *                               method id; they take precedence over the stored catalogue,
     *                               since the stored line numbers may pre-date the changes this
     *                               run captured
     * @param drainResult the library-impact drain performed for this run, or null when none was
     * @param commitValue the commit being sealed, recorded as the mapping baseline of every
     *                    library the seal advances
     * @param allTestsRun true when the run ignored zero suites, which re-covers every library
     * @return the payload to hand to {@link DataStore#persistSealedRunData(SealedRunData)}
     */
    public SealedRunData assemble(final TiaData tiaData,
                                  final Map<Integer, MethodImpactTracker> observedMethodTrackers,
                                  final LibraryImpactDrainResult drainResult,
                                  final String commitValue, final boolean allTestsRun) {
        Map<Integer, MethodImpactTracker> methodsTracked =
                buildMethodsTracked(tiaData, observedMethodTrackers);

        List<LibraryImpactDrainResult.DrainedBatchKey> drainedMethodKeys = Collections.emptyList();
        List<LibraryImpactDrainResult.DrainedBatchKey> drainedForcedKeys = Collections.emptyList();
        if (drainResult != null && drainResult.hasDrainedBatches()) {
            drainedMethodKeys = new ArrayList<>(drainResult.getDrainedBatchKeys());
            drainedForcedKeys = new ArrayList<>(drainResult.getDrainedForcedBatchKeys());
        }

        List<TrackedLibrary> librariesToPersist =
                collectLibrariesToPersist(drainResult, commitValue, allTestsRun);

        return new SealedRunData(tiaData, methodsTracked, drainedMethodKeys, drainedForcedKeys,
                librariesToPersist);
    }

    /**
     * Build the method catalogue to write at the seal. Note this must be called after the suite
     * mapping has been persisted - it queries the data store for the updated set of source class
     * method ids.
     *
     * @param tiaData the Tia DB, updated in place with the resulting catalogue
     * @param observedMethodTrackers all source code methods covered by any test suite executed for
     *                               the commit being sealed
     * @return the catalogue to persist, keyed by method id
     */
    private Map<Integer, MethodImpactTracker> buildMethodsTracked(final TiaData tiaData,
                                                                  final Map<Integer, MethodImpactTracker> observedMethodTrackers) {
        Map<Integer, MethodImpactTracker> methodTrackersOnDisk = dataStore.getMethodsTracked();
        Map<Integer, MethodImpactTracker> updatedMethodTrackers =
                updateMethodTracker(methodTrackersOnDisk, observedMethodTrackers);
        tiaData.setMethodsTracked(updatedMethodTrackers);
        return updatedMethodTrackers;
    }

    /**
     * Update the method tracker which is stored on disk. Any method id referenced from
     * {@code tia_source_class_method} that has no entry in either the observed trackers or the
     * on-disk catalogue is dropped as an orphan rather than carried forward - see the
     * "Persist flow and crash safety" chapter in {@code WIKI.md} for how such orphans arise
     * and why skipping them here is self-correcting.
     *
     * @param methodTrackerOnDisk current method tracker persisted on disk
     * @param observedMethodTrackers methods called by the test runs covering the commit being sealed
     * @return the updated method tracker map, with any orphaned ids dropped
     */
    private Map<Integer, MethodImpactTracker> updateMethodTracker(final Map<Integer, MethodImpactTracker> methodTrackerOnDisk,
                                                                  final Map<Integer, MethodImpactTracker> observedMethodTrackers){

        // Set containing the combined method ids using the updated test mapping after the test run
        Set<Integer> methodsImpactedAfterTestRun = dataStore.getUniqueMethodIdsTracked();

        // We have the updated list of method ids. But the stored method data will have the details associated before the test run
        // which will potentially have incorrect line numbers. This happens for 2 reasons.
        // 1. This happens when a method(s) exist in a source file that had its line numbers changes due to a source file change.
        // 2. We also need to account for other methods that had their line numbers updated but weren't executed in the test,
        // i.e. new lines of code were added to a method, this causes that method to be executed in this test run. But, the methods in
        // the file below this will all be pushed down and have updated line numbers. So we need to update those indexed
        // methods in the DB as well.
        Map<Integer, MethodImpactTracker> newMethodTracker = new HashMap<>();

        for (Integer methodImpactedId : methodsImpactedAfterTestRun){
            MethodImpactTracker tracker = observedMethodTrackers.containsKey(methodImpactedId)
                    ? observedMethodTrackers.get(methodImpactedId)
                    : methodTrackerOnDisk.get(methodImpactedId);

            if (tracker == null) {
                // The id is referenced from tia_source_class_method but neither this run's
                // JaCoCo results nor the tia_source_method table on disk knows about it.
                // Most likely an orphan left behind by an earlier run that aborted between
                // updating the join table and the seal's rewrite of tia_source_method.
                // Skip the orphan rather than NPE downstream in persistSourceMethods.
                log.error("Source method id {} is referenced from tia_source_class_method but " +
                        "has no entry in tia_source_method (and was not invoked in this run); " +
                        "dropping orphan reference.", methodImpactedId);
                continue;
            }

            newMethodTracker.put(methodImpactedId, tracker);
        }

        return newMethodTracker;
    }

    /**
     * Collect the tracked-library rows whose state changes as part of this seal, without writing
     * them - the caller hands them to the data store inside the seal transaction.
     *
     * <p>Two sources contribute. A drained library has its {@code lastAppliedSeq} advanced to the
     * resolved build's sequence and its {@code mappingBaselineCommit} to this run's commit. An
     * all-tests run advances every tracked library's baseline, because every suite was just
     * re-covered. See the mapping-baseline section of the library publish-time stamping chapter
     * in {@code WIKI.md}.
     *
     * <p>Neither source has anything to contribute on a selective run (not all-tests) with no
     * drained batches, so that case returns an empty list before reading {@link
     * DataStore#readTrackedLibraries()} - this keeps the common primary-build persist (a targeted
     * mapping run, no library drain) from paying an unconditional library-table read on every
     * call, per the performance guidance in {@code WIKI.md}.
     *
     * @param drainResult the drain result from test selection, or {@code null} when no drain ran
     * @param commitValue the commit this run seals - the new mapping baseline
     * @param allTestsRun {@code true} when Tia ignored zero suites this run
     * @return the library rows to upsert; empty when nothing changed
     */
    private List<TrackedLibrary> collectLibrariesToPersist(final LibraryImpactDrainResult drainResult,
                                                           final String commitValue,
                                                           final boolean allTestsRun) {
        if ((drainResult == null || !drainResult.hasDrainedBatches()) && !allTestsRun) {
            return Collections.emptyList();
        }

        Map<String, TrackedLibrary> trackedLibraries = dataStore.readTrackedLibraries();
        Map<String, TrackedLibrary> changed = new LinkedHashMap<>();

        if (drainResult != null && drainResult.hasDrainedBatches()) {
            for (Map.Entry<String, Long> entry : drainResult.getAppliedSeqByLibrary().entrySet()) {
                TrackedLibrary library = trackedLibraries.get(entry.getKey());
                if (library == null) {
                    log.warn("Tracked library '{}' not found during drain cleanup - skipping applied-seq update.",
                            entry.getKey());
                    continue;
                }
                library.setLastAppliedSeq(entry.getValue());
                library.setMappingBaselineCommit(commitValue);
                changed.put(entry.getKey(), library);
                log.info("Updating tracked library '{}': last_applied_seq={}, mapping_baseline_commit='{}'.",
                        entry.getKey(), entry.getValue(), commitValue);
            }
        }

        if (allTestsRun) {
            for (TrackedLibrary library : trackedLibraries.values()) {
                if (!Objects.equals(library.getMappingBaselineCommit(), commitValue)) {
                    library.setMappingBaselineCommit(commitValue);
                    changed.put(library.getGroupArtifact(), library);
                    log.info("All-tests run - advancing mapping baseline for library '{}' to '{}'.",
                            library.getGroupArtifact(), commitValue);
                }
            }
        }

        return new ArrayList<>(changed.values());
    }
}
