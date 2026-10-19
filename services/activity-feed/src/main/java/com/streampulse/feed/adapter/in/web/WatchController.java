/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.feed.adapter.in.web;

import com.streampulse.feed.application.WatchService;
import com.streampulse.feed.domain.TitleId;
import com.streampulse.feed.domain.UserId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1")
public class WatchController {

    public record WatchRequest(
            @NotNull @Pattern(regexp = UserId.PATTERN) String userId,
            @NotNull @Pattern(regexp = TitleId.PATTERN) String titleId,
            UUID eventId) {}

    public record WatchAccepted(UUID eventId) {}

    private final WatchService service;

    public WatchController(WatchService service) {
        this.service = service;
    }

    /** 202: recorded for processing, not yet visible in any follower's feed. */
    @PostMapping("/watching")
    ResponseEntity<WatchAccepted> watch(@Valid @RequestBody WatchRequest request) {
        UUID eventId = service.watch(
                new UserId(request.userId()), new TitleId(request.titleId()), Optional.ofNullable(request.eventId()));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new WatchAccepted(eventId));
    }
}
