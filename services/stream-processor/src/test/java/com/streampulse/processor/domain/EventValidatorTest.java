/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.processor.domain;

import static com.streampulse.processor.domain.ObservationFixtures.NOW;
import static com.streampulse.processor.domain.ObservationFixtures.id;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class EventValidatorTest {

    private static final Duration SKEW = Duration.ofMinutes(5);
    private final EventValidator validator = new EventValidator(Clock.fixed(NOW, ZoneOffset.UTC), SKEW);

    private static Observation with(String eventId, String tenant, String session, Instant at) {
        return new Observation(eventId, tenant, session, "title-1", "us-east-1", false, at);
    }

    @Test
    @DisplayName("a well-formed event is valid")
    void validEvent() {
        assertThat(validator.validate(with(id(1), "streamflix", "s-1", NOW))).isEqualTo(Validation.valid());
    }

    @Test
    @DisplayName("an event from the past is valid: lateness is the window's problem, not the validator's")
    void oldEventIsValid() {
        assertThat(validator.validate(with(id(1), "streamflix", "s-1", NOW.minus(Duration.ofDays(2)))))
                .isEqualTo(Validation.valid());
    }

    @Test
    @DisplayName("blank identifiers are rejected with a stable reason")
    void blankIdentifiers() {
        assertThat(validator.validate(with(" ", "streamflix", "s-1", NOW)))
                .isEqualTo(Validation.invalid(RejectionReason.INVALID_EVENT_ID));
        assertThat(validator.validate(with(id(1), "", "s-1", NOW)))
                .isEqualTo(Validation.invalid(RejectionReason.INVALID_TENANT_ID));
        assertThat(validator.validate(with(id(1), "streamflix", "", NOW)))
                .isEqualTo(Validation.invalid(RejectionReason.BLANK_SESSION_ID));
    }

    @Test
    @DisplayName("a tenant id the platform would refuse is rejected here too")
    void tenantIdUsesThePlatformRule() {
        assertThat(validator.validate(with(id(1), "Not Valid!", "s-1", NOW)))
                .isEqualTo(Validation.invalid(RejectionReason.INVALID_TENANT_ID));
    }

    @Test
    @DisplayName("an event up to the permitted skew in the future is valid, beyond it is rejected")
    void futureSkewBoundary() {
        assertThat(validator.validate(with(id(1), "streamflix", "s-1", NOW.plus(SKEW))))
                .isEqualTo(Validation.valid());
        assertThat(validator.validate(
                        with(id(1), "streamflix", "s-1", NOW.plus(SKEW).plusMillis(1))))
                .isEqualTo(Validation.invalid(RejectionReason.EVENT_TIME_IN_FUTURE));
    }

    @Test
    @DisplayName("an event id that is not a UUID is rejected, as is a missing one")
    void eventIdMustBeAUuid() {
        assertThat(validator.validate(with("not-a-uuid", "streamflix", "s-1", NOW)))
                .isEqualTo(Validation.invalid(RejectionReason.INVALID_EVENT_ID));
        assertThat(validator.validate(with(null, "streamflix", "s-1", NOW)))
                .isEqualTo(Validation.invalid(RejectionReason.INVALID_EVENT_ID));
    }
}
