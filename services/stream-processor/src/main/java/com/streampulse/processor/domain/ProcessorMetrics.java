/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.processor.domain;

/**
 * What the topology reports about itself. A port, so the topology can be tested without a
 * metrics registry and the registry can be swapped without touching the topology.
 */
public interface ProcessorMetrics {

    void rejected(RejectionReason reason);

    void duplicateDropped();

    ProcessorMetrics NONE = new ProcessorMetrics() {
        @Override
        public void rejected(RejectionReason reason) {}

        @Override
        public void duplicateDropped() {}
    };
}
