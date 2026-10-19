/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.controlplane.support;

import com.streampulse.controlplane.application.ChangeRecorder;
import com.streampulse.controlplane.domain.AlertRule;
import com.streampulse.controlplane.domain.AlertRuleRepository;
import com.streampulse.controlplane.domain.ChangeEvent;
import com.streampulse.controlplane.domain.ConfigChangeOutbox;
import com.streampulse.controlplane.domain.ServiceNode;
import com.streampulse.controlplane.domain.ServiceNodeRepository;
import com.streampulse.controlplane.domain.Tenant;
import com.streampulse.controlplane.domain.TenantRepository;
import com.streampulse.controlplane.domain.Title;
import com.streampulse.controlplane.domain.TitleRepository;
import com.streampulse.platform.domain.TenantId;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** In-memory ports: the application services are unit-tested with no database and no Spring. */
public final class Fakes {

    public static final Instant NOW = Instant.parse("2026-10-12T10:00:00Z");
    public static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private Fakes() {}

    public static final class RecordingOutbox implements ConfigChangeOutbox {
        public final List<ChangeEvent> events = new ArrayList<>();

        @Override
        public void append(ChangeEvent event) {
            events.add(event);
        }
    }

    public static ChangeRecorder recorder(RecordingOutbox outbox) {
        return new ChangeRecorder(outbox, CLOCK, UUID::randomUUID);
    }

    public static final class InMemoryTenants implements TenantRepository {
        public final Map<TenantId, Tenant> rows = new LinkedHashMap<>();

        @Override
        public Optional<Tenant> find(TenantId id) {
            return Optional.ofNullable(rows.get(id));
        }

        @Override
        public boolean exists(TenantId id) {
            return rows.containsKey(id);
        }

        @Override
        public List<Tenant> findAll() {
            return List.copyOf(rows.values());
        }

        @Override
        public Tenant insert(Tenant tenant) {
            rows.put(tenant.tenantId(), tenant);
            return tenant;
        }

        @Override
        public Tenant update(Tenant tenant) {
            Tenant bumped = new Tenant(
                    tenant.tenantId(),
                    tenant.displayName(),
                    tenant.monthlyEventQuota(),
                    tenant.parentTenantId(),
                    tenant.version() + 1);
            rows.put(bumped.tenantId(), bumped);
            return bumped;
        }

        @Override
        public void delete(TenantId id) {
            rows.remove(id);
        }
    }

    public static final class InMemoryTitles implements TitleRepository {
        public final Map<String, Title> rows = new LinkedHashMap<>();

        @Override
        public Optional<Title> find(String titleId) {
            return Optional.ofNullable(rows.get(titleId));
        }

        @Override
        public List<Title> findAll() {
            return List.copyOf(rows.values());
        }

        @Override
        public List<Title> findByTenant(TenantId tenantId) {
            return rows.values().stream()
                    .filter(t -> t.tenantId().equals(tenantId))
                    .toList();
        }

        @Override
        public Title insert(Title title) {
            rows.put(title.titleId(), title);
            return title;
        }

        @Override
        public Title update(Title title) {
            var bumped = new Title(title.titleId(), title.tenantId(), title.name(), title.kind(), title.version() + 1);
            rows.put(bumped.titleId(), bumped);
            return bumped;
        }

        @Override
        public void delete(String titleId) {
            rows.remove(titleId);
        }
    }

    public static final class InMemoryRules implements AlertRuleRepository {
        public final Map<String, AlertRule> rows = new LinkedHashMap<>();

        @Override
        public Optional<AlertRule> find(String ruleId) {
            return Optional.ofNullable(rows.get(ruleId));
        }

        @Override
        public List<AlertRule> findAll() {
            return List.copyOf(rows.values());
        }

        @Override
        public List<AlertRule> findByTenant(TenantId tenantId) {
            return rows.values().stream()
                    .filter(r -> r.tenantId().equals(tenantId))
                    .toList();
        }

        @Override
        public AlertRule insert(AlertRule rule) {
            rows.put(rule.ruleId(), rule);
            return rule;
        }

        @Override
        public AlertRule update(AlertRule rule) {
            var bumped = new AlertRule(
                    rule.ruleId(),
                    rule.tenantId(),
                    rule.metric(),
                    rule.comparison(),
                    rule.threshold(),
                    rule.severity(),
                    rule.category(),
                    rule.dependsOn(),
                    rule.version() + 1);
            rows.put(bumped.ruleId(), bumped);
            return bumped;
        }

        @Override
        public void delete(String ruleId) {
            rows.remove(ruleId);
        }
    }

    public static final class InMemoryNodes implements ServiceNodeRepository {
        public final Map<String, ServiceNode> rows = new LinkedHashMap<>();

        @Override
        public Optional<ServiceNode> find(String nodeId) {
            return Optional.ofNullable(rows.get(nodeId));
        }

        @Override
        public List<ServiceNode> findAll() {
            return List.copyOf(rows.values());
        }

        @Override
        public List<ServiceNode> findByTenant(TenantId tenantId) {
            return rows.values().stream()
                    .filter(n -> n.tenantId().equals(tenantId))
                    .toList();
        }

        @Override
        public ServiceNode insert(ServiceNode node) {
            rows.put(node.nodeId(), node);
            return node;
        }

        @Override
        public ServiceNode update(ServiceNode node) {
            var bumped = new ServiceNode(
                    node.nodeId(), node.tenantId(), node.name(), node.category(), node.dependsOn(), node.version() + 1);
            rows.put(bumped.nodeId(), bumped);
            return bumped;
        }

        @Override
        public void delete(String nodeId) {
            rows.remove(nodeId);
        }
    }
}
