/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.query.adapter.in.sse;

import com.streampulse.query.domain.EntryId;
import com.streampulse.query.domain.MetricUpdate;
import reactor.core.publisher.Flux;

/** The shared live feed, as the SSE endpoint needs it. Owned here so the outbound adapter depends on us, not the reverse. */
public interface LiveFeed {

    /** Hot: every subscriber sees entries from the moment it subscribes. Never completes. */
    Flux<MetricUpdate> live();

    /** Entries with an id strictly greater than {@code after}, oldest first; completes at the end of the feed. */
    Flux<MetricUpdate> replayAfter(EntryId after);
}
