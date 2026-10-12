/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.sink.adapter.in.kafka;

/**
 * Runs after every record of a poll is durably written and before the acknowledgement. It does
 * nothing in production. It exists so {@code NoLossNoDoubleCountIT} can fail exactly between
 * the write and the ack, the one crash window the guarantee has to survive.
 */
@FunctionalInterface
public interface PreAckHook {

    PreAckHook NONE = () -> {};

    void beforeAck();
}
