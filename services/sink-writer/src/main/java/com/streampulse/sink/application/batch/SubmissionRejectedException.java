/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.sink.application.batch;

/** The writer did not accept a record. It was never queued, so nothing was written. */
public final class SubmissionRejectedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public enum Reason {
        BUFFER_FULL,
        CLOSED,
        INTERRUPTED
    }

    private final transient Reason reason;

    public SubmissionRejectedException(Reason reason) {
        super("Submission rejected: " + reason);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
