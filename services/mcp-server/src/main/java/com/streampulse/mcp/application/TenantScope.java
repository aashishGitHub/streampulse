/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.mcp.application;

import com.streampulse.mcp.domain.AlertSnapshot;
import com.streampulse.platform.domain.TenantId;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The tenant isolation rule: a backend is asked about one tenant, and whatever it returns for another is
 * dropped before anything is counted or shown. Backends do not authenticate (their contracts say so), so
 * this is the only check on this side.
 */
final class TenantScope {

    private static final Logger LOG = LoggerFactory.getLogger(TenantScope.class);

    private TenantScope() {}

    static List<AlertSnapshot> only(TenantId tenant, List<AlertSnapshot> alerts) {
        List<AlertSnapshot> own =
                alerts.stream().filter(a -> tenant.value().equals(a.tenantId())).toList();
        if (own.size() != alerts.size()) {
            // a count only: the dropped items' content is another tenant's data
            LOG.warn("alert backend returned {} item(s) of another tenant; dropped", alerts.size() - own.size());
        }
        return own;
    }
}
