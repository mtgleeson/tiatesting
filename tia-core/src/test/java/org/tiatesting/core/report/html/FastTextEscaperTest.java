package org.tiatesting.core.report.html;

import j2html.utils.EscapeUtil;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Unit tests proving {@link FastTextEscaper} is byte-identical to j2html's default
 * {@link EscapeUtil#escape(String)} across representative inputs, while avoiding allocation for
 * inputs that need no escaping.
 */
class FastTextEscaperTest {

    /**
     * Verify the fast escaper produces exactly the same output as {@link EscapeUtil#escape} for
     * the HTML metacharacters, already-safe strings, empty input and mixed content.
     */
    @Test
    void escapeMatchesEscapeUtilForRepresentativeInputs() {
        // given
        String[] inputs = {
                "&", "<", ">", "\"", "'",
                "a & b < c > d \" e ' f",
                "com.acme.Service.handleRequest",
                "already safe",
                "",
                "<script>alert('x & y')</script>",
                "leading&trailing'",
                "'first char escapable"
        };

        // when / then
        for (String input : inputs) {
            assertEquals(EscapeUtil.escape(input), FastTextEscaper.INSTANCE.escape(input),
                    "escaped output differs for input: " + input);
        }
    }

    /**
     * Verify a null input returns null, matching {@link EscapeUtil#escape}.
     */
    @Test
    void escapeReturnsNullForNull() {
        // given
        String input = null;

        // when
        String result = FastTextEscaper.INSTANCE.escape(input);

        // then
        assertNull(result);
    }

    /**
     * Verify an input with no escapable character is returned as the same instance (no allocation),
     * confirming the fast clean-path return.
     */
    @Test
    void escapeReturnsSameInstanceWhenNothingToEscape() {
        // given
        String input = "com.acme.synthetic.enterprise.AbstractDefaultServiceImpl.handleRequest";

        // when
        String result = FastTextEscaper.INSTANCE.escape(input);

        // then
        assertSame(input, result);
    }
}
