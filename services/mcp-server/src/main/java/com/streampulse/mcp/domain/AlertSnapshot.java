/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.mcp.domain;

import java.time.Instant;

/**
 * One alert as alert-engine reports it (api/openapi/alerts-v1.yaml). The strings {@code ruleId},
 * {@code series} and {@code summary} are tenant-authored and arrive unvetted; the views decide what to
 * do with them.
 */
public record AlertSnapshot(
        String alertId,
        String tenantId,
        String ruleId,
        String series,
        AlertState state,
        Severity severity,
        String summary,
        Instant firedAt,
        Instant resolvedAt,
        String suppressedBy) {}
