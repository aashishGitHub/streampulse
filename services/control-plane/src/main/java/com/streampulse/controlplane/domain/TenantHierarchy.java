/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.controlplane.domain;

import com.streampulse.platform.domain.TenantId;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/** The tenant tree's one structural rule: a tenant is never its own ancestor. */
public final class TenantHierarchy {

    private TenantHierarchy() {}

    /**
     * Walks from the proposed parent to the root. Meeting {@code tenant} on the way means the new
     * edge would close a loop. A loop that is ALREADY in the data cannot spin this forever: a
     * repeated node ends the walk.
     *
     * @param parentOf the parent of a tenant, empty for a root or an unknown tenant
     */
    public static void assertNoCycle(
            TenantId tenant, TenantId proposedParent, Function<TenantId, Optional<TenantId>> parentOf) {
        Set<TenantId> seen = new HashSet<>();
        TenantId cursor = proposedParent;
        while (cursor != null) {
            if (cursor.equals(tenant)) {
                throw new RuleViolationException(
                        "tenant '" + tenant + "' would become its own ancestor via '" + proposedParent + "'");
            }
            if (!seen.add(cursor)) {
                return;
            }
            cursor = parentOf.apply(cursor).orElse(null);
        }
    }
}
