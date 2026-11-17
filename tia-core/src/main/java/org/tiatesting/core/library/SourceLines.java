package org.tiatesting.core.library;

/**
 * The matching rule for library source lines. A source line identifies where the library source
 * behind a published build lives; a null line is one recorded before lines existed, or one the
 * publish could not resolve. See the source-lines section of the library publish-time stamping
 * chapter in {@code WIKI.md}.
 */
public final class SourceLines {

    private SourceLines() {
    }

    /**
     * Report whether two source lines are the same line. An unknown (null) line matches any line,
     * so rows written before lines were recorded keep behaving as they always did.
     *
     * @param a one line, or null when unknown
     * @param b the other line, or null when unknown
     * @return true when either is null or both are equal
     */
    public static boolean matches(final String a, final String b) {
        return a == null || b == null || a.equals(b);
    }
}
