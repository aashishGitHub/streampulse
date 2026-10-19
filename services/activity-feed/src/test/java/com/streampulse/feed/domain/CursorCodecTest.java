/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.feed.domain;

import static com.streampulse.feed.support.Fixtures.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class CursorCodecTest {

    private static final byte[] SECRET = "a-test-secret-that-is-long-enough".getBytes(StandardCharsets.UTF_8);
    private static final Cursor CURSOR =
            new Cursor(1_760_356_800_000L, 0.123456789012345678, "11111111-2222-3333-4444-555555555555:alice:dune");

    private final CursorCodec codec = new CursorCodec(SECRET);

    @Test
    @DisplayName("a cursor round-trips exactly, including the bits of the score")
    void roundTrips() {
        String token = codec.encode(user("reader"), CURSOR);

        Cursor decoded = codec.decode(user("reader"), token);

        assertThat(decoded).isEqualTo(CURSOR);
        assertThat(Double.doubleToLongBits(decoded.score())).isEqualTo(Double.doubleToLongBits(CURSOR.score()));
    }

    @Test
    @DisplayName("the token is URL-safe: nothing to escape in a query string")
    void tokenIsUrlSafe() {
        assertThat(codec.encode(user("reader"), CURSOR)).matches("[A-Za-z0-9_-]+");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(
            strings = {" ", "not base64 !!", "%%%%", "AAAA", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"})
    @DisplayName("garbage is rejected")
    void rejectsGarbage(String token) {
        assertThatThrownBy(() -> codec.decode(user("reader"), token)).isInstanceOf(InvalidCursorException.class);
    }

    @Test
    @DisplayName("an oversized token is rejected before any decoding")
    void rejectsAnOversizedToken() {
        assertThatThrownBy(() -> codec.decode(user("reader"), "A".repeat(100_000)))
                .isInstanceOf(InvalidCursorException.class);
    }

    @Test
    @DisplayName("flipping any single byte of a valid token is detected")
    void rejectsATamperedPayload() {
        byte[] raw = Base64.getUrlDecoder().decode(codec.encode(user("reader"), CURSOR));

        for (int i = 0; i < raw.length; i++) {
            byte[] altered = raw.clone();
            altered[i] ^= 0x01;
            String token = Base64.getUrlEncoder().withoutPadding().encodeToString(altered);

            assertThatThrownBy(() -> codec.decode(user("reader"), token))
                    .as("byte %d altered", i)
                    .isInstanceOf(InvalidCursorException.class);
        }
    }

    @Test
    @DisplayName("a truncated token is rejected")
    void rejectsATruncatedToken() {
        String token = codec.encode(user("reader"), CURSOR);

        assertThatThrownBy(() -> codec.decode(user("reader"), token.substring(0, token.length() - 5)))
                .isInstanceOf(InvalidCursorException.class);
    }

    @Test
    @DisplayName("a token with bytes appended is rejected")
    void rejectsATokenWithTrailingBytes() {
        byte[] raw = Base64.getUrlDecoder().decode(codec.encode(user("reader"), CURSOR));
        byte[] longer = java.util.Arrays.copyOf(raw, raw.length + 3);

        assertThatThrownBy(() -> codec.decode(
                        user("reader"), Base64.getUrlEncoder().withoutPadding().encodeToString(longer)))
                .isInstanceOf(InvalidCursorException.class);
    }

    @Test
    @DisplayName("a cursor issued to one user is refused for another")
    void rejectsACursorIssuedToAnotherUser() {
        String token = codec.encode(user("reader"), CURSOR);

        assertThatThrownBy(() -> codec.decode(user("someone-else"), token)).isInstanceOf(InvalidCursorException.class);
    }

    @Test
    @DisplayName("a token signed with a different secret is refused")
    void rejectsADifferentSecret() {
        String token = codec.encode(user("reader"), CURSOR);
        var other = new CursorCodec("another-secret-that-is-long-enough!".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> other.decode(user("reader"), token)).isInstanceOf(InvalidCursorException.class);
    }

    @Test
    @DisplayName("a weak signing secret is refused at construction")
    void rejectsAShortSecret() {
        assertThatThrownBy(() -> new CursorCodec("short".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the rejection never echoes the token back")
    void theMessageDoesNotEchoTheToken() {
        assertThatThrownBy(() -> codec.decode(user("reader"), "SECRET-LOOKING-GARBAGE"))
                .hasMessageNotContaining("SECRET-LOOKING-GARBAGE");
    }
}
