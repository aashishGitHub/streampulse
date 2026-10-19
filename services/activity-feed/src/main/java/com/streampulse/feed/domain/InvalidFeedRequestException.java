/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.feed.domain;

/** A request the domain refuses: the adapters turn it into a 400. */
public class InvalidFeedRequestException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    public InvalidFeedRequestException(String message) {
        super(message);
    }
}
