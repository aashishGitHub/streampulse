/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.feed.application;

/** Acknowledging past a record that could not be parked would lose it. */
public class DeadLetterUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public DeadLetterUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
