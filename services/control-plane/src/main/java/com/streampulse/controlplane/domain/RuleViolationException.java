/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.controlplane.domain;

/** A domain invariant would be broken: a cycle, a missing parent, a dependency on another tenant. */
public class RuleViolationException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public RuleViolationException(String message) {
        super(message);
    }
}
