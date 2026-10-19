/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.alert.support;

import com.streampulse.alert.domain.Alert;
import com.streampulse.alert.domain.AlertKey;
import com.streampulse.alert.domain.AlertState;
import com.streampulse.alert.domain.Series;
import com.streampulse.alert.domain.Severity;

public final class Alerts {

    private Alerts() {}

    public static Alert firing(String tenant, String id) {
        var key = new AlertKey("rule-" + id, new Series(tenant, "title-1", "us-east-1", "ERROR_RATE"));
        return new Alert(
                key,
                id,
                AlertState.FIRING,
                Severity.CRITICAL,
                "CDN",
                "rule",
                "summary " + id,
                Fixtures.T0,
                Fixtures.T0,
                Fixtures.T0,
                null,
                null,
                0.5,
                0.1);
    }
}
