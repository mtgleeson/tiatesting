package org.tiatesting.core.sourcefile;

import java.util.List;

/**
 * Utility for converting file paths from VCS diffs into the relative, forward-slash keys
 * used to store source files in the Tia mapping (e.g. {@code com/example/Foo.java}).
 *
 * <p>The stored keys are produced by the coverage agent from class metadata, so a diff's
 * absolute file path must be normalized with exactly the same rules wherever the mapping
 * is looked up - both for the in-memory lookup in {@code MethodImpactAnalyzer} and for
 * deriving the SQL query keys on the targeted select-tests read path. Keeping the rules
 * in one place guarantees the two paths cannot drift apart.
 */
public final class SourceFilenameUtil {

    private SourceFilenameUtil() {
    }

    /**
     * Normalize a diff file path to the relative key format used in the stored mapping.
     *
     * <p>The normalization applies, in order:
     * <ol>
     *     <li>Strip every occurrence of each entry in {@code sourceFilesDirs} from the path
     *     (literal {@link String#replace(CharSequence, CharSequence)}, all occurrences -
     *     not a prefix strip).</li>
     *     <li>Convert Windows backslashes to forward slashes to match the stored format.</li>
     *     <li>Remove the leading path separator left behind by the directory strip.</li>
     * </ol>
     *
     * <p>The leading-separator removal unconditionally drops the first character, which
     * assumes one of the {@code sourceFilesDirs} matched and left a separator-prefixed
     * remainder. This mirrors the long-standing lookup behaviour; callers pass diff paths
     * that live under one of the source dirs.
     *
     * @param filePath the file path from the VCS diff (typically absolute, OS-specific separators)
     * @param sourceFilesDirs the configured source root directories to strip from the path
     * @return the relative, forward-slash mapping key for the file
     */
    public static String normalizeToMappingKey(final String filePath, final List<String> sourceFilesDirs) {
        String fileName = filePath;

        for (String sourceFilesDir : sourceFilesDirs) {
            fileName = fileName.replace(sourceFilesDir, "");
        }

        fileName = fileName.replaceAll("\\\\", "/"); // if Windows, switch to forward slash used in the test mapping
        fileName = fileName.substring(1); // remove beginning /
        return fileName;
    }
}
