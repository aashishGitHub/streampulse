/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.controlplane.domain;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streampulse.platform.domain.TenantId;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TenantHierarchyTest {

    private static TenantId t(String id) {
        return TenantId.of(id);
    }

    private static Function<TenantId, Optional<TenantId>> tree(Map<String, String> childToParent) {
        var parents = new HashMap<TenantId, TenantId>();
        childToParent.forEach((c, p) -> parents.put(t(c), t(p)));
        return id -> Optional.ofNullable(parents.get(id));
    }

    @Test
    @DisplayName("a tenant cannot be its own parent")
    void selfParentIsRejected() {
        assertThatThrownBy(() -> TenantHierarchy.assertNoCycle(t("acme"), t("acme"), tree(Map.of())))
                .isInstanceOf(RuleViolationException.class)
                .hasMessageContaining("its own ancestor");
    }

    @Test
    @DisplayName("a tenant cannot be re-parented under its own child")
    void childAsParentIsRejected() {
        var parents = tree(Map.of("kids", "acme"));

        assertThatThrownBy(() -> TenantHierarchy.assertNoCycle(t("acme"), t("kids"), parents))
                .isInstanceOf(RuleViolationException.class);
    }

    @Test
    @DisplayName("a tenant cannot be re-parented under a distant descendant")
    void deepDescendantAsParentIsRejected() {
        var parents = tree(Map.of("b", "a", "c", "b", "d", "c"));

        assertThatThrownBy(() -> TenantHierarchy.assertNoCycle(t("a"), t("d"), parents))
                .isInstanceOf(RuleViolationException.class);
    }

    @Test
    @DisplayName("moving a tenant under a sibling branch is allowed")
    void siblingBranchIsAllowed() {
        var parents = tree(Map.of("b", "a", "c", "a"));

        assertThatCode(() -> TenantHierarchy.assertNoCycle(t("b"), t("c"), parents))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a root (no parent) is always allowed")
    void rootIsAllowed() {
        assertThatCode(() -> TenantHierarchy.assertNoCycle(t("acme"), null, tree(Map.of())))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a loop already present in the data ends the walk instead of spinning forever")
    void existingLoopTerminates() {
        var corrupt = tree(Map.of("x", "y", "y", "x"));

        assertThatCode(() -> TenantHierarchy.assertNoCycle(t("acme"), t("x"), corrupt))
                .doesNotThrowAnyException();
    }
}
