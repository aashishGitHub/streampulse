/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.feed.domain;

/**
 * The alphabet excludes ':' on purpose: a timeline member is {@code eventId:author:title}, and
 * splitting it is only unambiguous if no id can contain the delimiter.
 */
public record UserId(String value) {

    public static final String PATTERN = Ids.PATTERN;

    public UserId {
        Ids.require(value, "user id");
    }

    @Override
    public String toString() {
        return value;
    }
}
