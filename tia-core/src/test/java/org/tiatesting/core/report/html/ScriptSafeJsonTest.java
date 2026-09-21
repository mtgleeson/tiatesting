package org.tiatesting.core.report.html;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies {@link ScriptSafeJson#appendString(StringBuilder, String)} produces a double-quoted
 * JSON string with the standard escapes and, additionally, unicode-escapes {@code < > &} so the
 * value is always safe to inline inside a {@code <script>} element (it can never form a literal
 * {@code </script>} or {@code <!--} sequence that would terminate the element).
 */
class ScriptSafeJsonTest {

    /**
     * Assert each input encodes to the expected script-safe JSON string literal: plain text is
     * quoted as-is, quotes/backslashes/control chars take their standard JSON escapes, and the
     * three HTML-significant characters are unicode-escaped.
     */
    @Test
    void appendStringEscapesForScriptSafety() {
        // given
        String[][] cases = {
                {"abc", "\"abc\""},
                {"a\"b", "\"a\\\"b\""},
                {"a\\b", "\"a\\\\b\""},
                {"<a>&", "\"\\u003ca\\u003e\\u0026\""},
                {"x\ty\nz", "\"x\\ty\\nz\""},
                {"", "\"\""}
        };

        // when / then
        for (String[] c : cases) {
            StringBuilder sb = new StringBuilder();
            ScriptSafeJson.appendString(sb, c[0]);
            assertEquals(c[1], sb.toString(), "escaping differs for input: " + c[0]);
        }
    }
}
