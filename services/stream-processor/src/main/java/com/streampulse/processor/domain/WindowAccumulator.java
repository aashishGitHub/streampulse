/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.processor.domain;

import java.util.HashSet;
import java.util.Set;

/**
 * What one window knows about one {@link AggregateKey}.
 *
 * <p>Immutable: {@link #add} returns a new value. That copies the session set on every event,
 * which is O(sessions) per update. It is the exact answer to "how many distinct viewers", and
 * the cost is the reason a production system at this scale would use an approximate sketch
 * (HyperLogLog) instead. State stores serialise the whole value per update anyway, so the
 * copy does not change the order of growth.
 */
public record WindowAccumulator(AggregateKey key, long events, long errors, Set<String> sessions) {

    public WindowAccumulator {
        sessions = Set.copyOf(sessions);
    }

    public static WindowAccumulator empty() {
        return new WindowAccumulator(null, 0, 0, Set.of());
    }

    public WindowAccumulator add(Observation event) {
        Set<String> next = new HashSet<>(sessions);
        next.add(event.sessionId());
        return new WindowAccumulator(
                key == null ? AggregateKey.of(event) : key, events + 1, errors + (event.error() ? 1 : 0), next);
    }
}
