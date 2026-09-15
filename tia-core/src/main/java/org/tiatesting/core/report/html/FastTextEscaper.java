package org.tiatesting.core.report.html;

import j2html.Config;
import j2html.utils.EscapeUtil;
import j2html.utils.TextEscaper;

/**
 * A fast, allocation-light {@link TextEscaper} that is byte-identical to j2html's default
 * {@link EscapeUtil#escape(String)} but avoids work on the common case where the input contains
 * no HTML metacharacters.
 *
 * <p>The HTML reports render tens of thousands of per-suite and per-method pages whose cells are
 * long deep-package method and class names. Those names almost never contain any of the five
 * characters that need escaping ({@code & < > " '}), yet j2html's default escaper allocates a
 * fresh {@code StringBuilder} and appends every character one at a time on every call. This
 * escaper first scans for a character that needs escaping and, finding none, returns the input
 * string unchanged with no allocation; only when an escapable character is present does it build
 * the escaped result. The produced text is identical to {@code EscapeUtil.escape} in every case.
 * The {@code profileHtmlReport} harness measured the render hotspot that motivated this.
 *
 * <p>j2html escapes text-node content via the per-render {@link Config#textEscaper()} but escapes
 * attribute values via the process-wide static {@code Config.textEscaper} field. To route both
 * through this escaper, {@link #reportConfig()} installs it on the per-render config, and the
 * static initialiser installs the same shared instance on the static field. The swap is
 * byte-identical to j2html's default, so it changes no rendered output.
 */
final class FastTextEscaper implements TextEscaper {

    /** Shared, stateless, thread-safe instance reused across report renders. */
    static final FastTextEscaper INSTANCE = new FastTextEscaper();

    static {
        // Route j2html's attribute-value escaping (which reads the static field, not the
        // per-render config) through this escaper as well. Byte-identical to the default.
        Config.textEscaper = INSTANCE;
    }

    private FastTextEscaper() {
    }

    /**
     * Build the j2html render {@link Config} the HTML reports use: empty tags closed (to match the
     * previous behaviour) with this escaper installed for text-node content. Referencing this
     * class also triggers its static initialiser, which installs the same escaper for attribute
     * values.
     *
     * @return the shared report render configuration
     */
    static Config reportConfig() {
        return Config.defaults().withEmptyTagsClosed(true).withTextEscaper(INSTANCE);
    }

    /**
     * Escape the given text for safe HTML output. Returns the input unchanged (no allocation) when
     * it contains no escapable character; otherwise builds the escaped string. Produces exactly
     * the same result as {@link EscapeUtil#escape(String)} for every input, including null.
     *
     * @param text the raw text to escape (may be null)
     * @return the escaped text, or null when the input is null
     */
    @Override
    public String escape(String text) {
        if (text == null) {
            return null;
        }
        int firstEscapable = indexOfEscapable(text);
        if (firstEscapable < 0) {
            return text;
        }
        StringBuilder sb = new StringBuilder(text.length() + 16);
        sb.append(text, 0, firstEscapable);
        for (int i = firstEscapable; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&': sb.append("&amp;"); break;
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                case '"': sb.append("&quot;"); break;
                case '\'': sb.append("&#x27;"); break;
                default: sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * Find the index of the first character that requires HTML escaping.
     *
     * @param text the text to scan (must not be null)
     * @return the index of the first escapable character, or -1 if the text contains none
     */
    private static int indexOfEscapable(String text) {
        for (int i = 0; i < text.length(); i++) {
            switch (text.charAt(i)) {
                case '&':
                case '<':
                case '>':
                case '"':
                case '\'':
                    return i;
                default:
                    // not escapable, keep scanning
            }
        }
        return -1;
    }
}
