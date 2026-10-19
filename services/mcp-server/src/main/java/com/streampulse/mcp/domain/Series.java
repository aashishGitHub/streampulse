/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.mcp.domain;

import java.util.Optional;

/** A series label {@code titleId:region:metric} where {@code *} (here: null) spans all titles or regions. */
public record Series(String titleId, String region, Metric metric) {

    private static final String ALL = "*";

    public static Optional<Series> parse(String label) {
        if (label == null) {
            return Optional.empty();
        }
        String[] parts = label.split(":", -1);
        if (parts.length != 3 || !validPart(parts[0]) || !validPart(parts[1])) {
            return Optional.empty();
        }
        return Metric.parse(parts[2]).map(metric -> new Series(orNull(parts[0]), orNull(parts[1]), metric));
    }

    private static boolean validPart(String part) {
        return ALL.equals(part) || SafeId.name(part).isPresent();
    }

    private static String orNull(String part) {
        return ALL.equals(part) ? null : part;
    }
}
