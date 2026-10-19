/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.alert.domain.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DependencyGraphTest {

    private static Edge e(String from, String to) {
        return new Edge(from, to);
    }

    @Test
    @DisplayName("upstream levels of a chain are its ancestors by distance")
    void chain() {
        var graph = DependencyGraph.of(List.of(e("CDN", "PACKAGER"), e("PACKAGER", "PLAYER")));

        assertThat(graph.upstreamLevels("PLAYER")).containsExactly(List.of("PACKAGER"), List.of("CDN"));
        assertThat(graph.upstreamLevels("CDN")).isEmpty();
    }

    @Test
    @DisplayName("in a diamond, both parents are one level up and the shared root is two up, once")
    void diamond() {
        var graph = DependencyGraph.of(List.of(e("A", "B"), e("A", "C"), e("B", "D"), e("C", "D")));

        assertThat(graph.upstreamLevels("D")).containsExactly(List.of("B", "C"), List.of("A"));
        assertThat(graph.downstreamBreadthFirst("A")).containsExactly("B", "C", "D");
    }

    @Test
    @DisplayName("a node reachable by a short and a long path is reported at its shortest distance")
    void shortestDistanceWins() {
        var graph = DependencyGraph.of(List.of(e("A", "B"), e("B", "C"), e("A", "C")));

        assertThat(graph.upstreamLevels("C")).containsExactly(List.of("A", "B"));
    }

    @Test
    @DisplayName("a long chain is searched to its end")
    void longChain() {
        var edges = new java.util.ArrayList<Edge>();
        for (int i = 0; i < 500; i++) {
            edges.add(e("N" + String.format("%04d", i), "N" + String.format("%04d", i + 1)));
        }
        var graph = DependencyGraph.of(edges);

        assertThat(graph.downstreamBreadthFirst("N0000")).hasSize(500).last().isEqualTo("N0500");
        assertThat(graph.upstreamLevels("N0500")).hasSize(500);
    }

    @Test
    @DisplayName("ancestors rank before descendants")
    void rank() {
        var graph = DependencyGraph.of(List.of(e("CDN", "PACKAGER"), e("PACKAGER", "PLAYER")));

        assertThat(graph.rank("CDN")).isLessThan(graph.rank("PACKAGER"));
        assertThat(graph.rank("PACKAGER")).isLessThan(graph.rank("PLAYER"));
    }

    @Test
    @DisplayName("a cyclic edge set cannot become a graph")
    void cycleRejected() {
        assertThatThrownBy(() -> DependencyGraph.of(List.of(e("A", "B"), e("B", "A"))))
                .isInstanceOf(CyclicDependencyException.class);
    }

    @Test
    @DisplayName("an unknown node has no neighbours")
    void unknownNode() {
        assertThat(DependencyGraph.EMPTY.upstreamLevels("X")).isEmpty();
        assertThat(DependencyGraph.EMPTY.downstreamBreadthFirst("X")).isEmpty();
    }
}
