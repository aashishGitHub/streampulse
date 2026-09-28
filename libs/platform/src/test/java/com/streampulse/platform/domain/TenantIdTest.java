/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.platform.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for {@link TenantId}.
 *
 * <p>Note what is being asserted: mostly <b>rejection</b>. For a validated value object the
 * interesting behaviour is which inputs it refuses, because every input it wrongly accepts
 * becomes a malformed key somewhere downstream — in Kafka, Redis, DynamoDB or a Prometheus
 * label — and surfaces months later for one customer.
 */
class TenantIdTest {

    @Nested
    @DisplayName("normalisation")
    class Normalisation {

        @Test
        void lowercasesAndTrims() {
            assertThat(TenantId.of("  StreamFlix  ").value()).isEqualTo("streamflix");
        }

        @Test
        @DisplayName("two spellings of the same tenant are equal")
        void equalityIgnoresOriginalCasing() {
            // Records generate equals/hashCode from the components. Because we normalise in
            // the compact constructor, both instances hold the same value and are equal —
            // which is what makes TenantId safe as a HashMap key.
            assertThat(TenantId.of("SportsNow")).isEqualTo(TenantId.of("sportsnow"));
            assertThat(TenantId.of("SportsNow")).hasSameHashCodeAs(TenantId.of("sportsnow"));
        }

        @Test
        @DisplayName("toString is the bare value, not the record's default")
        void toStringIsLogFriendly() {
            // The generated form would be "TenantId[value=streamflix]" — noisy in logs and
            // wrong if it ever leaked into a key.
            assertThat(TenantId.of("streamflix")).hasToString("streamflix");
        }
    }

    @Nested
    @DisplayName("accepts valid identifiers")
    class Valid {

        @ParameterizedTest
        @ValueSource(strings = {"streamflix", "sportsnow", "kids-plus", "a", "a1", "t-1-2", "abc123"})
        void acceptsWellFormed(String candidate) {
            assertThat(TenantId.of(candidate).value()).isEqualTo(candidate);
        }

        @Test
        void acceptsExactlyThirtyTwoCharacters() {
            String max = "a".repeat(32);
            assertThat(TenantId.of(max).value()).hasSize(32);
        }
    }

    @Nested
    @DisplayName("rejects anything that would break a downstream key")
    class Invalid {

        @ParameterizedTest
        @ValueSource(
                strings = {
                    "has space", // breaks Prometheus labels and S3 prefixes
                    "has/slash", // breaks S3 object keys
                    "has#hash", // collides with our own key separator
                    "-leading", // must start alphanumeric
                    "trailing-", // must end alphanumeric
                    "UPPER_SNAKE", // underscore not allowed
                    "dots.not.allowed",
                    "emoji🎬"
                })
        void rejectsMalformed(String candidate) {
            assertThatThrownBy(() -> TenantId.of(candidate)).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void rejectsNull() {
            assertThatThrownBy(() -> TenantId.of(null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must not be null");
        }

        @Test
        void rejectsBlank() {
            assertThatThrownBy(() -> TenantId.of("   "))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must not be blank");
        }

        @Test
        void rejectsTooLong() {
            assertThatThrownBy(() -> TenantId.of("a".repeat(33)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("at most 32");
        }
    }

    @Nested
    @DisplayName("key layout")
    class KeyLayout {

        @Test
        void keyPrefixIsDefinedInExactlyOnePlace() {
            // Asserting the literal format on purpose: it is a CONTRACT shared by Redis,
            // DynamoDB and S3. Changing it must be a deliberate act that breaks this test,
            // not an accident in a string concatenation somewhere.
            assertThat(TenantId.of("streamflix").keyPrefix()).isEqualTo("tenant#streamflix");
        }
    }
}
