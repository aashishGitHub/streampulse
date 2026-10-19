/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.alert.domain;

import com.streampulse.alert.domain.AlertStateMachine.Transition;
import com.streampulse.alert.domain.evaluator.Evaluators;
import com.streampulse.alert.domain.graph.DependencyGraph;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Evaluates observations against rules and keeps the active alerts. Every public method takes one
 * lock, so the cascade of suppression and release is atomic with the change that causes it.
 *
 * <h2>Idempotence</h2>
 *
 * The state per (rule, series) is a cursor: the last observation accepted. An observation whose
 * window start is not later than the cursor's is ignored before any evaluator runs. The cursor
 * advances in the same locked section as the alert transition it caused, so a duplicate can never
 * observe a half-applied state.
 *
 * <h2>Invariant</h2>
 *
 * After every call, no FIRING alert has a FIRING alert on a node upstream of it in the same tenant;
 * the nearest such alert is what a SUPPRESSED alert names in {@code suppressedBy}.
 *
 * <p>Delivery is not this class's job: it returns the alerts that changed, in order.
 */
public final class AlertEngine {

    private static final Comparator<Alert> OLDEST_FIRST =
            Comparator.comparing(Alert::firedAt).thenComparing(Alert::alertId);

    private final RuleBook rules;
    private final Evaluators evaluators;
    private final Clock clock;
    private final AlertMetrics metrics;

    private final ReentrantLock lock = new ReentrantLock();
    private final Map<AlertKey, Observation> cursors = new HashMap<>();
    private final Map<AlertKey, Alert> active = new HashMap<>();

    public AlertEngine(RuleBook rules, Evaluators evaluators, Clock clock, AlertMetrics metrics) {
        this.rules = rules;
        this.evaluators = evaluators;
        this.clock = clock;
        this.metrics = metrics;
    }

    public List<Alert> evaluate(Observation observation) {
        lock.lock();
        try {
            List<Alert> changes = new ArrayList<>();
            for (AlertRule rule : rules.rulesFor(observation)) {
                AlertKey key = new AlertKey(rule.ruleId(), observation.series());
                Observation last = cursors.get(key);
                if (last != null && !observation.windowStart().isAfter(last.windowStart())) {
                    metrics.evaluated(
                            rule.condition().kind(),
                            observation.windowStart().equals(last.windowStart())
                                    ? EvaluationOutcome.DUPLICATE
                                    : EvaluationOutcome.STALE);
                    continue;
                }
                Verdict verdict = evaluators.onObservation(rule.condition(), Optional.ofNullable(last), observation);
                cursors.put(key, observation);
                metrics.evaluated(rule.condition().kind(), EvaluationOutcome.of(verdict));
                apply(rule, key, verdict, observation.windowStart(), observation.value(), changes);
            }
            return List.copyOf(changes);
        } finally {
            lock.unlock();
        }
    }

    /** Time-driven evaluation, for conditions that depend on silence. */
    public List<Alert> sweep() {
        lock.lock();
        try {
            Instant now = clock.instant();
            List<Alert> changes = new ArrayList<>();
            List<Map.Entry<AlertKey, Observation>> entries = cursors.entrySet().stream()
                    .sorted(Comparator.comparing((Map.Entry<AlertKey, Observation> e) ->
                                    e.getKey().ruleId())
                            .thenComparing(e -> e.getKey().series().label()))
                    .toList();
            for (Map.Entry<AlertKey, Observation> entry : entries) {
                Optional<AlertRule> rule = rules.find(entry.getKey().ruleId());
                if (rule.isEmpty() || !(rule.get().condition() instanceof Condition.Absence)) {
                    continue;
                }
                Observation last = entry.getValue();
                Verdict verdict = evaluators.onTick(rule.get().condition(), last, now);
                metrics.evaluated(rule.get().condition().kind(), EvaluationOutcome.of(verdict));
                apply(rule.get(), entry.getKey(), verdict, last.windowEnd(), last.value(), changes);
            }
            return List.copyOf(changes);
        } finally {
            lock.unlock();
        }
    }

    /** The rule is gone: forget its cursors and resolve its alerts, downstream ones first. */
    public List<Alert> retire(AlertRule rule) {
        lock.lock();
        try {
            cursors.keySet().removeIf(key -> key.ruleId().equals(rule.ruleId()));
            DependencyGraph graph = rules.graphFor(rule.tenantId());
            List<AlertKey> mine = active.values().stream()
                    .filter(a -> a.key().ruleId().equals(rule.ruleId()))
                    .sorted(Comparator.comparingInt((Alert a) -> graph.rank(a.node()))
                            .reversed()
                            .thenComparing(OLDEST_FIRST))
                    .map(Alert::key)
                    .toList();
            List<Alert> changes = new ArrayList<>();
            for (AlertKey key : mine) {
                Alert current = active.get(key);
                if (current != null) {
                    resolve(current, changes);
                }
            }
            return List.copyOf(changes);
        } finally {
            lock.unlock();
        }
    }

    /** FIRING and SUPPRESSED alerts of a tenant, oldest first. */
    public List<Alert> activeAlerts(String tenantId) {
        lock.lock();
        try {
            return active.values().stream()
                    .filter(a -> a.tenantId().equals(tenantId))
                    .sorted(OLDEST_FIRST)
                    .toList();
        } finally {
            lock.unlock();
        }
    }

