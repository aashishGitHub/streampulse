/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.query.adapter.in.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.streampulse.query.application.MetricQueryResult;
import com.streampulse.query.application.MetricQueryService;
import com.streampulse.query.domain.MetricPoint;
import java.time.Instant;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
public class MetricQueryController {

    private final MetricQueryService service;

    public MetricQueryController(MetricQueryService service) {
        this.service = service;
    }

    @GetMapping("/v1/metrics/{tenantId}/{metric}")
    public Mono<Response> query(
            @PathVariable String tenantId,
            @PathVariable String metric,
            @RequestParam Instant from,
            @RequestParam Instant to,
            @RequestParam(required = false) String titleId,
            @RequestParam(required = false) String region) {
        return Mono.fromFuture(() -> service.query(tenantId, metric, titleId, region, from, to))
                .map(Response::of);
    }

    public record Response(List<Window> windows, String source) {

        static Response of(MetricQueryResult result) {
            return new Response(result.windows().stream().map(Window::of).toList(), result.source());
        }
    }

    /** {@code titleId} and {@code region} are additive to the contract: without them two regions look like duplicates. */
    public record Window(
            Instant windowStart,
            double value,
            long sampleCount,
            @JsonProperty("isFinal") boolean isFinal,
            String titleId,
            String region) {

        // The sink writes only final aggregates (REQ-SP-09), so every stored window is final.
        static Window of(MetricPoint point) {
            return new Window(
                    point.windowStart(), point.value(), point.sampleCount(), true, point.titleId(), point.region());
        }
    }
}
