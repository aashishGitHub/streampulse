/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.controlplane.domain;

import java.util.Collection;

public final class ServiceNodeDependencies {

    private ServiceNodeDependencies() {}

    /**
     * @param sameTenantOthers the tenant's other nodes, EXCLUDING the candidate's previous version.
     *     A dependency that is not among them is unknown, or belongs to another tenant.
     */
    public static void validate(ServiceNode candidate, Collection<ServiceNode> sameTenantOthers) {
        var graph = new DependencyGraph<String>();
        sameTenantOthers.forEach(node -> {
            graph.addNode(node.nodeId());
            node.dependsOn().forEach(dep -> graph.addDependency(node.nodeId(), dep));
        });
        graph.addNode(candidate.nodeId());

        for (String dependency : candidate.dependsOn()) {
            boolean known = dependency.equals(candidate.nodeId())
                    || sameTenantOthers.stream().anyMatch(n -> n.nodeId().equals(dependency));
            if (!known) {
                throw new RuleViolationException(
                        "dependency '" + dependency + "' is unknown or belongs to another tenant");
            }
            graph.addDependency(candidate.nodeId(), dependency);
        }
        graph.assertAcyclic();
    }
}
