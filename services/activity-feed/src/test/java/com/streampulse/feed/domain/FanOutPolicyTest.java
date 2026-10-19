/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.feed.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FanOutPolicyTest {

    private final FanOutPolicy policy = new FanOutPolicy(1_000);

    @Test
    @DisplayName("below the threshold the entry is pushed to every follower")
    void belowTheThresholdFansOutOnWrite() {
        assertThat(policy.strategyFor(0)).isEqualTo(FanOutStrategy.ON_WRITE);
        assertThat(policy.strategyFor(999)).isEqualTo(FanOutStrategy.ON_WRITE);
        assertThat(policy.isCelebrity(999)).isFalse();
    }

    @Test
    @DisplayName("exactly at the threshold the author is a celebrity: the boundary belongs to the cheaper strategy")
    void exactlyAtTheThresholdIsACelebrity() {
        assertThat(policy.strategyFor(1_000)).isEqualTo(FanOutStrategy.ON_READ);
        assertThat(policy.isCelebrity(1_000)).isTrue();
    }

    @Test
    @DisplayName("above the threshold followers pull the author's entries at read time")
    void aboveTheThresholdFansOutOnRead() {
        assertThat(policy.strategyFor(1_001)).isEqualTo(FanOutStrategy.ON_READ);
        assertThat(policy.strategyFor(Long.MAX_VALUE)).isEqualTo(FanOutStrategy.ON_READ);
    }

    @Test
    @DisplayName("a threshold of zero or less would make every author a celebrity or none, and is rejected")
    void aNonPositiveThresholdIsRejected() {
        assertThatThrownBy(() -> new FanOutPolicy(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FanOutPolicy(-5)).isInstanceOf(IllegalArgumentException.class);
    }
}
