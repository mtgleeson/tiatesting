package org.tiatesting.core.report.html;

/**
 * Helpers for building JSON that is embedded verbatim inside an inline {@code <script>} element in
 * a report page. Beyond producing valid JSON these unicode-escape the three HTML-significant
 * characters ({@code <}, {@code >}, {@code &}) so the emitted text can never contain a literal
 * {@code </script>} or {@code <!--} sequence that would terminate the script element - the browser
 * decodes the {@code \\uXXXX} escapes back to the original characters when it parses the JSON, so
 * the value still reaches the page script intact.
 *
 * <p>Shared by the report builders that embed data for client-side rendering (the Source Methods
 * index and the History timeline chart) so the escaping rule lives in one place.
 */
final class ScriptSafeJson {

    private ScriptSafeJson() {}

    /**
     * Append {@code value} to {@code sb} as a double-quoted JSON string that is also safe to embed
     * inside an inline {@code <script>}. Applies the standard JSON escapes (backslash, quote,
     * control characters) and additionally unicode-escapes {@code <}, {@code >} and {@code &}.
     *
     * @param sb the builder to append the quoted, escaped string to
     * @param value the raw string value to encode
     */
    static void appendString(StringBuilder sb, String value) {
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '<':  sb.append("\\u003c"); break;
                case '>':  sb.append("\\u003e"); break;
                case '&':  sb.append("\\u0026"); break;
                default:
                    if (c < 0x20) {
                        sb.append("\\u").append(String.format("%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }
}
