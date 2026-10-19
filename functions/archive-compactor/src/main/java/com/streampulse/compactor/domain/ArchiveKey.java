/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.compactor.domain;

import com.streampulse.platform.domain.TenantId;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** An object key of the form {@code tenant=<t>/dt=<yyyy-mm-dd>/hour=<hh>/<name>}. */
public record ArchiveKey(PartitionId partition, String name) {

    public enum Kind {
        RAW,
        COMBINED,
        MANIFEST,
        OTHER
    }

    public static final String COMBINED_PREFIX = "compacted-";
    public static final String DATA_SUFFIX = ".ndjson.gz";
    public static final String MANIFEST_SUFFIX = ".manifest";

    private static final Pattern LAYOUT =
            Pattern.compile("^tenant=([^/]+)/dt=(\\d{4}-\\d{2}-\\d{2})/hour=(\\d{2})/([^/]+)$");

    /** Empty for anything outside the layout, including a tenant id that is not already canonical. */
    public static Optional<ArchiveKey> parse(String key) {
        Matcher m = LAYOUT.matcher(key);
        if (!m.matches()) {
            return Optional.empty();
        }
        try {
            TenantId tenant = new TenantId(m.group(1));
            // TenantId lowercases and trims. A key that differs from its canonical form would
            // re-render to a prefix that does not exist, so it is not ours to touch.
            if (!tenant.value().equals(m.group(1))) {
                return Optional.empty();
            }
            var partition = new PartitionId(tenant, LocalDate.parse(m.group(2)), Integer.parseInt(m.group(3)));
            return Optional.of(new ArchiveKey(partition, m.group(4)));
        } catch (IllegalArgumentException | DateTimeException e) {
            return Optional.empty();
        }
    }

    public Kind kind() {
        boolean ours = name.startsWith(COMBINED_PREFIX);
        if (ours && name.endsWith(MANIFEST_SUFFIX)) {
            return Kind.MANIFEST;
        }
        if (name.endsWith(DATA_SUFFIX)) {
            return ours ? Kind.COMBINED : Kind.RAW;
        }
        return Kind.OTHER;
    }
}
