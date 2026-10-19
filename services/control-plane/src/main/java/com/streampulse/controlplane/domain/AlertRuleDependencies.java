/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.controlplane.domain;

import java.util.Collection;

/**
 * The category graph of a tenant: each rule contributes {@code category -> dependsOn}. Rejecting a
 * cycle at save time is what keeps a later breadth-first root-cause walk terminating.
 */
public final class AlertRuleDependencies {

    private AlertRuleDependencies() {}

    /** @param others the tenant's other rules, EXCLUDING the candidate's previous version on update */
    public static void assertAcyclic(AlertRule candidate, Collection<AlertRule> others) {
        var graph = new DependencyGraph<Category>();
        others.forEach(rule -> addEdges(graph, rule));
        addEdges(graph, candidate);
        graph.assertAcyclic();
    }

    private static void addEdges(DependencyGraph<Category> graph, AlertRule rule) {
        if (rule.category() == null) {
            return;
        }
        graph.addNode(rule.category());
        rule.dependsOn().forEach(upstream -> graph.addDependency(rule.category(), upstream));
    }
}
