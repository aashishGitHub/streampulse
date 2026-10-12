/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.processor.domain;

import com.streampulse.platform.domain.EventId;
import com.streampulse.platform.domain.TenantId;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;

/**
 * Decides whether an event may enter an aggregate.
 *
 * <p>Only rejects what can never be correct. An event from the past is valid: lateness is the
 * window's grace period to judge, because a device that was offline for an hour is not a
 * malformed device.
 *
 * <p>Uses the platform's {@link TenantId} and {@link EventId} so the rule for what a valid id
 * is has one definition across ingest and here.
 */
public final class EventValidator {

    private final Clock clock;
    private final Duration maxFutureSkew;

    public EventValidator(Clock clock, Duration maxFutureSkew) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.maxFutureSkew = Objects.requireNonNull(maxFutureSkew, "maxFutureSkew");
    }

    public Validation validate(Observation event) {
        if (!isValidEventId(event.eventId())) {
            return Validation.invalid(RejectionReason.INVALID_EVENT_ID);
        }
        if (!isValidTenantId(event.tenantId())) {
            return Validation.invalid(RejectionReason.INVALID_TENANT_ID);
        }
        if (event.sessionId() == null || event.sessionId().isBlank()) {
            return Validation.invalid(RejectionReason.BLANK_SESSION_ID);
        }
        if (event.occurredAt().isAfter(clock.instant().plus(maxFutureSkew))) {
            // A device with a wrong clock would otherwise open a window far in the future and
            // advance stream time past every real window, making them all "late".
            return Validation.invalid(RejectionReason.EVENT_TIME_IN_FUTURE);
        }
        return Validation.valid();
    }

    private static boolean isValidEventId(String value) {
        if (value == null) {
            return false;
        }
        try {
            EventId.of(value);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean isValidTenantId(String value) {
        try {
            new TenantId(value);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
