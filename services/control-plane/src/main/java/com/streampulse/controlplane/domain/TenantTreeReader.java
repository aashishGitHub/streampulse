/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.controlplane.domain;

import java.util.List;

public interface TenantTreeReader {

    /**
     * Every root tenant with its descendants, apps and titles. The cost in store round trips must
     * not grow with the number of tenants.
     */
    List<TenantTreeNode> readTree();
}
