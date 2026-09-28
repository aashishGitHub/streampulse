/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.platform.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Tests for {@link EventId} — the pipeline's idempotency key. */
class EventIdTest {

    private static final String VALID_UUID = "3f2504e0-4f89-41d3-9a0c-0305e82c3301";

    @Nested
    @DisplayName("validation")
    class Validation {

        @Test
        void acceptsCanonicalUuid() {
            assertThat(EventId.of(VALID_UUID).value()).isEqualTo(VALID_UUID);
        }

        @Test
        @DisplayName("uppercase input is normalised, so dedup keys match")
        void normalisesToLowercase() {
            // This matters concretely: if one retry sent uppercase and the original sent
            // lowercase, a case-sensitive Redis key would MISS and the event would be
            // processed twice. Normalising here is what prevents that.
            assertThat(EventId.of(VALID_UUID.toUpperCase()).value()).isEqualTo(VALID_UUID);
        }

        @ParameterizedTest
        @ValueSource(
                strings = {"not-a-uuid", "", "12345", "3f2504e0-4f89-41d3-9a0c", "zzzzzzzz-4f89-41d3-9a0c-0305e82c3301"
                })
        void rejectsMalformed(String candidate) {
            assertThatThrownBy(() -> EventId.of(candidate)).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void rejectsNull() {
            assertThatThrownBy(() -> EventId.of(null)).isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("the original parse failure is preserved as the cause")
        void keepsTheCause() {
            // Losing the cause makes a malformed client payload look like an internal error
            // in the logs. Cheap to preserve, expensive to debug without.
            assertThatThrownBy(() -> EventId.of("nope"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must be a valid UUID")
                    .hasCauseInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("generation")
    class Generation {

        @Test
        void randomIdsAreUnique() {
            Set<EventId> ids = new HashSet<>();
            for (int i = 0; i < 10_000; i++) {
                ids.add(EventId.random());
            }
            // Also proves equals/hashCode behave correctly as a HashSet key — if the record
            // semantics were broken, this set would collapse or over-count.
            assertThat(ids).hasSize(10_000);
        }
    }

    @Nested
    @DisplayName("idempotency key")
    class IdempotencyKey {

        @Test
        void isNamespacedByTenant() {
            // Namespacing is what stops one tenant's traffic colliding with — or evicting —
            // another's in a shared Redis. Phase 2 uses this key with SET NX EX.
            String key = EventId.of(VALID_UUID).idempotencyKey(TenantId.of("streamflix"));
            assertThat(key).isEqualTo("tenant#streamflix:event#" + VALID_UUID);
        }

        @Test
        void sameEventDifferentTenantsDoNotCollide() {
            EventId event = EventId.of(VALID_UUID);
            assertThat(event.idempotencyKey(TenantId.of("streamflix")))
                    .isNotEqualTo(event.idempotencyKey(TenantId.of("sportsnow")));
        }
    }
}
