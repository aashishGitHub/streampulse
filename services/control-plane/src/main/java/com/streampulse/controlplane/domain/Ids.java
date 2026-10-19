/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.controlplane.domain;

import com.streampulse.platform.domain.TenantId;
import java.util.regex.Pattern;

/** Validation of identifiers a client chooses. They end up in outbox keys, so {@code /} is excluded. */
public final class Ids {

    /** Tenant ids that would shadow a fixed sub-resource of {@code /tenants}. */
    private static final String RESERVED_TENANT_ID = "tree";

    private static final Pattern ENTITY_ID = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$");

    private Ids() {}

    public static String entityId(String field, String value) {
        if (value == null || !ENTITY_ID.matcher(value).matches()) {
            throw new InvalidInputException(field + " must match " + ENTITY_ID.pattern());
        }
        return value;
    }

    public static TenantId tenantId(String value) {
        TenantId id;
        try {
            id = TenantId.of(value);
        } catch (IllegalArgumentException e) {
            throw new InvalidInputException(e.getMessage());
        }
        if (RESERVED_TENANT_ID.equals(id.value())) {
            throw new InvalidInputException("tenantId '" + RESERVED_TENANT_ID + "' is reserved");
        }
        return id;
    }

    public static String requireText(String field, String value, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new InvalidInputException(field + " must not be blank");
        }
        if (value.length() > maxLength) {
            throw new InvalidInputException(field + " must be at most " + maxLength + " characters");
        }
        return value;
    }
}
