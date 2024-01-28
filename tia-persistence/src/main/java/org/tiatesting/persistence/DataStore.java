package org.tiatesting.persistence;

import org.tiatesting.core.model.TiaData;

public interface DataStore {

    /**
     * Retrieve the persisted Tia data.
     *
     * @return
     */
    TiaData getTiaData(boolean readFromDisk);

    /**
     * Persist the given Tia data to disk.
     *
     * @param tiaData
     * @return
     */
    boolean persistTiaData(final TiaData tiaData);
}
