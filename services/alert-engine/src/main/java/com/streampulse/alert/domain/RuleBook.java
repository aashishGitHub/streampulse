/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.alert.domain;

import com.streampulse.alert.domain.graph.CyclicDependencyException;
import com.streampulse.alert.domain.graph.DependencyGraph;
import com.streampulse.alert.domain.graph.Edge;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The current rules, in memory. Rebuilt from the compacted config topic at start-up.
 *
 * <p>Reads are on the evaluation hot path and take no lock: writers publish a new immutable
 * snapshot. A write replaces one tenant's rules and rebuilds that tenant's graph, which is how a
 * cyclic rule set is caught before it can be used.
 */
public final class RuleBook {

    public enum PutResult {
        APPLIED,
        /** The book already holds a newer version of this rule. */
        STALE
    }

    private record Versioned(AlertRule rule, long version) {}

    private record TenantRules(
            Map<String, Versioned> byId, Map<String, List<AlertRule>> byMetric, DependencyGraph graph) {

        static final TenantRules EMPTY = new TenantRules(Map.of(), Map.of(), DependencyGraph.EMPTY);

        static TenantRules of(Map<String, Versioned> byId) {
            Map<String, List<AlertRule>> byMetric = new HashMap<>();
            List<Edge> edges = new ArrayList<>();
            for (Versioned versioned : byId.values()) {
                AlertRule rule = versioned.rule();
                byMetric.computeIfAbsent(rule.metric(), m -> new ArrayList<>()).add(rule);
                for (String upstream : rule.upstream()) {
                    edges.add(new Edge(upstream, rule.node()));
                }
            }
            byMetric.replaceAll((metric, rules) -> List.copyOf(rules));
            return new TenantRules(Map.copyOf(byId), Map.copyOf(byMetric), DependencyGraph.of(edges));
        }
    }

    private record Snapshot(Map<String, TenantRules> tenants, Map<String, String> tenantOfRule) {}

    private final ReentrantLock writeLock = new ReentrantLock();
    private volatile Snapshot snapshot = new Snapshot(Map.of(), Map.of());

    /**
     * @throws CyclicDependencyException if the rule's upstream set closes a cycle; the book is unchanged
     */
    public PutResult put(AlertRule rule, long version) {
        writeLock.lock();
        try {
            Snapshot current = snapshot;
            String previousTenant = current.tenantOfRule().get(rule.ruleId());
            if (previousTenant != null) {
                Versioned existing =
                        current.tenants().get(previousTenant).byId().get(rule.ruleId());
                if (existing.version() > version) {
                    return PutResult.STALE;
                }
            }

            Map<String, TenantRules> tenants = new HashMap<>(current.tenants());
            Map<String, String> tenantOf = new HashMap<>(current.tenantOfRule());
            if (previousTenant != null && !previousTenant.equals(rule.tenantId())) {
                tenants.put(previousTenant, without(tenants.get(previousTenant), rule.ruleId()));
            }
            Map<String, Versioned> byId = new HashMap<>(
                    tenants.getOrDefault(rule.tenantId(), TenantRules.EMPTY).byId());
            byId.put(rule.ruleId(), new Versioned(rule, version));
            tenants.put(rule.tenantId(), TenantRules.of(byId));
            tenantOf.put(rule.ruleId(), rule.tenantId());

            snapshot = new Snapshot(Map.copyOf(tenants), Map.copyOf(tenantOf));
            return PutResult.APPLIED;
        } finally {
            writeLock.unlock();
        }
    }

    public Optional<AlertRule> remove(String ruleId) {
        writeLock.lock();
        try {
            Snapshot current = snapshot;
            String tenant = current.tenantOfRule().get(ruleId);
            if (tenant == null) {
                return Optional.empty();
            }
            AlertRule removed = current.tenants().get(tenant).byId().get(ruleId).rule();

            Map<String, TenantRules> tenants = new HashMap<>(current.tenants());
            Map<String, String> tenantOf = new HashMap<>(current.tenantOfRule());
            tenants.put(tenant, without(tenants.get(tenant), ruleId));
            tenantOf.remove(ruleId);
            snapshot = new Snapshot(Map.copyOf(tenants), Map.copyOf(tenantOf));
            return Optional.of(removed);
        } finally {
            writeLock.unlock();
        }
    }

    public List<AlertRule> rulesFor(Observation observation) {
        TenantRules tenant = snapshot.tenants().get(observation.tenantId());
        if (tenant == null) {
            return List.of();
        }
        return tenant.byMetric().getOrDefault(observation.metric(), List.of()).stream()
                .filter(rule -> rule.matches(observation))
                .toList();
    }

    public Optional<AlertRule> find(String ruleId) {
        Snapshot current = snapshot;
        String tenant = current.tenantOfRule().get(ruleId);
        return tenant == null
                ? Optional.empty()
                : Optional.of(current.tenants().get(tenant).byId().get(ruleId).rule());
    }

    public DependencyGraph graphFor(String tenantId) {
        TenantRules tenant = snapshot.tenants().get(tenantId);
        return tenant == null ? DependencyGraph.EMPTY : tenant.graph();
    }

    public Collection<AlertRule> all() {
        return snapshot.tenants().values().stream()
                .flatMap(t -> t.byId().values().stream())
                .map(Versioned::rule)
                .toList();
    }

    public int size() {
        return snapshot.tenantOfRule().size();
    }

    private static TenantRules without(TenantRules tenant, String ruleId) {
        Map<String, Versioned> byId = new HashMap<>(tenant.byId());
        byId.remove(ruleId);
        return TenantRules.of(byId);
    }
}
