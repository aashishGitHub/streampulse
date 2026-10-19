/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.alert.support;

import com.streampulse.alert.domain.AlertMetrics;
import com.streampulse.alert.domain.EvaluationOutcome;
import com.streampulse.alert.domain.Severity;
import java.util.ArrayList;
import java.util.List;

public final class RecordingMetrics implements AlertMetrics {

    public final List<String> events = new ArrayList<>();

    @Override
    public void evaluated(String conditionKind, EvaluationOutcome outcome) {
        events.add("evaluated:" + conditionKind + ":" + outcome);
    }

    @Override
    public void fired(String tenantId, Severity severity) {
        events.add("fired:" + tenantId + ":" + severity);
    }

    @Override
    public void suppressed(String tenantId, Severity severity) {
        events.add("suppressed:" + tenantId + ":" + severity);
    }

    @Override
    public void resolved(String tenantId, Severity severity) {
        events.add("resolved:" + tenantId + ":" + severity);
    }

    @Override
    public void rejected(String source, String reason) {
        events.add("rejected:" + source + ":" + reason);
    }

    @Override
    public void ruleApplied(String result) {
        events.add("rule:" + result);
    }

    @Override
    public void delivered(String channel) {
        events.add("delivered:" + channel);
    }

    @Override
    public void deliveryFailed(String channel) {
        events.add("deliveryFailed:" + channel);
    }
}
