package org.tiatesting.plugin.persistence;

import java.util.Map;
import java.util.Set;

public interface DataStore {

    /**
     * Retrieve the stored test mapping.
     *
     * @return
     */
    StoredMapping getTestMapping();

    /**
     * Persist the mapping of the methods called by each test class.
     * @param testMethodsCalled
     * @return
     */
    boolean persistTestMapping(Map<String, Set<String>> testMethodsCalled);

    /**
     * Get the persistence strategy for the DB.
     * @return
     */
    PersistenceStrategy getDBPersistenceStrategy();
}
