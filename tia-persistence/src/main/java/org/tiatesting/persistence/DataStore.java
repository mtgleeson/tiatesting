package org.tiatesting.persistence;

import org.tiatesting.core.model.TiaData;

import java.util.Set;

public interface DataStore {

    /**
     * Retrieve the full persisted Tia data.
     *
     * @return
     */
    TiaData getTiaData(boolean readFromDisk);

    /**
     * Retrieve the persisted Tia core data.
     *
     * @return
     */
    TiaData getTiaCore();

    /**
     * Get the number of test suites tracked by Tia in the DB.
     * @return
     */
    int getNumTestSuites();

    /**
     * Get the number of source methods tracked;
     * @return
     */
    int getNumSourceMethods();

    /**
     * Get the list of test suites that failed the previous run and are tracked in the Tia to be executed in the next run.
     * @return
     */
    Set<String> getTestSuitesFailed();

    /**
     * Persist the given Tia data to disk.
     *
     * @param tiaData
     * @return
     */
    boolean persistTiaData(final TiaData tiaData);
}
