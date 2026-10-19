/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.alert.domain.graph;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * An immutable acyclic service graph with breadth-first search in both directions.
 *
 * <p>Construction runs Kahn's algorithm, so a graph that exists is acyclic and every node has a
 * topological rank. The searches still keep a visited set: termination must not depend on a
 * validation that happened elsewhere.
 */
public final class DependencyGraph {

    public static final DependencyGraph EMPTY = new DependencyGraph(List.of());

    private final Map<String, Set<String>> downstream = new HashMap<>();
    private final Map<String, Set<String>> upstream = new HashMap<>();
    private final Map<String, Integer> rank = new HashMap<>();

    private DependencyGraph(Collection<Edge> edges) {
        for (Edge edge : edges) {
            downstream.computeIfAbsent(edge.from(), n -> new TreeSet<>()).add(edge.to());
            upstream.computeIfAbsent(edge.to(), n -> new TreeSet<>()).add(edge.from());
        }
        List<String> order = RuleGraphValidator.topologicalOrder(edges);
        for (int i = 0; i < order.size(); i++) {
            rank.put(order.get(i), i);
        }
    }

    /** @throws CyclicDependencyException if the edges contain a cycle */
    public static DependencyGraph of(Collection<Edge> edges) {
        return edges.isEmpty() ? EMPTY : new DependencyGraph(edges);
    }

    /**
     * Ancestors grouped by distance from {@code node}: first the direct upstreams, then theirs.
     * A node appears once, at its shortest distance. Within a level, names are sorted.
     */
    public List<List<String>> upstreamLevels(String node) {
        return levels(node, upstream);
    }

    /** Descendants in breadth-first order, nearest first, each once. */
    public List<String> downstreamBreadthFirst(String node) {
        return levels(node, downstream).stream().flatMap(List::stream).toList();
    }

    /** Position in a topological order: ancestors have a smaller rank. Unknown nodes rank first. */
    public int rank(String node) {
        return rank.getOrDefault(node, -1);
    }

    private static List<List<String>> levels(String start, Map<String, Set<String>> adjacency) {
        Set<String> visited = new HashSet<>();
        visited.add(start);
        List<List<String>> levels = new ArrayList<>();
        Deque<String> frontier = new ArrayDeque<>(List.of(start));
        while (!frontier.isEmpty()) {
            Set<String> next = new TreeSet<>();
            for (String node : frontier) {
                for (String neighbour : adjacency.getOrDefault(node, Set.of())) {
                    if (visited.add(neighbour)) {
                        next.add(neighbour);
                    }
                }
            }
            if (!next.isEmpty()) {
                levels.add(List.copyOf(next));
            }
            frontier = new ArrayDeque<>(next);
        }
        return levels;
    }
}
