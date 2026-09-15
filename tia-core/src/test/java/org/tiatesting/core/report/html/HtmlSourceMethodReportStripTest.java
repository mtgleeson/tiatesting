package org.tiatesting.core.report.html;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Unit tests proving {@link HtmlSourceMethodReport#stripAngleBrackets(String)} is byte-identical
 * to the previous {@code replaceAll("<", "").replaceAll(">", "")} it replaced.
 */
class HtmlSourceMethodReportStripTest {

    /**
     * Verify the single-pass strip returns exactly what the two chained regex replacements
     * produced, for names with and without angle brackets.
     */
    @Test
    void stripMatchesPreviousReplaceAllBehaviour() {
        // given
        String[] inputs = {
                "/tmp/report/methods/123456.html",
                "/tmp/report/methods/<init>.html",
                "a<b>c<d>e",
                "<<>>",
                "no brackets here",
                ""
        };

        // when / then
        for (String input : inputs) {
            String expected = input.replaceAll("<", "").replaceAll(">", "");
            assertEquals(expected, HtmlSourceMethodReport.stripAngleBrackets(input),
                    "strip differs for input: " + input);
        }
    }

    /**
     * Verify a name with no angle brackets is returned as the same instance (no needless copy),
     * confirming the clean-path fast return.
     */
    @Test
    void stripReturnsSameInstanceWhenNoBrackets() {
        // given
        String input = "/tmp/report/methods/987654.html";

        // when
        String result = HtmlSourceMethodReport.stripAngleBrackets(input);

        // then
        assertSame(input, result);
    }
}
