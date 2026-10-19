/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.feed.adapter.in.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.streampulse.feed.application.FeedService;
import com.streampulse.feed.domain.FeedPager;
import com.streampulse.feed.domain.RankedEntry;
import com.streampulse.feed.domain.UserId;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1")
public class FeedController {

    public record Item(String authorId, String titleId, Instant watchedAt, double score) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FeedResponse(List<Item> items, String nextCursor) {}

    private final FeedService service;

    public FeedController(FeedService service) {
        this.service = service;
    }

    @GetMapping("/feed")
    FeedResponse feed(
            @RequestParam @NotNull @Pattern(regexp = UserId.PATTERN) String userId,
            @RequestParam(required = false) @Size(max = 512) String cursor,
            @RequestParam(defaultValue = "" + FeedPager.DEFAULT_LIMIT) @Min(1) @Max(FeedPager.MAX_LIMIT) int limit) {
        FeedService.Result result = service.read(new UserId(userId), Optional.ofNullable(cursor), limit);
        List<Item> items = result.items().stream().map(FeedController::item).toList();
        return new FeedResponse(items, result.nextCursor().orElse(null));
    }

    private static Item item(RankedEntry ranked) {
        var entry = ranked.entry();
        return new Item(entry.author().value(), entry.title().value(), entry.occurredAt(), ranked.score());
    }
}
