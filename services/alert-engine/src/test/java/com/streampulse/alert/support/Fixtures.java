/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.alert.support;

import com.streampulse.alert.domain.AlertRule;
import com.streampulse.alert.domain.Comparison;
import com.streampulse.alert.domain.Condition;
import com.streampulse.alert.domain.Observation;
import com.streampulse.alert.domain.Severity;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;

public final class Fixtures {

    public static final Instant T0 = Instant.parse("2026-10-12T10:00:00Z");
    public static final Duration WINDOW = Duration.ofSeconds(10);
    public static final String TENANT = "streamflix";
    public static final String TITLE = "title-1";
    public static final String REGION = "us-east-1";

    private Fixtures() {}

    /** Window number {@code index} counted from T0, one {@link #WINDOW} long. */
    public static Observation obs(String metric, int index, double value) {
        return obs(TENANT, metric, index, value);
    }

    public static Observation obs(String tenant, String metric, int index, double value) {
        Instant start = T0.plus(WINDOW.multipliedBy(index));
        return new Observation(tenant, TITLE, REGION, metric, start, start.plus(WINDOW), value);
    }

    /** ERROR_RATE above a limit, on a node, with the given upstream nodes. */
    public static AlertRule errorRateAbove(String ruleId, double limit, String node, String... upstream) {
        return rule(ruleId, TENANT, "ERROR_RATE", new Condition.Threshold(Comparison.GT, limit), node, upstream);
    }

    public static AlertRule rule(
            String ruleId, String tenant, String metric, Condition condition, String node, String... upstream) {
        return new AlertRule(
                ruleId, tenant, ruleId, metric, condition, Severity.CRITICAL, node, Set.of(upstream), null, null);
    }
}
