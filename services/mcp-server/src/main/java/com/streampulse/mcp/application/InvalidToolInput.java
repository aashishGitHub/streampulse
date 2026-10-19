/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.mcp.application;

/** A tool argument failed validation. The message names the argument and never echoes its value. */
public final class InvalidToolInput extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public InvalidToolInput(String message) {
        super(message);
    }
}
