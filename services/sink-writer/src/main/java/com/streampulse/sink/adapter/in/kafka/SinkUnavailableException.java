/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.sink.adapter.in.kafka;

/** The poll could not be made durable. Thrown so the container redelivers it; nothing is skipped. */
public final class SinkUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public SinkUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
