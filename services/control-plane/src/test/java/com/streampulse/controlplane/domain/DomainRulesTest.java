/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.controlplane.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streampulse.platform.domain.TenantId;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class DomainRulesTest {

    private static final TenantId ACME = TenantId.of("acme");

    private static AlertRule rule(String id, Category category, Category... upstream) {
        return new AlertRule(
                id, ACME, "error_rate", Comparison.GT, 0.05, AlertSeverity.CRITICAL, category, Set.of(upstream), 0);
    }

    private static ServiceNode node(String id, String... deps) {
        return new ServiceNode(id, ACME, id, Category.CDN, Set.of(deps), 0);
    }

    @Nested
    @DisplayName("tenant")
    class TenantRules {

        @Test
        @DisplayName("the reserved id 'tree' would shadow the tree resource")
        void treeIsReserved() {
            assertThatThrownBy(() -> Tenant.draft("tree", "Tree", null, null))
                    .isInstanceOf(InvalidInputException.class)
                    .hasMessageContaining("reserved");
        }

        @Test
        @DisplayName("an id that breaks the platform tenant-id rule is invalid input, not a stack trace")
        void malformedIdIsInvalidInput() {
            assertThatThrownBy(() -> Tenant.draft("Has Spaces", "x", null, null))
                    .isInstanceOf(InvalidInputException.class);
        }

        @Test
        @DisplayName("blank name and negative quota are refused")
        void nameAndQuota() {
            assertThatThrownBy(() -> Tenant.draft("acme", " ", null, null)).isInstanceOf(InvalidInputException.class);
            assertThatThrownBy(() -> Tenant.draft("acme", "Acme", -1L, null)).isInstanceOf(InvalidInputException.class);
        }

        @Test
        @DisplayName("the snapshot is the whole entity, keyed by a kind-qualified id")
        void snapshotIsComplete() {
            var tenant = Tenant.draft("acme", "Acme", 100L, "root");

            assertThat(tenant.entityId()).isEqualTo("tenant/acme");
            assertThat(tenant.snapshot())
                    .containsEntry("entityKind", "TENANT")
                    .containsEntry("tenantId", "acme")
                    .containsEntry("displayName", "Acme")
                    .containsEntry("monthlyEventQuota", 100L)
                    .containsEntry("parentTenantId", "root")
                    .containsKey("version");
        }
    }

    @Nested
    @DisplayName("alert rule graph")
    class AlertRuleGraph {

        @Test
        @DisplayName("a rule that depends on its own category is a cycle")
        void selfCategoryCycle() {
            assertThatThrownBy(() ->
                            AlertRuleDependencies.assertAcyclic(rule("r1", Category.CDN, Category.CDN), List.of()))
                    .isInstanceOf(RuleViolationException.class);
        }

        @Test
        @DisplayName("a cycle is found ACROSS rules of the tenant, not only within one")
        void cycleAcrossRules() {
            var existing = List.of(rule("r1", Category.PLAYER, Category.PACKAGER));
            var candidate = rule("r2", Category.PACKAGER, Category.PLAYER);

            assertThatThrownBy(() -> AlertRuleDependencies.assertAcyclic(candidate, existing))
                    .isInstanceOf(RuleViolationException.class);
        }

        @Test
        @DisplayName("a chain CDN <- packager <- player is accepted")
        void chainIsAccepted() {
            var existing = List.of(rule("r1", Category.PACKAGER, Category.CDN));
            var candidate = rule("r2", Category.PLAYER, Category.PACKAGER);

            assertThatCode(() -> AlertRuleDependencies.assertAcyclic(candidate, existing))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("dependsOn without a category of its own is invalid")
        void dependsOnNeedsACategory() {
            assertThatThrownBy(() -> rule("r1", null, Category.CDN)).isInstanceOf(InvalidInputException.class);
        }

        @Test
        @DisplayName("comparison and severity default; a NaN threshold is refused")
        void defaultsAndThreshold() {
            var r = new AlertRule("r1", ACME, "m", null, 1.0, null, null, null, 0);

            assertThat(r.comparison()).isEqualTo(Comparison.GT);
            assertThat(r.severity()).isEqualTo(AlertSeverity.WARNING);
            assertThatThrownBy(() -> new AlertRule("r1", ACME, "m", null, Double.NaN, null, null, null, 0))
                    .isInstanceOf(InvalidInputException.class);
        }
    }

    @Nested
    @DisplayName("service node graph")
    class NodeGraph {

        @Test
        @DisplayName("an unknown dependency is a violation")
        void unknownDependency() {
            assertThatThrownBy(() -> ServiceNodeDependencies.validate(node("player", "ghost"), List.of()))
                    .isInstanceOf(RuleViolationException.class)
                    .hasMessageContaining("ghost");
        }

        @Test
        @DisplayName("a node depending on itself is a violation")
        void selfDependency() {
            assertThatThrownBy(() -> ServiceNodeDependencies.validate(node("a", "a"), List.of()))
                    .isInstanceOf(RuleViolationException.class);
        }

        @Test
        @DisplayName("re-pointing an upstream node at a downstream one closes a loop")
        void loopThroughExistingNodes() {
            var others = List.of(node("packager", "cdn"), node("player", "packager"));

            assertThatThrownBy(() -> ServiceNodeDependencies.validate(node("cdn", "player"), others))
                    .isInstanceOf(RuleViolationException.class);
        }

        @Test
        @DisplayName("a valid chain passes")
        void validChain() {
            var others = List.of(node("cdn"), node("packager", "cdn"));

            assertThatCode(() -> ServiceNodeDependencies.validate(node("player", "packager"), others))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("change event")
    class ChangeEvents {

        private final Instant at = Instant.parse("2026-10-12T10:00:00Z");

        @Test
        @DisplayName("a delete carries no snapshot: it is the tombstone")
        void deleteHasNoSnapshot() {
            var event =
                    ChangeEvent.of(UUID.randomUUID(), Operation.DELETED, Tenant.draft("acme", "Acme", null, null), at);

            assertThat(event.snapshot()).isNull();
            assertThat(event.isTombstone()).isTrue();
            assertThat(event.entityId()).isEqualTo("tenant/acme");
        }

        @Test
        @DisplayName("a create carries the full snapshot")
        void createHasSnapshot() {
            var event =
                    ChangeEvent.of(UUID.randomUUID(), Operation.CREATED, Tenant.draft("acme", "Acme", null, null), at);

            assertThat(event.snapshot()).containsEntry("displayName", "Acme");
            assertThat(event.isTombstone()).isFalse();
        }

        @Test
        @DisplayName("a non-delete without a snapshot, or a delete with one, cannot be built")
        void invariants() {
            var id = UUID.randomUUID();
            assertThatThrownBy(() ->
                            new ChangeEvent(id, ConfigKind.TENANT, "tenant/a", "a", Operation.UPDATED, null, 1, at))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new ChangeEvent(
                            id,
                            ConfigKind.TENANT,
                            "tenant/a",
                            "a",
                            Operation.DELETED,
                            java.util.Map.of("k", "v"),
                            1,
                            at))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("keys never collide across kinds even when ids are equal")
        void keysAreKindQualified() {
            var title = new Title("x1", ACME, "T", TitleKind.VOD, 0);
            var rule = rule("x1", null);

            assertThat(title.entityId()).isEqualTo("title/acme/x1");
            assertThat(rule.entityId()).isEqualTo("x1");
            assertThat(title.entityId()).isNotEqualTo(rule.entityId());
        }
    }
}
