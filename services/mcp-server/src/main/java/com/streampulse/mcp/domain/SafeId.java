/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.mcp.domain;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * An identifier this server is willing to treat as one. Anything else a backend sends in an identifier slot
 * is tenant-authored text and is demoted to the untrusted field instead.
 */
public final class SafeId {

    private static final Pattern NAME = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$");
    private static final Pattern ALERT_ID = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$");

    private SafeId() {}

    /** Titles, regions, rule ids: at most 64 characters. */
    public static Optional<String> name(String raw) {
        return raw != null && NAME.matcher(raw).matches() ? Optional.of(raw) : Optional.empty();
    }

    /** Alert ids are UUIDs today; the bound leaves room without admitting free text. */
    public static Optional<String> alertId(String raw) {
        return raw != null && ALERT_ID.matcher(raw).matches() ? Optional.of(raw) : Optional.empty();
    }
}
