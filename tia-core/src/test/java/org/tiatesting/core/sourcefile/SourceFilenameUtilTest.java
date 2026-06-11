package org.tiatesting.core.sourcefile;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for {@link SourceFilenameUtil#normalizeToMappingKey(String, java.util.List)} - the
 * shared normalization from VCS diff file paths to the relative, forward-slash keys stored
 * in the Tia mapping. The behaviour must stay identical to the long-standing lookup logic
 * previously inlined in MethodImpactAnalyzer, because the same rules now also derive the
 * targeted-query keys on the select-tests read path.
 */
class SourceFilenameUtilTest {

    @Test
    void normalizesUnixAbsolutePathToRelativeKey() {
        // given
        String filePath = "/home/user/project/src/main/java/com/example/Foo.java";

        // when
        String key = SourceFilenameUtil.normalizeToMappingKey(filePath,
                Collections.singletonList("/home/user/project/src/main/java"));

        // then
        assertEquals("com/example/Foo.java", key);
    }

    @Test
    void normalizesWindowsBackslashPathToForwardSlashKey() {
        // given
        String filePath = "C:\\dev\\project\\src\\main\\java\\com\\example\\Foo.java";

        // when
        String key = SourceFilenameUtil.normalizeToMappingKey(filePath,
                Collections.singletonList("C:\\dev\\project\\src\\main\\java"));

        // then
        assertEquals("com/example/Foo.java", key);
    }

    @Test
    void stripsUsingMatchingDirWhenMultipleSourceDirsConfigured() {
        // given
        String filePath = "/home/user/project/module-b/src/main/java/com/example/Bar.java";

        // when
        String key = SourceFilenameUtil.normalizeToMappingKey(filePath,
                Arrays.asList("/home/user/project/module-a/src/main/java",
                        "/home/user/project/module-b/src/main/java"));

        // then
        assertEquals("com/example/Bar.java", key);
    }

    @Test
    void stripsAllOccurrencesOfSourceDirNotJustPrefix() {
        // given - the legacy behaviour uses String.replace (all occurrences, not a prefix
        // strip); a path that repeats the source dir string loses every occurrence
        String filePath = "/src/main/java/src/main/java/com/example/Foo.java";

        // when
        String key = SourceFilenameUtil.normalizeToMappingKey(filePath,
                Collections.singletonList("/src/main/java"));

        // then
        assertEquals("com/example/Foo.java", key);
    }
}
