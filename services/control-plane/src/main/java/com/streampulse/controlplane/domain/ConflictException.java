/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.controlplane.domain;

/** The request is well-formed but the current state forbids it, e.g. deleting something still referenced. */
public class ConflictException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public ConflictException(String message) {
        super(message);
    }
}
