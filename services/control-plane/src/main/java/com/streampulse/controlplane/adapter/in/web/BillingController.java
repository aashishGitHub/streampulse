/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.controlplane.adapter.in.web;

import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Deferred, on purpose (REQ-CP-24): the repo has no usage table, and the only candidate source,
 * {@code metric_window}, is owned by the sink writer. An empty or invented answer would be worse
 * than an honest 501.
 */
@RestController
@RequestMapping("/v1/billing")
public class BillingController {

    private static final Pattern MONTH = Pattern.compile("^\\d{4}-(0[1-9]|1[0-2])$");

    @GetMapping("/usage")
    public ResponseEntity<ProblemDetail> usage(@RequestParam String month) {
        if (!MONTH.matcher(month).matches()) {
            var bad = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "month must look like 2026-10");
            bad.setTitle("Invalid month");
            return ResponseEntity.badRequest().body(bad);
        }
        var problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.NOT_IMPLEMENTED,
                "Billing usage is deferred: no usage data is aggregated for billing yet, and none is invented here.");
        problem.setTitle("Not implemented");
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).body(problem);
    }
}
