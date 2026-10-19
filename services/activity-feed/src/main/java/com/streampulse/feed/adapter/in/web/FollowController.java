/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.feed.adapter.in.web;

import com.streampulse.feed.application.FollowService;
import com.streampulse.feed.domain.FanOutStrategy;
import com.streampulse.feed.domain.UserId;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * No authentication exists yet, so the caller names the follower explicitly. With auth this
 * becomes the authenticated principal, and the parameter goes away.
 */
@RestController
@RequestMapping("/v1/users/{id}")
public class FollowController {

    public record FollowResponse(boolean changed, long followerCount) {}

    public record FollowersResponse(String userId, long followers, FanOutStrategy strategy) {}

    private final FollowService service;

    public FollowController(FollowService service) {
        this.service = service;
    }

    @PostMapping("/follow")
    FollowResponse follow(
            @PathVariable("id") @Pattern(regexp = UserId.PATTERN) String followee,
            @RequestParam @NotNull @Pattern(regexp = UserId.PATTERN) String followerId) {
        return response(service.follow(new UserId(followerId), new UserId(followee)));
    }

    @DeleteMapping("/follow")
    FollowResponse unfollow(
            @PathVariable("id") @Pattern(regexp = UserId.PATTERN) String followee,
            @RequestParam @NotNull @Pattern(regexp = UserId.PATTERN) String followerId) {
        return response(service.unfollow(new UserId(followerId), new UserId(followee)));
    }

    @GetMapping("/followers")
    FollowersResponse followers(@PathVariable("id") @Pattern(regexp = UserId.PATTERN) String id) {
        FollowService.Summary summary = service.summary(new UserId(id));
        return new FollowersResponse(summary.user().value(), summary.followers(), summary.strategy());
    }

    private static FollowResponse response(FollowService.Result result) {
        return new FollowResponse(result.changed(), result.followerCount());
    }
}
