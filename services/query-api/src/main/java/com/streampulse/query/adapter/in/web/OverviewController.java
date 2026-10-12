/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.query.adapter.in.web;

import com.streampulse.query.application.InvalidRequestException;
import com.streampulse.query.application.TitleOverviewService;
import com.streampulse.query.domain.TitleOverview;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
public class OverviewController {

    private final TitleOverviewService service;

    public OverviewController(TitleOverviewService service) {
        this.service = service;
    }

    @GetMapping("/v1/titles/{titleId}/overview")
    public Mono<TitleOverview> overview(@PathVariable String titleId, @RequestParam String tenantId) {
        if (tenantId.isBlank()) {
            throw new InvalidRequestException("tenantId must not be blank");
        }
        // The service never blocks this thread: it returns a future that completes on its own executor.
        return Mono.fromFuture(() -> service.overview(tenantId, titleId));
    }
}
