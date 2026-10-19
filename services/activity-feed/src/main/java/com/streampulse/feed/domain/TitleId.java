/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.feed.domain;

public record TitleId(String value) {

    public static final String PATTERN = Ids.PATTERN;

    public TitleId {
        Ids.require(value, "title id");
    }

    @Override
    public String toString() {
        return value;
    }
}
