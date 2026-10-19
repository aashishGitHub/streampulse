/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.feed.domain;

/** Malformed, altered or foreign. The message never echoes the token. */
public final class InvalidCursorException extends InvalidFeedRequestException {

    private static final long serialVersionUID = 1L;

    public InvalidCursorException(String message) {
        super(message);
    }
}
