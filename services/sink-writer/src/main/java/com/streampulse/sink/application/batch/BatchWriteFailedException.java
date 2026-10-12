/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.sink.application.batch;

/** The record was accepted but the sink could not store it; the sink is treated as unavailable. */
public final class BatchWriteFailedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public BatchWriteFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
