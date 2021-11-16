package org.tiatesting.plugin.persistence;

public enum PersistenceStrategy {
    ALL,
    INCREMENTAL;

    public static PersistenceStrategy getDefaultPersistenceStrategy(){
        return ALL;
    }
}
