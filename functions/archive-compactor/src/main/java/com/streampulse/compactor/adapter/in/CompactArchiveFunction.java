/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.compactor.adapter.in;

import com.streampulse.compactor.domain.CompactionReport;
import com.streampulse.compactor.domain.Compactor;
import com.streampulse.compactor.domain.Scope;
import com.streampulse.platform.domain.TenantId;
import java.time.LocalDate;
import java.util.function.Function;

/** The Lambda-shaped entry point: a request in, a report out, no framework type in sight. */
public final class CompactArchiveFunction implements Function<CompactionRequest, CompactionReport> {

    private final Compactor compactor;

    public CompactArchiveFunction(Compactor compactor) {
        this.compactor = compactor;
    }

    @Override
    public CompactionReport apply(CompactionRequest request) {
        if (request == null) {
            return compactor.compact(Scope.all());
        }
        TenantId tenant = isBlank(request.tenant()) ? null : new TenantId(request.tenant());
        LocalDate date = isBlank(request.date()) ? null : LocalDate.parse(request.date());
        return compactor.compact(Scope.of(tenant, date));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