    private void apply(
            AlertRule rule, AlertKey key, Verdict verdict, Instant windowStart, double value, List<Alert> changes) {
        Alert current = active.get(key);
        Optional<Alert> root = verdict == Verdict.BREACHING && current == null
                ? findRoot(rule.tenantId(), rule.node())
                : Optional.empty();

        switch (AlertStateMachine.next(Optional.ofNullable(current), verdict, root)) {
            case Transition.None none -> {}
            case Transition.Open open -> open(rule, key, windowStart, value, open.rootCause(), changes);
            case Transition.Resolve resolve -> resolve(current, changes);
        }
    }

    private void open(
            AlertRule rule,
            AlertKey key,
            Instant windowStart,
            double value,
            Optional<Alert> rootCause,
            List<Alert> changes) {
        Instant now = clock.instant();
        Alert firing = new Alert(
                key,
                key.incidentId(windowStart),
                AlertState.FIRING,
                rule.severity(),
                rule.node(),
                rule.name(),
                rule.name() + " [" + key.series().label() + "]: "
                        + rule.condition().describe() + ", observed " + value,
                windowStart,
                now,
                now,
                null,
                null,
                value,
                rule.condition() instanceof Condition.Threshold t ? Double.valueOf(t.limit()) : null);

        Alert opened =
                rootCause.map(root -> firing.suppressed(root.alertId(), now)).orElse(firing);
        active.put(key, opened);
        changes.add(opened);

        if (opened.state() == AlertState.SUPPRESSED) {
            metrics.suppressed(opened.tenantId(), opened.severity());
        } else {
            metrics.fired(opened.tenantId(), opened.severity());
            suppressDownstream(opened, changes);
        }
    }

    private void resolve(Alert current, List<Alert> changes) {
        Alert resolved = current.resolved(clock.instant());
        active.remove(current.key());
        changes.add(resolved);
        metrics.resolved(resolved.tenantId(), resolved.severity());
        if (current.state() == AlertState.FIRING) {
            release(current, changes);
        }
    }

    /**
     * Breadth-first from the failing node, over reverse edges: the first level that holds a firing
     * alert is the nearest cause. Level by level, not first-found, so a tie is broken by age and
     * id rather than by hash order.
     */
    private Optional<Alert> findRoot(String tenantId, String node) {
        for (List<String> level : rules.graphFor(tenantId).upstreamLevels(node)) {
            Optional<Alert> nearest = active.values().stream()
                    .filter(a ->
                            a.tenantId().equals(tenantId) && a.state() == AlertState.FIRING && level.contains(a.node()))
                    .min(OLDEST_FIRST);
            if (nearest.isPresent()) {
                return nearest;
            }
        }
        return Optional.empty();
    }

    /**
     * Breadth-first from the new root over forward edges. A firing alert below it is demoted; an
     * alert already suppressed by something that has just been demoted is re-attributed to this
     * root, so {@code suppressedBy} always names a firing alert.
     */
    private void suppressDownstream(Alert root, List<Alert> changes) {
        Instant now = clock.instant();
        Set<String> firing = new HashSet<>();
        for (Alert a : active.values()) {
            if (a.tenantId().equals(root.tenantId()) && a.state() == AlertState.FIRING) {
                firing.add(a.alertId());
            }
        }

        for (String node : rules.graphFor(root.tenantId()).downstreamBreadthFirst(root.node())) {
            List<Alert> onNode = active.values().stream()
                    .filter(a ->
                            a.tenantId().equals(root.tenantId()) && a.node().equals(node))
                    .sorted(OLDEST_FIRST)
                    .toList();
            for (Alert alert : onNode) {
                boolean demote = alert.state() == AlertState.FIRING;
                boolean orphaned = alert.state() == AlertState.SUPPRESSED && !firing.contains(alert.suppressedBy());
                if (demote || orphaned) {
                    firing.remove(alert.alertId());
                    Alert suppressed = alert.suppressed(root.alertId(), now);
                    active.put(alert.key(), suppressed);
                    changes.add(suppressed);
                    if (demote) {
                        metrics.suppressed(suppressed.tenantId(), suppressed.severity());
                    }
                }
            }
        }
    }

    /**
     * Everything the resolved root was suppressing is looked at again, ancestors first. Handling a
     * node before its ancestor would promote both and leave a firing alert under a firing one.
     */
    private void release(Alert resolvedRoot, List<Alert> changes) {
        Instant now = clock.instant();
        String tenantId = resolvedRoot.tenantId();
        DependencyGraph graph = rules.graphFor(tenantId);
        List<Alert> candidates = active.values().stream()
                .filter(a -> a.tenantId().equals(tenantId)
                        && a.state() == AlertState.SUPPRESSED
                        && a.suppressedBy().equals(resolvedRoot.alertId()))
                .sorted(Comparator.comparingInt((Alert a) -> graph.rank(a.node()))
                        .thenComparing(OLDEST_FIRST))
                .toList();

        for (Alert candidate : candidates) {
            Optional<Alert> newRoot = findRoot(tenantId, candidate.node());
            Alert updated =
                    newRoot.isPresent() ? candidate.suppressed(newRoot.get().alertId(), now) : candidate.firing(now);
            active.put(candidate.key(), updated);
            changes.add(updated);
            if (updated.state() == AlertState.FIRING) {
                metrics.fired(updated.tenantId(), updated.severity());
            }
        }
    }
}
