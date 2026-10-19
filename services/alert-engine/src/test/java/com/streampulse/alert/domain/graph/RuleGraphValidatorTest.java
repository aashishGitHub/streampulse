/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.alert.domain.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RuleGraphValidatorTest {

    private static Edge e(String from, String to) {
        return new Edge(from, to);
    }

    @Test
    @DisplayName("an acyclic graph yields a topological order: every edge points forward")
    void acyclicOrder() {
        List<Edge> edges = List.of(e("CDN", "PACKAGER"), e("PACKAGER", "PLAYER"), e("CDN", "PLAYER"));

        List<String> order = RuleGraphValidator.topologicalOrder(edges);

        assertThat(order).containsExactly("CDN", "PACKAGER", "PLAYER");
    }

    @Test
    @DisplayName("ties are broken by name, so the order is deterministic")
    void deterministicOrder() {
        List<Edge> edges = List.of(e("R", "C"), e("R", "B"), e("R", "A"), e("B", "Z"), e("A", "Z"));

        assertThat(RuleGraphValidator.topologicalOrder(edges)).containsExactly("R", "A", "B", "C", "Z");
    }

    @Test
    @DisplayName("isolated nodes are part of the order")
    void isolatedNodes() {
        assertThat(RuleGraphValidator.topologicalOrder(List.of("LONELY"), List.of(e("A", "B"))))
                .containsExactly("A", "B", "LONELY");
    }

    @Test
    @DisplayName("an empty graph is valid")
    void empty() {
        assertThat(RuleGraphValidator.topologicalOrder(List.of())).isEmpty();
    }

    @Test
    @DisplayName("a self-loop is a cycle and the message says so")
    void selfLoop() {
        assertThatThrownBy(() -> RuleGraphValidator.validate(List.of(e("CDN", "CDN"))))
                .isInstanceOf(CyclicDependencyException.class)
                .hasMessage("dependency cycle: CDN -> CDN")
                .extracting(t -> ((CyclicDependencyException) t).cycle())
                .isEqualTo(List.of("CDN", "CDN"));
    }

    @Test
    @DisplayName("a two-node cycle is named")
    void twoCycle() {
        assertThatThrownBy(() -> RuleGraphValidator.validate(List.of(e("B", "A"), e("A", "B"))))
                .isInstanceOf(CyclicDependencyException.class)
                .hasMessage("dependency cycle: A -> B -> A");
    }

    @Test
    @DisplayName("a three-cycle is named in dependency order, starting from its smallest node")
    void threeCycle() {
        assertThatThrownBy(() -> RuleGraphValidator.validate(List.of(e("B", "C"), e("C", "A"), e("A", "B"))))
                .isInstanceOf(CyclicDependencyException.class)
                .hasMessage("dependency cycle: A -> B -> C -> A");
    }

    @Test
    @DisplayName("nodes merely downstream of a cycle are not reported as part of it")
    void tailsAreNotInTheCycle() {
        List<Edge> edges = List.of(e("X", "A"), e("A", "B"), e("B", "A"), e("B", "TAIL"));

        assertThatThrownBy(() -> RuleGraphValidator.validate(edges))
                .isInstanceOf(CyclicDependencyException.class)
                .hasMessage("dependency cycle: A -> B -> A");
    }

    @Test
    @DisplayName("a cycle hiding beside a healthy part of the graph is still found")
    void cycleBesideHealthyPart() {
        List<Edge> edges =
                List.of(e("CDN", "PACKAGER"), e("PACKAGER", "PLAYER"), e("P", "Q"), e("Q", "R"), e("R", "P"));

        assertThatThrownBy(() -> RuleGraphValidator.validate(edges))
                .isInstanceOf(CyclicDependencyException.class)
                .hasMessage("dependency cycle: P -> Q -> R -> P");
    }

    @Test
    @DisplayName("validating a candidate rejects the one edge that closes the loop")
    void candidateClosesLoop() {
        List<Edge> existing = List.of(e("CDN", "PACKAGER"), e("PACKAGER", "PLAYER"));

        assertThatCode(() -> RuleGraphValidator.validate(existing, "PLAYER", List.of("CDN")))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> RuleGraphValidator.validate(existing, "CDN", List.of("PLAYER")))
                .isInstanceOf(CyclicDependencyException.class)
                .hasMessage("dependency cycle: CDN -> PACKAGER -> PLAYER -> CDN");
    }

    @Test
    @DisplayName("duplicate edges are harmless")
    void duplicateEdges() {
        assertThat(RuleGraphValidator.topologicalOrder(List.of(e("A", "B"), e("A", "B"))))
                .containsExactly("A", "B");
    }
}
