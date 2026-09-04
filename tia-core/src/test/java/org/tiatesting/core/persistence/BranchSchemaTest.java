package org.tiatesting.core.persistence;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BranchSchemaTest {

    /** The Postgres identifier limit every derived name must respect. */
    private static final int MAX_IDENTIFIER_LENGTH = 63;

    @Test
    void sanitizesAndPrefixes() {
        // given / when / then
        assertEquals("tia_main", BranchSchema.schemaName("main", null));
        assertEquals("tia_feature_foo_bar", BranchSchema.schemaName("feature/Foo-Bar", null));
        assertEquals("tia_123", BranchSchema.schemaName("123", null));           // prefix makes it valid
        assertEquals("tia_", BranchSchema.schemaName("", null));                  // empty branch
    }

    /**
     * With no suffix the output must be byte-identical to what Tia produced before the suffix
     * existed, or an existing project's schema silently moves on upgrade and its whole mapping is
     * orphaned - a re-seed that looks like data loss. These expectations were captured from the
     * previous single-argument implementation and asserted green against it before it changed, so
     * they pin what it actually produced rather than what it was assumed to produce.
     */
    @Test
    void noSuffixIsByteIdenticalToTheHistoricalOutput() {
        // given / when / then
        assertEquals("tia_main", BranchSchema.schemaName("main", null));
        assertEquals("tia_release_2_x", BranchSchema.schemaName("release/2.x", null));
        assertEquals("tia_feature_proj_1234_refactor_the_persistence_layer",
                BranchSchema.schemaName("feature/PROJ-1234-refactor-the-persistence-layer", null));
        assertEquals("tia_" + repeat("a", 59), BranchSchema.schemaName(repeat("a", 59), null));
        assertEquals("tia_" + repeat("a", 59), BranchSchema.schemaName(repeat("a", 100), null));
        assertEquals("tia_", BranchSchema.schemaName("", null));
        assertEquals("tia_", BranchSchema.schemaName(null, null));
    }

    /**
     * A blank suffix means "none" as surely as null does. Config that resolves to an empty string -
     * an unset Maven property expanding to nothing, say - must not produce a trailing separator and
     * a schema distinct from the unsuffixed one.
     */
    @Test
    void aBlankSuffixIsTreatedAsNoSuffix() {
        // given / when / then
        assertEquals("tia_main", BranchSchema.schemaName("main", ""));
        assertEquals("tia_main", BranchSchema.schemaName("main", "   "));
    }

    @Test
    void aSuffixIsAppendedAndSanitised() {
        // given / when / then
        assertEquals("tia_main_unit", BranchSchema.schemaName("main", "unit"));
        assertEquals("tia_main_integration_tests",
                BranchSchema.schemaName("main", "Integration Tests"));
        assertEquals("tia_feature_foo_unit", BranchSchema.schemaName("feature/Foo", "unit"));
    }

    /**
     * The regression the suffix exists to prevent, in the one place it could silently reappear.
     * Clamping the whole concatenation from the right would drop the suffix on a branch already at
     * the limit, so two test tasks would resolve to one schema and delete each other's suites - the
     * exact failure the suffix is meant to stop, reintroduced only for projects with long branch
     * names.
     */
    @Test
    void aLongBranchNeverClampsTheSuffixAway() {
        // given - a branch that alone fills the identifier budget
        String longBranch = repeat("a", 100);

        // when
        String unit = BranchSchema.schemaName(longBranch, "unit");
        String integration = BranchSchema.schemaName(longBranch, "integration");

        // then
        assertTrue(unit.endsWith("_unit"), unit);
        assertTrue(integration.endsWith("_integration"), integration);
        assertNotEquals(unit, integration,
                "two suffixes on one long branch must not clamp to the same schema");
        assertTrue(unit.length() <= MAX_IDENTIFIER_LENGTH, unit);
        assertTrue(integration.length() <= MAX_IDENTIFIER_LENGTH, integration);
    }

    /**
     * A suffix long enough to leave no room for any branch still has to produce a valid, unique
     * name. Readability is unattainable here; distinctness is not optional.
     */
    @Test
    void anOverlongSuffixFallsBackToAUniqueName() {
        // given
        String hugeSuffix = repeat("s", 70);

        // when
        String fromMain = BranchSchema.schemaName("main", hugeSuffix);
        String fromRelease = BranchSchema.schemaName("release", hugeSuffix);

        // then
        assertTrue(fromMain.length() <= MAX_IDENTIFIER_LENGTH, fromMain);
        assertTrue(fromRelease.length() <= MAX_IDENTIFIER_LENGTH, fromRelease);
        assertNotEquals(fromMain, fromRelease,
                "two branches sharing an overlong suffix must still resolve apart");
        assertTrue(fromMain.startsWith("tia_"), fromMain);
    }

    /**
     * Every derived name has to be a legal identifier whatever went in - the prefix, the character
     * class and the length limit all hold for suffixed names too.
     */
    @Test
    void everyDerivedNameIsALegalIdentifier() {
        // given
        String[][] pairs = {
                {"main", "unit"},
                {"feature/PROJ-1234", "integration tests"},
                {repeat("b", 200), repeat("c", 40)},
                {"", "unit"},
                {null, "unit"},
        };

        for (String[] pair : pairs) {
            // when
            String schema = BranchSchema.schemaName(pair[0], pair[1]);

            // then
            assertTrue(schema.startsWith("tia_"), schema);
            assertTrue(schema.length() <= MAX_IDENTIFIER_LENGTH, schema);
            assertTrue(schema.matches("[a-z0-9_]+"), schema);
        }
    }

    @Test
    void clampsToIdentifierLimit() {
        // given
        String longBranch = new String(new char[100]).replace('\0', 'a');

        // when
        String schema = BranchSchema.schemaName(longBranch, null);

        // then
        assertEquals(63, schema.length());
        assertEquals("tia_", schema.substring(0, 4));
    }

    /**
     * Repeat a string, since the project targets Java 8 and {@code String.repeat} is Java 11.
     *
     * @param unit the string to repeat
     * @param times how many times to repeat it
     * @return the repeated string
     */
    private static String repeat(final String unit, final int times) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < times; i++) {
            sb.append(unit);
        }
        return sb.toString();
    }
}
