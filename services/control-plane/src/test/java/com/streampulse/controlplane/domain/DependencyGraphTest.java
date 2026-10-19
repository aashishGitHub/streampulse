/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.controlplane.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DependencyGraphTest {

    @Test
    @DisplayName("a chain sorts dependencies first: CDN, then packager, then player")
    void chainIsOrderedUpstreamFirst() {
        var graph = new DependencyGraph<String>()
                .addDependency("player", "packager")
                .addDependency("packager", "cdn");

        assertThat(graph.topologicalOrder()).containsExactly("cdn", "packager", "player");
    }

    @Test
    @DisplayName("a diamond is not a cycle")
    void diamondIsAcyclic() {
        var graph = new DependencyGraph<String>()
                .addDependency("b", "a")
                .addDependency("c", "a")
                .addDependency("d", "b")
                .addDependency("d", "c");

        assertThatCode(graph::assertAcyclic).doesNotThrowAnyException();
        assertThat(graph.topologicalOrder()).startsWith("a").endsWith("d");
    }

    @Test
    @DisplayName("a node depending on itself is a cycle")
    void selfDependencyIsACycle() {
        var graph = new DependencyGraph<String>().addDependency("a", "a");

        assertThatThrownBy(graph::assertAcyclic)
                .isInstanceOf(RuleViolationException.class)
                .hasMessageContaining("a");
    }

    @Test
    @DisplayName("a two-node loop is a cycle and names both nodes")
    void twoNodeLoop() {
        var graph = new DependencyGraph<String>().addDependency("a", "b").addDependency("b", "a");

        assertThatThrownBy(graph::assertAcyclic)
                .isInstanceOf(RuleViolationException.class)
                .hasMessageContaining("[a, b]");
    }

    @Test
    @DisplayName("a three-node loop with a healthy tail still reports the loop")
    void threeNodeLoopAmongOthers() {
        var graph = new DependencyGraph<String>()
                .addDependency("a", "b")
                .addDependency("b", "c")
                .addDependency("c", "a")
                .addDependency("z", "ok");

        assertThatThrownBy(graph::assertAcyclic)
                .isInstanceOf(RuleViolationException.class)
                .hasMessageContaining("[a, b, c]");
    }

    @Test
    @DisplayName("an empty graph is acyclic")
    void emptyGraph() {
        assertThat(new DependencyGraph<String>().topologicalOrder()).isEmpty();
    }
}
