/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.alert.adapter.in.web;

import com.streampulse.alert.application.AlertProcessor;
import com.streampulse.alert.application.AlertView;
import java.util.List;
import java.util.OptionalLong;
import java.util.regex.Pattern;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The read interface, pinned by api/openapi/alerts-v1.yaml.
 *
 * <p>{@code tenantId} is a filter the client asserts, not an authorisation: there is no
 * authentication on this service yet (requirements, "Honest gaps").
 */
@RestController
@RequestMapping("/v1/alerts")
class AlertController {

    // The tenant id format of the control-plane contract; it also keeps odd characters out of logs.
    private static final Pattern TENANT = Pattern.compile("^[a-z0-9]([a-z0-9-]{0,30}[a-z0-9])?$");

    private final AlertProcessor processor;
    private final SseStreamer streamer;

    AlertController(AlertProcessor processor, SseStreamer streamer) {
        this.processor = processor;
        this.streamer = streamer;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    List<AlertView> list(@RequestParam String tenantId) {
        return processor.activeAlerts(validTenant(tenantId)).stream()
                .map(AlertView::from)
                .toList();
    }

    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter stream(
            @RequestParam String tenantId,
            @RequestHeader(name = "Last-Event-ID", required = false) String lastEventId) {
        return streamer.open(validTenant(tenantId), parse(lastEventId));
    }

    private static String validTenant(String tenantId) {
        if (!TENANT.matcher(tenantId).matches()) {
            throw new InvalidRequestException("'tenantId' must match " + TENANT.pattern());
        }
        return tenantId;
    }

    /** An id this instance never issued is treated as absent: the client then gets live events only. */
    private static OptionalLong parse(String lastEventId) {
        if (lastEventId == null || lastEventId.isBlank()) {
            return OptionalLong.empty();
        }
        try {
            long id = Long.parseLong(lastEventId.trim());
            return id < 0 ? OptionalLong.empty() : OptionalLong.of(id);
        } catch (NumberFormatException e) {
            return OptionalLong.empty();
        }
    }
}
