/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.query.application;

/** The caller asked for something that cannot be answered as asked (maps to a 400). */
public class InvalidRequestException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public InvalidRequestException(String message) {
        super(message);
    }
}
