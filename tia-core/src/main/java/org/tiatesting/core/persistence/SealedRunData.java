package org.tiatesting.core.persistence;

import org.tiatesting.core.library.LibraryImpactDrainResult;
import org.tiatesting.core.model.MethodImpactTracker;
import org.tiatesting.core.model.TiaData;
import org.tiatesting.core.model.TrackedLibrary;

import java.util.List;
import java.util.Map;

/**
 * The complete payload of a run's seal: everything that must become visible at the same instant
 * as the new commit value.
 *
 * <p>The method catalogue and the library mapping baselines are both statements about the commit
 * being sealed - the catalogue's line ranges are in that commit's coordinate space, and a
 * library's {@code mappingBaselineCommit} claims its methods were re-captured there. If either
 * landed without the commit value, a later diff would read them against the wrong baseline and
 * could under-select. Bundling them lets {@link DataStore#persistSealedRunData} write all of it
 * in one transaction.
 *
 * <p>The bulk suite mapping rows are deliberately NOT part of this bundle - they are written
 * earlier and are safe to be ahead of the commit, because they carry no line coordinates and are
 * marked unsealed until the seal clears them. See the "Persist flow and crash safety" chapter in
 * {@code WIKI.md}.
 */
public class SealedRunData {

    private final TiaData tiaData;
    private final Map<Integer, MethodImpactTracker> methodsTracked;
    private final List<LibraryImpactDrainResult.DrainedBatchKey> drainedMethodBatchKeys;
    private final List<LibraryImpactDrainResult.DrainedBatchKey> drainedForcedBatchKeys;
    private final List<TrackedLibrary> librariesToPersist;

    /**
     * Construct a seal payload.
     *
     * @param tiaData the core data to write, carrying the commit value being sealed
     * @param methodsTracked the full method catalogue to write, keyed by method id
     * @param drainedMethodBatchKeys pending impacted-method batches to delete; may be empty
     * @param drainedForcedBatchKeys pending forced-selection batches to delete; may be empty
     * @param librariesToPersist tracked libraries whose baseline or applied sequence changed;
     *                           may be empty
     */
    public SealedRunData(TiaData tiaData, Map<Integer, MethodImpactTracker> methodsTracked,
                         List<LibraryImpactDrainResult.DrainedBatchKey> drainedMethodBatchKeys,
                         List<LibraryImpactDrainResult.DrainedBatchKey> drainedForcedBatchKeys,
                         List<TrackedLibrary> librariesToPersist) {
        this.tiaData = tiaData;
        this.methodsTracked = methodsTracked;
        this.drainedMethodBatchKeys = drainedMethodBatchKeys;
        this.drainedForcedBatchKeys = drainedForcedBatchKeys;
        this.librariesToPersist = librariesToPersist;
    }

    /** @return the core data to write, carrying the commit value being sealed */
    public TiaData getTiaData() { return tiaData; }

    /** @return the full method catalogue to write, keyed by method id */
    public Map<Integer, MethodImpactTracker> getMethodsTracked() { return methodsTracked; }

    /** @return the pending impacted-method batches to delete */
    public List<LibraryImpactDrainResult.DrainedBatchKey> getDrainedMethodBatchKeys() { return drainedMethodBatchKeys; }

    /** @return the pending forced-selection batches to delete */
    public List<LibraryImpactDrainResult.DrainedBatchKey> getDrainedForcedBatchKeys() { return drainedForcedBatchKeys; }

    /** @return the tracked libraries to upsert */
    public List<TrackedLibrary> getLibrariesToPersist() { return librariesToPersist; }
}
