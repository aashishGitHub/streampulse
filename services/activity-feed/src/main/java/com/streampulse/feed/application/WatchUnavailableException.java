/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.feed.application;

/** The report could not be recorded, so it must not be acknowledged. */
public class WatchUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public WatchUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
