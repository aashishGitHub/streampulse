/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.loadgen;

public final class ArgumentException extends RuntimeException {

    public ArgumentException(String message) {
        super(message);
    }
}
