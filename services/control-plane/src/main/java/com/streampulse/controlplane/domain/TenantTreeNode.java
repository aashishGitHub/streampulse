/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.controlplane.domain;

import java.util.List;

public record TenantTreeNode(Tenant tenant, List<TenantApp> apps, List<Title> titles, List<TenantTreeNode> children) {}
