/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.controlplane.adapter.in.web.dto;

import com.streampulse.controlplane.domain.AlertRule;
import com.streampulse.controlplane.domain.AlertSeverity;
import com.streampulse.controlplane.domain.Category;
import com.streampulse.controlplane.domain.Comparison;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

public final class AlertRuleDtos {

    private AlertRuleDtos() {}

    public record CreateAlertRuleRequest(
            @NotBlank @Pattern(regexp = AppDtos.ID_PATTERN) String ruleId,
            @NotBlank @Pattern(regexp = TenantDtos.TENANT_ID_PATTERN) String tenantId,
            @NotBlank @Size(max = 120) String metric,
            Comparison comparator,
            @NotNull Double threshold,
            AlertSeverity severity,
            Category category,
            Set<Category> dependsOn) {}

    public record UpdateAlertRuleRequest(
            @NotBlank @Size(max = 120) String metric,
            Comparison comparator,
            @NotNull Double threshold,
            AlertSeverity severity,
            Category category,
            Set<Category> dependsOn) {}

    public record AlertRuleResponse(
            String ruleId,
            String tenantId,
            String metric,
            Comparison comparator,
            double threshold,
            AlertSeverity severity,
            Category category,
            List<Category> dependsOn,
            long version) {

        public static AlertRuleResponse from(AlertRule r) {
            return new AlertRuleResponse(
                    r.ruleId(),
                    r.tenantId().value(),
                    r.metric(),
                    r.comparison(),
                    r.threshold(),
                    r.severity(),
                    r.category(),
                    r.dependsOn().stream().sorted(Comparator.naturalOrder()).toList(),
                    r.version());
        }
    }
}
