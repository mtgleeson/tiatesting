package org.tiatesting.core.persistence.dialect;

import org.junit.jupiter.api.Test;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class H2DialectTest {

    @Test
    void identityColumnMatchesH2() {
        // given
        H2Dialect dialect = new H2Dialect();
        // when
        String ddl = dialect.identityColumnDefinition();
        // then
        assertEquals("BIGINT AUTO_INCREMENT PRIMARY KEY", ddl);
    }

    @Test
    void upsertUsesMergeIntoWithKey() {
        // given
        H2Dialect dialect = new H2Dialect();
        // when
        String sql = dialect.upsert("tia_test_suite",
                Arrays.asList("name", "num_runs"), Arrays.asList("name"));
        // then
        assertEquals("MERGE INTO tia_test_suite (name, num_runs) KEY(name) VALUES (?, ?)", sql);
    }

    @Test
    void idIsH2() {
        // given / when / then
        assertEquals("h2", new H2Dialect().id());
    }
}
