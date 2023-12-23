package org.tiatesting.persistence;

import org.tiatesting.core.coverage.ClassImpactTracker;

import java.util.List;
import java.util.Map;
import java.util.Set;

public interface DataStore {

    /**
     * Retrieve the stored test mapping details.
     *
     * @return
     */
    StoredMapping getStoredMapping();

    /**
     * Persist the mapping of the methods called by each test class.
     *
     * @param testMethodsCalled
     * @param testSuitesFailed
     * @param commitValue
     * @return
     */
    boolean persistTestMapping(Map<String, List<ClassImpactTracker>> testMethodsCalled,
                               Set<String> testSuitesFailed, String commitValue);

    /**
     * Get the persistence strategy for the DB.
     * @return
     */
    PersistenceStrategy getDBPersistenceStrategy();
}
