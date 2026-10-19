/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.alert.domain;

import static com.streampulse.alert.support.Fixtures.T0;
import static com.streampulse.alert.support.Fixtures.TENANT;
import static com.streampulse.alert.support.Fixtures.obs;
import static com.streampulse.alert.support.Fixtures.rule;
import static org.assertj.core.api.Assertions.assertThat;

import com.streampulse.alert.domain.evaluator.Evaluators;
import com.streampulse.alert.support.MutableClock;
import com.streampulse.alert.support.RecordingMetrics;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Root-cause suppression over small graphs. Every node has one rule on its own metric (the metric
 * is named after the node), so a test can make exactly one node breach at a time.
 */
class SuppressionTest {

    private final RuleBook rules = new RuleBook();
    private final MutableClock clock = new MutableClock(T0);
    private final AlertEngine engine =
            new AlertEngine(rules, new Evaluators(Duration.ofSeconds(5)), clock, new RecordingMetrics());
    private int window;

    private void node(String name, String... upstream) {
        tenantNode(TENANT, name, upstream);
    }

    private void tenantNode(String tenant, String name, String... upstream) {
        String id = tenant.equals(TENANT) ? "rule-" + name : "rule-" + tenant + "-" + name;
        rules.put(rule(id, tenant, "M_" + name, new Condition.Threshold(Comparison.GT, 0.5), name, upstream), 1);
    }

    private List<Alert> breach(String name) {
        return breach(TENANT, name);
    }

    private List<Alert> breach(String tenant, String name) {
        clock.advance(Duration.ofSeconds(1));
        return engine.evaluate(obs(tenant, "M_" + name, ++window, 1.0));
    }

    private List<Alert> clear(String name) {
        clock.advance(Duration.ofSeconds(1));
        return engine.evaluate(obs(TENANT, "M_" + name, ++window, 0.0));
    }

