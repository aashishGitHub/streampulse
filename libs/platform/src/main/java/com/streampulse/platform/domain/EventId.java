/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.platform.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * The client-generated unique id of a telemetry event — and therefore the
 * <b>idempotency key</b> for the entire pipeline.
 *
 * <h2>Why the CLIENT generates it, not the server</h2>
 *
 * This is the design decision that makes the whole system safe to retry, and it is worth
 * being able to defend in an interview.
 *
 * <pre>
 *   player ──[event, id=abc]──▶ ingest ──▶ Kafka
 *      │                           │
 *      │      ✗ network drops the RESPONSE (the write already happened)
 *      │
 *      └──[event, id=abc]──▶ ingest ──▶ "already seen abc" → 200 OK, no duplicate
 *              retry
 * </pre>
 *
 * If the server generated the id, the retry would be a <em>different</em> event and the
 * duplicate would be undetectable. Because the client generates it once and reuses it on
 * every retry, at-least-once delivery plus a dedup check becomes <b>effectively-once</b>
 * processing.
 *
 * <p>This is the same reasoning behind Stripe's {@code Idempotency-Key} header, and it is
 * the answer to "how do you handle retries without duplicating work?".
 *
 * <h2>Why UUID specifically</h2>
 *
 * It can be generated offline, on-device, with no coordination and no round trip — which
 * matters because players buffer telemetry while offline and flush later. A server-assigned
 * sequence number would require a call the client may not be able to make.
 *
 * <p>We store the canonical <em>string</em> rather than {@link UUID} because the value
 * crosses Avro, JSON and protobuf boundaries as text, and re-parsing at every hop is wasted
 * work. We validate the format once, here.
 *
 * @param value canonical lowercase UUID string
 */
public record EventId(String value) {

    public EventId {
        Objects.requireNonNull(value, "eventId must not be null");

        // Parse to validate, then discard the result. UUID.fromString is the authoritative
        // check — a hand-rolled regex would accept malformed input that the JDK rejects.
        //
        // Note: UUID.fromString is historically lenient about some malformed inputs, so we
        // also compare the round-tripped form. If parsing and re-printing does not reproduce
        // the input, the input was not canonical.
        try {
            UUID parsed = UUID.fromString(value);
            if (!parsed.toString().equalsIgnoreCase(value)) {
                throw new IllegalArgumentException("eventId is not a canonical UUID: '" + value + "'");
            }
        } catch (IllegalArgumentException e) {
            // Rethrow with context. Swallowing the cause here would make a bad client
            // payload look like an internal error in the logs.
            throw new IllegalArgumentException("eventId must be a valid UUID, was: '" + value + "'", e);
        }

        value = value.toLowerCase(java.util.Locale.ROOT); // canonical form for dedup keys
    }

    public static EventId of(String value) {
        return new EventId(value);
    }

    /**
     * Generates a new id. Used by the load generator and by tests — <b>never</b> by the
     * ingest path, which must use the id the client sent, or idempotency is lost.
     */
    public static EventId random() {
        return new EventId(UUID.randomUUID().toString());
    }

    /**
     * Redis key used to claim this event exactly once.
     *
     * <p>Namespaced by tenant so one tenant's traffic can never collide with — or evict —
     * another's. Phase 2 uses this with {@code SET NX EX}, where the atomicity of "set if
     * absent" is what makes the dedup correct under concurrency.
     */
    public String idempotencyKey(TenantId tenant) {
        return tenant.keyPrefix() + ":event#" + value;
    }

    @Override
    public String toString() {
        return value;
    }
}
