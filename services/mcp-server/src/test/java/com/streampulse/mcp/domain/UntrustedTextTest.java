/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.mcp.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class UntrustedTextTest {

    @Test
    @DisplayName("control characters are removed")
    void stripsControlCharacters() {
        assertThat(UntrustedText.of("a\u0000b\u001Bc\u007Fd", 100).text()).isEqualTo("abcd");
    }

    @Test
    @DisplayName("line breaks and tabs become one space, so text cannot fake a new paragraph or a new message")
    void collapsesLineBreaks() {
        assertThat(UntrustedText.of("  a\n\n\r\n b\t\tc  ", 100).text()).isEqualTo("a b c");
    }

    @Test
    @DisplayName("invisible formatting characters (zero width, bidi override, tag characters) are removed")
    void stripsInvisibleFormatting() {
        String tag = new String(Character.toChars(0xE0041));
        assertThat(UntrustedText.of("a‮b​c⁦d" + tag, 100).text()).isEqualTo("abcd");
    }

    @Test
    @DisplayName("text over the cap is cut and ends with the explicit marker")
    void capsWithMarker() {
        UntrustedText t = UntrustedText.of("x".repeat(500), 200);
        assertThat(t.truncated()).isTrue();
        assertThat(t.text()).isEqualTo("x".repeat(200) + UntrustedText.MARKER);
    }

    @Test
    @DisplayName("text exactly at the cap is not truncated")
    void exactlyAtCap() {
        UntrustedText t = UntrustedText.of("y".repeat(200), 200);
        assertThat(t.truncated()).isFalse();
        assertThat(t.text()).hasSize(200);
    }

    @Test
    @DisplayName("removed characters do not use up the budget")
    void capIsMeasuredAfterSanitising() {
        UntrustedText t = UntrustedText.of("​".repeat(1000) + "ok", 10);
        assertThat(t.text()).isEqualTo("ok");
        assertThat(t.truncated()).isFalse();
    }

    @Test
    @DisplayName("a cut never splits a surrogate pair and the bound counts chars")
    void doesNotSplitSurrogatePairs() {
        String emoji = new String(Character.toChars(0x1F600));
        UntrustedText t = UntrustedText.of(emoji.repeat(50), 5);
        assertThat(t.text()).isEqualTo(emoji.repeat(2) + UntrustedText.MARKER);
    }

    @Test
    @DisplayName("null becomes empty")
    void nullIsEmpty() {
        UntrustedText t = UntrustedText.of(null, 10);
        assertThat(t.text()).isEmpty();
        assertThat(t.truncated()).isFalse();
    }
}
