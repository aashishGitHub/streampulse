/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.feed.domain;

final class Ids {

    static final String PATTERN = "[A-Za-z0-9_-]{1,64}";
    private static final java.util.regex.Pattern COMPILED = java.util.regex.Pattern.compile(PATTERN);

    private Ids() {}

    static String require(String value, String what) {
        if (value == null || !COMPILED.matcher(value).matches()) {
            // Not echoed in full: the value is attacker-controlled and may be enormous.
            String shown = value == null ? "null" : value.length() > 16 ? value.substring(0, 16) + "..." : value;
            throw new InvalidFeedRequestException(
                    what + " must match " + PATTERN + " (1 to 64 letters, digits, '_' or '-'), got '" + shown + "'");
        }
        return value;
    }
}
