/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.query.adapter.out.catalog;

import com.streampulse.query.application.TitleCatalogPort;
import com.streampulse.query.domain.TitleMetadata;
import java.time.Duration;

/**
 * Stands in for the control plane, which does not exist yet. The latency is configurable so the
 * overview's budget and degradation can be exercised against a slow dependency.
 */
public final class StubTitleCatalog implements TitleCatalogPort {

    private final Duration latency;

    public StubTitleCatalog(Duration latency) {
        this.latency = latency;
    }

    @Override
    public TitleMetadata title(String tenantId, String titleId) {
        try {
            Thread.sleep(latency);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while reading the title catalog", e);
        }
        return new TitleMetadata(titleId, "Title " + titleId);
    }
}
