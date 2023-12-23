package org.tiatesting.persistence;

public enum PersistenceStrategy {
    ALL,
    INCREMENTAL;

    public static PersistenceStrategy getDefaultPersistenceStrategy(){
        return ALL;
    }
}