    private Alert alertOn(String name) {
        return engine.activeAlerts(TENANT).stream()
                .filter(a -> a.node().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no active alert on " + name));
    }

    private Map<String, String> states() {
        return engine.activeAlerts(TENANT).stream()
                .collect(Collectors.toMap(
                        Alert::node,
                        a -> a.state() + (a.suppressedBy() == null ? "" : "<-" + nodeOf(a.suppressedBy()))));
    }

    private String nodeOf(String alertId) {
        return engine.activeAlerts(TENANT).stream()
                .filter(a -> a.alertId().equals(alertId))
                .findFirst()
                .map(Alert::node)
                .orElse("?" + alertId);
    }

    @Nested
    @DisplayName("chain: CDN -> PACKAGER -> PLAYER")
    class Chain {

        Chain() {
            node("CDN");
            node("PACKAGER", "CDN");
            node("PLAYER", "PACKAGER");
        }

        @Test
        @DisplayName("a downstream breach while the root fires is born SUPPRESSED, naming the root")
        void downstreamBornSuppressed() {
            Alert cdn = breach("CDN").get(0);

            Alert player = breach("PLAYER").get(0);

            assertThat(player.state()).isEqualTo(AlertState.SUPPRESSED);
            assertThat(player.suppressedBy()).isEqualTo(cdn.alertId());
        }

        @Test
        @DisplayName("the nearest FIRING ancestor is named, skipping a suppressed middle node")
        void chainNamesTheNearestRoot() {
            Alert cdn = breach("CDN").get(0);
            Alert pkg = breach("PACKAGER").get(0);
            assertThat(pkg.state()).isEqualTo(AlertState.SUPPRESSED);

            Alert player = breach("PLAYER").get(0);

            assertThat(player.suppressedBy())
                    .as("PACKAGER is itself suppressed, so the cause is CDN, two levels up")
                    .isEqualTo(cdn.alertId());
        }

        @Test
        @DisplayName("a root that fires later suppresses the already-firing descendants")
        void rootFiringSuppressesFiringDescendants() {
            breach("PLAYER");
            breach("PACKAGER");
            assertThat(states()).containsEntry("PLAYER", "SUPPRESSED<-PACKAGER").containsEntry("PACKAGER", "FIRING");

            var changes = breach("CDN");

            assertThat(changes).extracting(Alert::node).containsExactly("CDN", "PACKAGER", "PLAYER");
            assertThat(states())
                    .containsEntry("CDN", "FIRING")
                    .containsEntry("PACKAGER", "SUPPRESSED<-CDN")
                    .containsEntry("PLAYER", "SUPPRESSED<-CDN");
        }

        @Test
        @DisplayName("an upstream that is not firing suppresses nothing")
        void quietUpstreamDoesNotSuppress() {
            assertThat(breach("PLAYER").get(0).state()).isEqualTo(AlertState.FIRING);
        }

        @Test
        @DisplayName("a downstream failure never suppresses its upstream")
        void downstreamDoesNotSuppressUpstream() {
            breach("PLAYER");

            assertThat(breach("CDN").get(0).state()).isEqualTo(AlertState.FIRING);
        }

        @Test
        @DisplayName("when the root resolves, still-true suppressed alerts become FIRING with the same ids")
        void releaseOnRootResolve() {
            Alert cdn = breach("CDN").get(0);
            Alert pkg = breach("PACKAGER").get(0);
            Alert player = breach("PLAYER").get(0);

            var changes = clear("CDN");

            assertThat(changes)
                    .extracting(Alert::state)
                    .containsExactly(AlertState.RESOLVED, AlertState.FIRING, AlertState.SUPPRESSED);
            assertThat(changes.get(0).alertId()).isEqualTo(cdn.alertId());
            assertThat(states()).containsEntry("PACKAGER", "FIRING").containsEntry("PLAYER", "SUPPRESSED<-PACKAGER");
            assertThat(alertOn("PACKAGER").alertId()).isEqualTo(pkg.alertId());
            assertThat(alertOn("PLAYER").alertId()).isEqualTo(player.alertId());
            assertThat(alertOn("PACKAGER").firedAt())
                    .as("the incident began at the first breach")
                    .isEqualTo(pkg.firedAt());
        }

        @Test
        @DisplayName("a suppressed alert whose own condition clears is resolved, and its root is undisturbed")
        void suppressedResolvesWhenItsConditionClears() {
            Alert cdn = breach("CDN").get(0);
            breach("PACKAGER");

            var changes = clear("PACKAGER");

            assertThat(changes).extracting(Alert::state).containsExactly(AlertState.RESOLVED);
            assertThat(states()).containsOnlyKeys("CDN").containsEntry("CDN", "FIRING");
            assertThat(alertOn("CDN").alertId()).isEqualTo(cdn.alertId());
        }

        @Test
        @DisplayName(
                "if the suppressed alert's condition cleared meanwhile, nothing is released when the root resolves")
        void nothingToReleaseWhenAlreadyCleared() {
            breach("CDN");
            breach("PACKAGER");
            clear("PACKAGER");

            assertThat(clear("CDN")).extracting(Alert::state).containsExactly(AlertState.RESOLVED);
            assertThat(engine.activeAlerts(TENANT)).isEmpty();
        }

        @Test
        @DisplayName("a repeated downstream breach while suppressed says nothing new")
        void suppressedStaysQuiet() {
            breach("CDN");
            breach("PLAYER");

            assertThat(breach("PLAYER")).isEmpty();
        }
    }

    @Nested
    @DisplayName("long chain: N0 -> N1 -> ... -> N8")
    class LongChain {

        LongChain() {
            node("N0");
            for (int i = 1; i <= 8; i++) {
                node("N" + i, "N" + (i - 1));
            }
        }

        @Test
        @DisplayName("the root eight levels up is found")
        void rootFoundAcrossManyLevels() {
            Alert root = breach("N0").get(0);

            Alert leaf = breach("N8").get(0);

            assertThat(leaf.state()).isEqualTo(AlertState.SUPPRESSED);
            assertThat(leaf.suppressedBy()).isEqualTo(root.alertId());
        }

        @Test
        @DisplayName("a firing leaf is suppressed when the distant root fires, and released when it resolves")
        void distantRootSuppressesAndReleases() {
            breach("N8");

            breach("N0");
            assertThat(states()).containsEntry("N8", "SUPPRESSED<-N0");

            clear("N0");
            assertThat(states()).containsOnlyKeys("N8").containsEntry("N8", "FIRING");
        }

        @Test
        @DisplayName("with a nearer firing ancestor in the way, the nearer one is named")
        void nearestOfTwoRoots() {
            breach("N6");
            breach("N2");
            // N6 is below N2: it is suppressed by N2, so for N8 only N2 is a FIRING ancestor
            Alert leaf = breach("N8").get(0);

            assertThat(nodeOf(leaf.suppressedBy())).isEqualTo("N2");
        }
    }

    @Nested
    @DisplayName("diamond: A -> B, A -> C, B -> D, C -> D")
    class Diamond {

        Diamond() {
            node("A");
            node("B", "A");
            node("C", "A");
            node("D", "B", "C");
        }

        @Test
        @DisplayName("with two firing parents, the earlier-fired one is named")
        void tieBrokenByAge() {
            Alert b = breach("B").get(0);
            breach("C");

            Alert d = breach("D").get(0);

            assertThat(d.suppressedBy()).isEqualTo(b.alertId());
        }

        @Test
        @DisplayName(
                "when the named parent resolves but the other still fires, the bottom is re-attributed, not released")
        void reattributedToTheOtherParent() {
            breach("B");
            Alert c = breach("C").get(0);
            breach("D");

            var changes = clear("B");

            assertThat(changes).extracting(Alert::state).containsExactly(AlertState.RESOLVED, AlertState.SUPPRESSED);
            assertThat(changes.get(1).suppressedBy()).isEqualTo(c.alertId());
        }

        @Test
        @DisplayName("when both parents have resolved, the bottom is released")
        void releasedWhenBothResolved() {
            breach("B");
            breach("C");
            breach("D");

            clear("B");
            clear("C");

            assertThat(states()).containsOnlyKeys("D").containsEntry("D", "FIRING");
        }

        @Test
        @DisplayName("the apex firing suppresses every firing node below it, through both branches")
        void apexSuppressesEverything() {
            breach("B");
            breach("C");
            breach("D");

            breach("A");

            assertThat(states())
                    .containsEntry("A", "FIRING")
                    .containsEntry("B", "SUPPRESSED<-A")
                    .containsEntry("C", "SUPPRESSED<-A")
                    .containsEntry("D", "SUPPRESSED<-A");
        }

        @Test
        @DisplayName("siblings do not suppress each other")
        void siblingsAreIndependent() {
            breach("B");

            assertThat(breach("C").get(0).state()).isEqualTo(AlertState.FIRING);
        }
    }

    @Nested
    @DisplayName("release order: R -> A -> B and R -> B")
    class ReleaseOrder {

        ReleaseOrder() {
            node("R");
            node("A", "R");
            node("B", "A", "R");
        }

        @Test
        @DisplayName("ancestors are re-examined first: B ends up under A, not firing beside it")
        void releaseCascadesInTopologicalOrder() {
            breach("R");
            Alert a = breach("A").get(0);
            breach("B");

            clear("R");

            assertThat(states()).containsEntry("A", "FIRING").containsEntry("B", "SUPPRESSED<-A");
            assertThat(alertOn("B").suppressedBy()).isEqualTo(a.alertId());
        }
    }

    @Nested
    @DisplayName("two independent roots")
    class TwoRoots {

        TwoRoots() {
            node("R1");
            node("X1", "R1");
            node("Y1", "X1");
            node("R2");
            node("X2", "R2");
        }

        @Test
        @DisplayName("each root suppresses only its own subtree, and releasing one leaves the other alone")
        void independentRoots() {
            Alert r1 = breach("R1").get(0);
            Alert r2 = breach("R2").get(0);
            Alert x1 = breach("X1").get(0);
            Alert x2 = breach("X2").get(0);
            Alert y1 = breach("Y1").get(0);

            assertThat(x1.suppressedBy()).isEqualTo(r1.alertId());
            assertThat(x2.suppressedBy()).isEqualTo(r2.alertId());
            assertThat(y1.suppressedBy()).isEqualTo(r1.alertId());

            clear("R1");

            assertThat(states())
                    .containsEntry("R2", "FIRING")
                    .containsEntry("X2", "SUPPRESSED<-R2")
                    .containsEntry("X1", "FIRING")
                    .containsEntry("Y1", "SUPPRESSED<-X1");
        }

        @Test
        @DisplayName("an unrelated root firing does not suppress a node in another subtree")
        void unrelatedRootIgnored() {
            breach("R2");

            assertThat(breach("X1").get(0).state()).isEqualTo(AlertState.FIRING);
        }
    }

    @Test
    @DisplayName("suppression never crosses tenants")
    void noSuppressionAcrossTenants() {
        node("CDN");
        tenantNode("other", "PACKAGER", "CDN");

        breach("CDN");

        assertThat(breach("other", "PACKAGER").get(0).state()).isEqualTo(AlertState.FIRING);
    }

    @Test
    @DisplayName("an uncategorised rule neither suppresses nor is suppressed")
    void uncategorisedTakesNoPart() {
        node("CDN");
        node("PACKAGER", "CDN");
        node(AlertRule.UNCATEGORISED);

        breach("CDN");

        assertThat(breach(AlertRule.UNCATEGORISED).get(0).state()).isEqualTo(AlertState.FIRING);
    }

    @Test
    @DisplayName("retiring a root rule releases what it was suppressing")
    void retiringARootReleasesItsSuppressed() {
        node("CDN");
        node("PACKAGER", "CDN");
        breach("CDN");
        breach("PACKAGER");

        AlertRule removed = rules.remove("rule-CDN").orElseThrow();
        var changes = engine.retire(removed);

        assertThat(changes).extracting(Alert::state).containsExactly(AlertState.RESOLVED, AlertState.FIRING);
    }
}
