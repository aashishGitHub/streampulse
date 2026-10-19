/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.alert.domain.graph;

import java.util.Objects;

/**
 * A dependency: a failure of {@code from} can cause failures in {@code to}.
 *
 * <p>Edges read "upstream to downstream", so a CDN to packager edge is {@code new Edge("CDN",
 * "PACKAGER")}. A rule on the packager that lists the CDN as upstream contributes exactly that edge.
 */
public record Edge(String from, String to) {

    public Edge {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
    }
}
