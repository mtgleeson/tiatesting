package org.tiatesting.core.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests proving {@link MethodImpactTracker#getNameForDisplay()} (now a single-pass
 * {@code replace('/', '.')}) is byte-identical to the previous {@code replaceAll("/", ".")}, and
 * that {@link MethodImpactTracker#getShortNameForDisplay()} still strips the signature.
 */
class MethodImpactTrackerDisplayNameTest {

    /**
     * Verify the display name replaces every {@code /} with {@code .}, matching the previous
     * regex-based behaviour for representative internal method names.
     */
    @Test
    void getNameForDisplayMatchesPreviousReplaceAll() {
        // given
        String[] names = {
                "com/example/HandleService.getHandleModel.(Ljava/lang/Long;)Ljava/lang/String",
                "no/slashes/here",
                "NoSlashesAtAll",
                "a/b/c/d",
                "/leading",
                "trailing/",
                ""
        };

        // when / then
        for (String name : names) {
            MethodImpactTracker tracker = new MethodImpactTracker(name, 1, 2);
            assertEquals(name.replaceAll("/", "."), tracker.getNameForDisplay(),
                    "display name differs for: " + name);
        }
    }

    /**
     * Verify the short display name strips everything from the first parenthesis onwards and the
     * separating dot, over the dot-separated display name.
     */
    @Test
    void getShortNameForDisplayStripsSignature() {
        // given
        MethodImpactTracker tracker = new MethodImpactTracker(
                "com/example/HandleService.getHandleModel.(Ljava/lang/Long;)Ljava/lang/String", 1, 2);

        // when
        String shortName = tracker.getShortNameForDisplay();

        // then
        assertEquals("com.example.HandleService.getHandleModel", shortName);
    }
}
