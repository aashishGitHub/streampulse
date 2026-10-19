/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.alert.adapter.in.kafka;

/** A rule definition that cannot be understood. The reason is a short code for metrics. */
public final class RuleParseException extends Exception {

    private static final long serialVersionUID = 1L;

    private final String reason;

    public RuleParseException(String reason, String detail, Throwable cause) {
        super(detail, cause);
        this.reason = reason;
    }

    public String reason() {
        return reason;
    }
}
