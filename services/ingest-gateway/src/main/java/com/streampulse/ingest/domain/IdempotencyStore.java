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
