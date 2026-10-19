/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.alert.domain.graph;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Topological sort and cycle detection with Kahn's algorithm. Imports only {@code java.*}, so the
 * control plane can call it at rule-save time once the three files in this package live in a
 * shared library (see specs/06-alert-engine/design.md, "Reuse by the control plane").
 *
 * <p>Kahn: repeatedly remove a node with no remaining incoming edge. If every node is removed the
 * graph is acyclic and the removal order is a topological order. Whatever is left is on a cycle
 * or downstream of one. Taking the smallest ready node first makes the order deterministic.
 *
 * <h2>API</h2>
 *
 * <ul>
 *   <li>{@link #topologicalOrder(Collection)}: order, or {@link CyclicDependencyException}.
 *   <li>{@link #validate(Collection)}: same check, no result.
 *   <li>{@link #validate(Collection, String, Collection)}: "would adding this rule break it?"
 * </ul>
 */
public final class RuleGraphValidator {

    private RuleGraphValidator() {}

    public static void validate(Collection<Edge> edges) {
        topologicalOrder(edges);
    }

    /**
     * Checks that a new node with the given upstream dependencies can join the existing edges.
     *
     * @throws CyclicDependencyException if it cannot; the exception names the cycle
     */
    public static void validate(Collection<Edge> existing, String node, Collection<String> upstreams) {
        List<Edge> all = new ArrayList<>(existing);
        for (String upstream : upstreams) {
            all.add(new Edge(upstream, node));
        }
        topologicalOrder(all);
    }

    public static List<String> topologicalOrder(Collection<Edge> edges) {
        return topologicalOrder(List.of(), edges);
    }

    /** @param nodes extra nodes that may have no edge at all; edge endpoints are added implicitly */
    public static List<String> topologicalOrder(Collection<String> nodes, Collection<Edge> edges) {
        Map<String, Set<String>> downstream = new TreeMap<>();
        Map<String, Integer> inDegree = new TreeMap<>();
        for (String node : nodes) {
            register(node, downstream, inDegree);
        }
        for (Edge edge : edges) {
            register(edge.from(), downstream, inDegree);
            register(edge.to(), downstream, inDegree);
            if (downstream.get(edge.from()).add(edge.to())) {
                inDegree.merge(edge.to(), 1, Integer::sum);
            }
        }

        PriorityQueue<String> ready = new PriorityQueue<>();
        inDegree.forEach((node, degree) -> {
            if (degree == 0) {
                ready.add(node);
            }
        });

        List<String> order = new ArrayList<>(inDegree.size());
        while (!ready.isEmpty()) {
            String node = ready.poll();
            order.add(node);
            for (String next : downstream.get(node)) {
                if (inDegree.merge(next, -1, Integer::sum) == 0) {
                    ready.add(next);
                }
            }
        }

        if (order.size() < inDegree.size()) {
            Set<String> remaining = new TreeSet<>(inDegree.keySet());
            remaining.removeAll(order);
            throw new CyclicDependencyException(findCycle(remaining, downstream));
        }
        return Collections.unmodifiableList(order);
    }

    private static void register(String node, Map<String, Set<String>> downstream, Map<String, Integer> inDegree) {
        downstream.computeIfAbsent(node, n -> new TreeSet<>());
        inDegree.putIfAbsent(node, 0);
    }

    /**
     * Every node Kahn could not remove still has an unremoved predecessor, so walking backwards through
     * predecessors must eventually repeat a node, and the repeated stretch is a cycle.
     */
    private static List<String> findCycle(Set<String> remaining, Map<String, Set<String>> downstream) {
        Map<String, TreeSet<String>> predecessors = new TreeMap<>();
        for (String node : remaining) {
            for (String next : downstream.get(node)) {
                if (remaining.contains(next)) {
                    predecessors.computeIfAbsent(next, n -> new TreeSet<>()).add(node);
                }
            }
        }

        Map<String, Integer> walkIndex = new LinkedHashMap<>();
        String current = remaining.iterator().next();
        while (!walkIndex.containsKey(current)) {
            walkIndex.put(current, walkIndex.size());
            current = predecessors.get(current).first();
        }

        List<String> backwards = new ArrayList<>(walkIndex.keySet());
        List<String> cycle = new ArrayList<>(backwards.subList(walkIndex.get(current), backwards.size()));
        Collections.reverse(cycle);
        Collections.rotate(cycle, -cycle.indexOf(Collections.min(cycle)));
        cycle.add(cycle.get(0));
        return cycle;
    }
}
