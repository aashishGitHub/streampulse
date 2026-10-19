/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.controlplane.domain;

import java.util.Map;

/** An entity whose every change is announced as a full snapshot. */
public interface ConfigEntity {

    ConfigKind configKind();

    /** The announcement key: unique across kinds and tenants. */
    String entityId();

    /** The tenant this entity belongs to (a tenant is its own owner). */
    String owningTenant();

    long version();

    /** The whole entity, never a delta. Explicit rather than reflected, so the wire shape is reviewed in code. */
    Map<String, Object> snapshot();
}
