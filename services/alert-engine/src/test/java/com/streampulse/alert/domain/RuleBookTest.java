/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.alert.domain;

import static com.streampulse.alert.support.Fixtures.errorRateAbove;
import static com.streampulse.alert.support.Fixtures.obs;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streampulse.alert.domain.RuleBook.PutResult;
import com.streampulse.alert.domain.graph.CyclicDependencyException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RuleBookTest {

    private final RuleBook book = new RuleBook();

    @Test
    @DisplayName("rules are found by tenant and metric")
    void lookup() {
        book.put(errorRateAbove("r1", 0.05, "CDN"), 1);

        assertThat(book.rulesFor(obs("ERROR_RATE", 0, 1)))
                .extracting(AlertRule::ruleId)
                .containsExactly("r1");
        assertThat(book.rulesFor(obs("CONCURRENT_VIEWERS", 0, 1))).isEmpty();
        assertThat(book.rulesFor(obs("other-tenant", "ERROR_RATE", 0, 1))).isEmpty();
    }

    @Test
    @DisplayName("a title or region filter narrows the match")
    void filters() {
        var scoped = new AlertRule(
                "r1",
                "streamflix",
                "r1",
                "ERROR_RATE",
                new Condition.Threshold(Comparison.GT, 1),
                Severity.INFO,
                "CDN",
                java.util.Set.of(),
                "title-2",
                null);
        book.put(scoped, 1);

        assertThat(book.rulesFor(obs("ERROR_RATE", 0, 1))).isEmpty();
    }

    @Test
    @DisplayName("a newer version replaces the rule and an older one is ignored")
    void olderVersionIsIgnored() {
        book.put(errorRateAbove("r1", 0.05, "CDN"), 5);

        assertThat(book.put(errorRateAbove("r1", 0.99, "CDN"), 4)).isEqualTo(PutResult.STALE);
        assertThat(book.find("r1").orElseThrow().condition()).isEqualTo(new Condition.Threshold(Comparison.GT, 0.05));

        assertThat(book.put(errorRateAbove("r1", 0.2, "CDN"), 6)).isEqualTo(PutResult.APPLIED);
        assertThat(book.find("r1").orElseThrow().condition()).isEqualTo(new Condition.Threshold(Comparison.GT, 0.2));
    }

    @Test
    @DisplayName("the same version again is applied: a replay must be a no-op in effect")
    void sameVersionReplay() {
        book.put(errorRateAbove("r1", 0.05, "CDN"), 5);

        assertThat(book.put(errorRateAbove("r1", 0.05, "CDN"), 5)).isEqualTo(PutResult.APPLIED);
        assertThat(book.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("remove returns the rule and forgets it")
    void remove() {
        book.put(errorRateAbove("r1", 0.05, "CDN"), 1);

        assertThat(book.remove("r1")).isPresent();
        assertThat(book.remove("r1")).isEmpty();
        assertThat(book.rulesFor(obs("ERROR_RATE", 0, 1))).isEmpty();
        assertThat(book.size()).isZero();
    }

    @Test
    @DisplayName("the graph is derived from the rules' upstream sets")
    void graphFromRules() {
        book.put(errorRateAbove("cdn", 0.05, "CDN"), 1);
        book.put(errorRateAbove("pkg", 0.05, "PACKAGER", "CDN"), 1);
        book.put(errorRateAbove("ply", 0.05, "PLAYER", "PACKAGER"), 1);

        assertThat(book.graphFor("streamflix").upstreamLevels("PLAYER"))
                .containsExactly(List.of("PACKAGER"), List.of("CDN"));
        assertThat(book.graphFor("nobody").upstreamLevels("PLAYER")).isEmpty();
    }

    @Test
    @DisplayName("a rule that closes a dependency cycle is rejected and the book is unchanged")
    void cyclicRuleIsRejected() {
        book.put(errorRateAbove("pkg", 0.05, "PACKAGER", "CDN"), 1);

        assertThatThrownBy(() -> book.put(errorRateAbove("cdn", 0.05, "CDN", "PACKAGER"), 1))
                .isInstanceOf(CyclicDependencyException.class)
                .hasMessage("dependency cycle: CDN -> PACKAGER -> CDN");

        assertThat(book.find("cdn")).isEmpty();
        assertThat(book.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("a rule that depends on its own node is a self-loop and is rejected")
    void selfLoopRejected() {
        assertThatThrownBy(() -> book.put(errorRateAbove("cdn", 0.05, "CDN", "CDN"), 1))
                .isInstanceOf(CyclicDependencyException.class)
                .hasMessage("dependency cycle: CDN -> CDN");
    }

    @Test
    @DisplayName("graphs are per tenant: another tenant's rules never add edges")
    void tenantsAreIsolated() {
        book.put(errorRateAbove("a", 0.05, "PACKAGER", "CDN"), 1);
        book.put(
                new AlertRule(
                        "b",
                        "other",
                        "b",
                        "ERROR_RATE",
                        new Condition.Threshold(Comparison.GT, 1),
                        Severity.INFO,
                        "CDN",
                        java.util.Set.of("PACKAGER"),
                        null,
                        null),
                1);

        assertThat(book.graphFor("streamflix").upstreamLevels("PACKAGER")).containsExactly(List.of("CDN"));
        assertThat(book.graphFor("other").upstreamLevels("CDN")).containsExactly(List.of("PACKAGER"));
    }

    @Test
    @DisplayName("a deleted rule forgets its version: re-created at version 0, the new rule takes effect")
    void recreateAfterDeleteAtVersionZero() {
        book.put(errorRateAbove("r1", 0.05, "CDN"), 7);
        book.remove("r1");

        assertThat(book.put(errorRateAbove("r1", 0.9, "CDN"), 0)).isEqualTo(PutResult.APPLIED);
        assertThat(book.find("r1").orElseThrow().condition()).isEqualTo(new Condition.Threshold(Comparison.GT, 0.9));
    }

    @Test
    @DisplayName("update to version 1, then a stale version 0 redelivery is still ignored")
    void staleVersionZeroAfterOne() {
        book.put(errorRateAbove("r1", 0.05, "CDN"), 0);
        book.put(errorRateAbove("r1", 0.2, "CDN"), 1);

        assertThat(book.put(errorRateAbove("r1", 0.05, "CDN"), 0)).isEqualTo(PutResult.STALE);
        assertThat(book.find("r1").orElseThrow().condition()).isEqualTo(new Condition.Threshold(Comparison.GT, 0.2));
    }
}
