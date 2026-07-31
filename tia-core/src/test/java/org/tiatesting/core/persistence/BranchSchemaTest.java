package org.tiatesting.core.persistence;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class BranchSchemaTest {

    @Test
    void sanitizesAndPrefixes() {
        // given / when / then
        assertEquals("tia_main", BranchSchema.schemaName("main"));
        assertEquals("tia_feature_foo_bar", BranchSchema.schemaName("feature/Foo-Bar"));
        assertEquals("tia_123", BranchSchema.schemaName("123"));           // prefix makes it valid
        assertEquals("tia_", BranchSchema.schemaName(""));                  // empty branch
    }

    @Test
    void clampsToIdentifierLimit() {
        // given
        String longBranch = new String(new char[100]).replace('\0', 'a');
        // when
        String schema = BranchSchema.schemaName(longBranch);
        // then
        assertEquals(63, schema.length());
        assertEquals("tia_", schema.substring(0, 4));
    }
}
