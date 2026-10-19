/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.feed.application;

import static com.streampulse.feed.support.Fixtures.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streampulse.feed.domain.FanOutPolicy;
import com.streampulse.feed.domain.FanOutStrategy;
import com.streampulse.feed.domain.InvalidFeedRequestException;
import com.streampulse.feed.support.InMemoryFollowGraph;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FollowServiceTest {

    private final InMemoryFollowGraph graph = new InMemoryFollowGraph();
    private final FollowService service = new FollowService(graph, new FanOutPolicy(3));

    @Test
    @DisplayName("a follow is recorded once, however often it is requested")
    void repeatedFollowChangesOnce() {
        assertThat(service.follow(user("a"), user("b"))).isEqualTo(new FollowService.Result(true, 1));
        assertThat(service.follow(user("a"), user("b"))).isEqualTo(new FollowService.Result(false, 1));
    }

    @Test
    @DisplayName("an unfollow removes once, and unfollowing a stranger changes nothing")
    void repeatedUnfollowChangesOnce() {
        service.follow(user("a"), user("b"));

        assertThat(service.unfollow(user("a"), user("b"))).isEqualTo(new FollowService.Result(true, 0));
        assertThat(service.unfollow(user("a"), user("b"))).isEqualTo(new FollowService.Result(false, 0));
        assertThat(service.unfollow(user("x"), user("y"))).isEqualTo(new FollowService.Result(false, 0));
    }

    @Test
    @DisplayName("a user cannot follow themselves")
    void aUserCannotFollowThemselves() {
        assertThatThrownBy(() -> service.follow(user("a"), user("a"))).isInstanceOf(InvalidFeedRequestException.class);
        assertThat(service.summary(user("a")).followers()).isZero();
    }

    @Test
    @DisplayName("the summary reports the count and the strategy the count selects")
    void summaryNamesTheStrategy() {
        service.follow(user("f1"), user("star"));
        service.follow(user("f2"), user("star"));
        assertThat(service.summary(user("star")).strategy()).isEqualTo(FanOutStrategy.ON_WRITE);

        service.follow(user("f3"), user("star"));

        var summary = service.summary(user("star"));
        assertThat(summary.followers()).isEqualTo(3);
        assertThat(summary.strategy()).isEqualTo(FanOutStrategy.ON_READ);
    }
}
