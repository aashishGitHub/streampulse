/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.controlplane.domain;

public class InvalidInputException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public InvalidInputException(String message) {
        super(message);
    }
}
