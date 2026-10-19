/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.alert.domain.graph;

import java.util.List;

/** Thrown when a dependency set is not acyclic. The message names one concrete cycle. */
public final class CyclicDependencyException extends IllegalArgumentException {

    private final List<String> cycle;

    /** @param cycle the nodes in dependency order with the first repeated last, e.g. {@code [A, B, A]} */
    public CyclicDependencyException(List<String> cycle) {
        super("dependency cycle: " + String.join(" -> ", cycle));
        this.cycle = List.copyOf(cycle);
    }

    /** The cycle in dependency order, first node repeated at the end. */
    public List<String> cycle() {
        return cycle;
    }
}
