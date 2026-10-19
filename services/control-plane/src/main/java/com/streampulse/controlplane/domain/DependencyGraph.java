/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.controlplane.domain;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * "Depends on" edges between nodes, with a Kahn topological sort. A cycle makes the order
 * impossible, which is how it is detected: the nodes the sort cannot place are on a cycle or
 * downstream of one.
 */
public final class DependencyGraph<N extends Comparable<N>> {

    private final Map<N, Set<N>> upstream = new LinkedHashMap<>();

    public DependencyGraph<N> addNode(N node) {
        upstream.computeIfAbsent(node, n -> new LinkedHashSet<>());
        return this;
    }

    /** {@code node} depends on {@code dependency}. */
    public DependencyGraph<N> addDependency(N node, N dependency) {
        addNode(node);
        addNode(dependency);
        upstream.get(node).add(dependency);
        return this;
    }

    /** Dependencies first: every node appears after everything it depends on. Throws if there is a cycle. */
    public List<N> topologicalOrder() {
        Map<N, Integer> unresolved = new HashMap<>();
        Map<N, List<N>> dependents = new HashMap<>();
        upstream.forEach((node, deps) -> {
            unresolved.put(node, deps.size());
            deps.forEach(dep ->
                    dependents.computeIfAbsent(dep, d -> new ArrayList<>()).add(node));
        });

        var ready = new ArrayDeque<N>();
        upstream.keySet().stream().filter(n -> unresolved.get(n) == 0).forEach(ready::add);

        var order = new ArrayList<N>(upstream.size());
        while (!ready.isEmpty()) {
            N next = ready.poll();
            order.add(next);
            for (N dependent : dependents.getOrDefault(next, List.of())) {
                if (unresolved.merge(dependent, -1, Integer::sum) == 0) {
                    ready.add(dependent);
                }
            }
        }

        if (order.size() != upstream.size()) {
            Collection<N> stuck = new java.util.TreeSet<>(upstream.keySet());
            stuck.removeAll(order);
            throw new RuleViolationException("dependency cycle involving: " + stuck);
        }
        return order;
    }

    public void assertAcyclic() {
        topologicalOrder();
    }
}
