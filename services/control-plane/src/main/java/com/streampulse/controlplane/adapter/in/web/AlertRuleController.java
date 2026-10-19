/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.controlplane.adapter.in.web;

import com.streampulse.controlplane.adapter.in.web.dto.AlertRuleDtos.AlertRuleResponse;
import com.streampulse.controlplane.adapter.in.web.dto.AlertRuleDtos.CreateAlertRuleRequest;
import com.streampulse.controlplane.adapter.in.web.dto.AlertRuleDtos.UpdateAlertRuleRequest;
import com.streampulse.controlplane.application.AlertRuleService;
import com.streampulse.controlplane.domain.AlertRule;
import com.streampulse.controlplane.domain.Ids;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/admin/rules")
public class AlertRuleController {

    private final AlertRuleService rules;

    public AlertRuleController(AlertRuleService rules) {
        this.rules = rules;
    }

    @GetMapping
    public List<AlertRuleResponse> list(@RequestParam(required = false) String tenantId) {
        var tenant = tenantId == null ? null : Ids.tenantId(tenantId);
        return rules.list(tenant).stream().map(AlertRuleResponse::from).toList();
    }

    @PostMapping
    public ResponseEntity<AlertRuleResponse> create(@Valid @RequestBody CreateAlertRuleRequest request) {
        AlertRule stored = rules.create(new AlertRule(
                request.ruleId(),
                Ids.tenantId(request.tenantId()),
                request.metric(),
                request.comparator(),
                request.threshold(),
                request.severity(),
                request.category(),
                request.dependsOn(),
                0));
        return ResponseEntity.created(URI.create("/v1/admin/rules/" + stored.ruleId()))
                .body(AlertRuleResponse.from(stored));
    }

    @GetMapping("/{ruleId}")
    public AlertRuleResponse get(@PathVariable String ruleId) {
        return AlertRuleResponse.from(rules.get(ruleId));
    }

    @PutMapping("/{ruleId}")
    public AlertRuleResponse update(@PathVariable String ruleId, @Valid @RequestBody UpdateAlertRuleRequest request) {
        return AlertRuleResponse.from(rules.update(
                ruleId,
                request.metric(),
                request.comparator(),
                request.threshold(),
                request.severity(),
                request.category(),
                request.dependsOn()));
    }

    @DeleteMapping("/{ruleId}")
    public ResponseEntity<Void> delete(@PathVariable String ruleId) {
        rules.delete(ruleId);
        return ResponseEntity.noContent().build();
    }
}
