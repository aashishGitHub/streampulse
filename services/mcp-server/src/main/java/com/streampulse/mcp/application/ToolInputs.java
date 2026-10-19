/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.mcp.application;

import com.streampulse.mcp.domain.SafeId;
import com.streampulse.platform.domain.TenantId;
import java.util.Optional;

/** Validates every tool argument before any backend is contacted. */
public final class ToolInputs {

    private static final int MAX_RAW_LENGTH = 256;

    private ToolInputs() {}

    public static TenantId tenant(String raw) {
        if (raw == null || raw.length() > MAX_RAW_LENGTH) {
            throw invalid(
                    "tenantId",
                    "1-32 characters: lowercase letters, digits and hyphens, not starting or ending with a hyphen");
        }
        try {
            return TenantId.of(raw);
        } catch (IllegalArgumentException e) {
            // TenantId's own message quotes the rejected value; ours must not.
            throw invalid(
                    "tenantId",
                    "1-32 characters: lowercase letters, digits and hyphens, not starting or ending with a hyphen");
        }
    }

    /** Absent or blank means "all titles". */
    public static Optional<String> optionalTitle(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(SafeId.name(raw)
                .orElseThrow(() -> invalid("titleId", "1-64 characters: letters, digits, '.', '_' and '-'")));
    }

    public static String alertId(String raw) {
        return SafeId.alertId(raw)
                .orElseThrow(() -> invalid("alertId", "1-128 characters: letters, digits, '.', '_', ':' and '-'"));
    }

    private static InvalidToolInput invalid(String argument, String rule) {
        return new InvalidToolInput("invalid argument '" + argument + "': expected " + rule);
    }
}
