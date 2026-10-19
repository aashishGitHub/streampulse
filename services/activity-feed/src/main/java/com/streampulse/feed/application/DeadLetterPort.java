/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.feed.application;

public interface DeadLetterPort {

    /** Parks the original bytes and key, with the reason. Throws {@link DeadLetterUnavailableException} if it cannot. */
    void deadLetter(String key, byte[] value, String reason);
}
