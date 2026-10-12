/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.query.application;

import com.streampulse.query.domain.TitleMetadata;

/** Blocking by design. */
public interface TitleCatalogPort {

    TitleMetadata title(String tenantId, String titleId);
}
