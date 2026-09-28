/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.ingest.domain;

import com.streampulse.platform.domain.EventId;
import com.streampulse.platform.domain.TenantId;
import java.util.List;

/**
 * Outbound port: "have I already seen this event?"
 *
 * <h2>Why claim() and not contains()</h2>
 *
 * A {@code contains()} check followed by a write is a check-then-act race: two concurrent
 * requests both see "absent" and both publish. The whole point is a single <b>atomic</b>
 * operation that tests and sets together — Redis {@code SET NX EX}.
 *
 * <p>Naming it {@code claim} rather than {@code check} is deliberate: it says the call has a
 * side effect, so nobody later "optimises" it into a cached read.
 *
 * <h2>Failing open</h2>
 *
 * When the store is unavailable, implementations return {@link ClaimResult#unavailable()}
 * rather than throwing. The use case then publishes anyway and records a metric
 * (REQ-IG-14): at-least-once with some duplicates is better than losing telemetry outright.
 * That is a deliberate trade — for a payments system the correct choice would be the
 * opposite, and the ADR says so.
 */
public interface IdempotencyStore {

    /**
     * Atomically claims each id. Returns the subset that is genuinely new.
     *
     * @return which ids were claimed, or {@link ClaimResult#unavailable()} if the store could
     *     not be reached
     */
    ClaimResult claimAll(TenantId tenant, List<EventId> eventIds);

    /**
     * Gives back claims for events that were never published.
     *
     * <h2>Why this exists</h2>
     *
     * A claim is a <b>reservation</b>, not a record of completion. {@link #claimAll} runs
     * <em>before</em> the publish, so a batch that fails to publish leaves reservations for
     * events that never arrived anywhere. The client retries, every id comes back as
     * already-seen, and the service answers {@code 202} having published nothing.
     *
     * <p>That is silent, permanent data loss reported as success — the worst failure mode a
     * pipeline can have, because no metric moves and no alert fires.
     *
     * <h2>The duplicate it can cause, and why that is the right trade</h2>
     *
     * If the publish actually succeeded but we could not observe it (a timeout that fired
     * while the write was landing), releasing lets a retry publish the event twice.
     *
     * <p>That is the same trade as failing open, made in the same direction on purpose:
     * duplicates are absorbed downstream by idempotent aggregation, a gap is unrecoverable.
     * ADR-0007.
     *
     * <p>Best-effort by contract: implementations must not throw. A failed release costs a
     * possible lost retry, and throwing here would replace the original publish failure with
     * a less useful one.
     */
    void release(TenantId tenant, List<EventId> eventIds);

    /**
     * @param claimed ids that were not seen before — these must be published
     * @param alreadySeen ids that were already claimed — these are duplicates
     * @param storeAvailable false when the store could not be reached; the other fields are
     *     then meaningless and the caller must fail open
     */
    record ClaimResult(List<EventId> claimed, List<EventId> alreadySeen, boolean storeAvailable) {

        public static ClaimResult unavailable() {
            return new ClaimResult(List.of(), List.of(), false);
        }

        public static ClaimResult of(List<EventId> claimed, List<EventId> alreadySeen) {
            return new ClaimResult(List.copyOf(claimed), List.copyOf(alreadySeen), true);
        }
    }
}
