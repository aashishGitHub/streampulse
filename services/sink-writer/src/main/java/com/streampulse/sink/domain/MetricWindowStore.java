/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.sink.domain;

import java.util.List;

/** The durable store. */
public interface MetricWindowStore {

    /**
     * Stores every window or none. Idempotent, and a window computed earlier never replaces one
     * computed later. Throws if the store is unavailable or rejects the data.
     */
    void upsertAll(List<MetricWindow> windows);
}
