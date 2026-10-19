/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.feed.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class UserIdTest {

    @ParameterizedTest
    @ValueSource(strings = {"u1", "User_7-b", "a", "0123456789012345678901234567890123456789012345678901234567890123"})
    @DisplayName("an id of 1 to 64 letters, digits, underscores and hyphens is accepted")
    void acceptsWellFormedIds(String value) {
        assertThat(new UserId(value).value()).isEqualTo(value);
        assertThat(new TitleId(value).value()).isEqualTo(value);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(
            strings = {
                " ",
                "a b",
                "a:b",
                "a/b",
                "naïve",
                "01234567890123456789012345678901234567890123456789012345678901234"
            })
    @DisplayName("anything else is rejected, and ':' above all, because it delimits a timeline member")
    void rejectsMalformedIds(String value) {
        assertThatThrownBy(() -> new UserId(value)).isInstanceOf(InvalidFeedRequestException.class);
        assertThatThrownBy(() -> new TitleId(value)).isInstanceOf(InvalidFeedRequestException.class);
    }

    @Test
    @DisplayName("the error does not echo an arbitrarily long value")
    void errorMessageIsBounded() {
        assertThatThrownBy(() -> new UserId("x".repeat(10_000))).hasMessageNotContaining("x".repeat(100));
    }
}
