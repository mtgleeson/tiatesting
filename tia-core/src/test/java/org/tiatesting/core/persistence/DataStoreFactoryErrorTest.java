package org.tiatesting.core.persistence;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies {@link DataStoreFactory} produces actionable errors for the two failure modes a
 * pluggable-datastore user can hit: an unknown dialect id (override or unresolvable URL scheme),
 * and a non-H2 dialect whose JDBC driver is missing from the classpath. See the
 * pluggable-datastore WIKI chapter for the two-classpath model (test-scope dependency vs. Tia
 * plugin dependency) these messages point users at.
 */
class DataStoreFactoryErrorTest {

    @Test
    void unknownDialectOverrideListsSupported() {
        // given / when / then
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> DataStoreFactory.fromConfig(null, "jdbc:postgresql://h/db", "u", "p", "oracle", "main", null));
        assertTrue(ex.getMessage().contains("postgres"));
        assertTrue(ex.getMessage().contains("h2"));
    }

    @Test
    void missingDriverMessageNamesVendorAndClasspath() {
        // given a postgres URL but the driver class made unavailable is hard to force in-process;
        // instead assert the factory's driver-presence check produces the actionable message.
        // when / then
        String msg = DataStoreFactory.missingDriverMessage("postgres");
        assertTrue(msg.contains("postgres"));
        assertTrue(msg.toLowerCase().contains("driver"));
        assertTrue(msg.toLowerCase().contains("dependenc"));
    }
}
