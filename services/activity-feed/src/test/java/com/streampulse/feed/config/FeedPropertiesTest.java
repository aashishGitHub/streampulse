/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.feed.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class FeedPropertiesTest {

    @Configuration
    @EnableConfigurationProperties(FeedProperties.class)
    static class Props {}

    private static final String[] VALID = {
        "streampulse.feed.watch-topic=activity.watch.v1",
        "streampulse.feed.watch-dlt-topic=activity.watch.v1.dlt",
        "streampulse.feed.celebrity-threshold=10000",
        "streampulse.feed.cursor-secret=0123456789abcdef0123456789abcdef",
        "streampulse.feed.publish-timeout=3s",
        "streampulse.feed.dlt-send-timeout=10s",
        "streampulse.feed.follower-page-size=500",
        "streampulse.feed.timeline.max-length=200",
        "streampulse.feed.timeline.ttl=7d",
        "streampulse.feed.outbox.max-length=1000",
        "streampulse.feed.outbox.ttl=7d",
        "streampulse.feed.read.timeline-window=200",
        "streampulse.feed.read.celebrity-window=50",
        "streampulse.feed.read.max-celebrities=100",
        "streampulse.feed.read.max-followees=5000",
        "streampulse.feed.ranking.half-life=30m",
        "streampulse.feed.retry.initial-interval=1s",
        "streampulse.feed.retry.multiplier=2.0",
        "streampulse.feed.retry.max-interval=30s",
    };

    private ApplicationContextRunner runner(String... overrides) {
        return new ApplicationContextRunner()
                .withUserConfiguration(Props.class)
                .withPropertyValues(VALID)
                .withPropertyValues(overrides);
    }

    @Test
    @DisplayName("valid properties bind, with durations parsed")
    void validPropertiesBind() {
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            var props = context.getBean(FeedProperties.class);
            assertThat(props.celebrityThreshold()).isEqualTo(10_000);
            assertThat(props.timeline().ttl()).isEqualTo(Duration.ofDays(7));
            assertThat(props.ranking().halfLife()).isEqualTo(Duration.ofMinutes(30));
        });
    }

    @Test
    @DisplayName("the shipped application.yml is itself valid")
    void shippedConfigurationIsValid() {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(Props.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(FeedProperties.class).celebrityThreshold())
                            .isEqualTo(10_000);
                });
    }

    @Test
    @DisplayName("a non-positive celebrity threshold fails the boot")
    void nonPositiveThresholdFailsTheBoot() {
        runner("streampulse.feed.celebrity-threshold=0")
                .run(context -> assertThat(context).getFailure().hasStackTraceContaining("celebrityThreshold"));
    }

    @Test
    @DisplayName("a cursor secret shorter than 32 characters fails the boot")
    void shortSecretFailsTheBoot() {
        runner("streampulse.feed.cursor-secret=short")
                .run(context -> assertThat(context).getFailure().hasStackTraceContaining("cursorSecret"));
    }

    @Test
    @DisplayName("a blank topic fails the boot")
    void blankTopicFailsTheBoot() {
        runner("streampulse.feed.watch-topic=")
                .run(context -> assertThat(context).getFailure().hasStackTraceContaining("watchTopic"));
    }

    @Test
    @DisplayName("a zero TTL fails the boot: EXPIRE 0 would delete every timeline the moment it is written")
    void zeroTtlFailsTheBoot() {
        runner("streampulse.feed.timeline.ttl=0s")
                .run(context -> assertThat(context).getFailure().hasStackTraceContaining("ttl"));
    }

    @Test
    @DisplayName("non-positive bounds fail the boot")
    void nonPositiveBoundsFailTheBoot() {
        runner("streampulse.feed.timeline.max-length=0")
                .run(context -> assertThat(context).getFailure().hasStackTraceContaining("maxLength"));
        runner("streampulse.feed.read.max-celebrities=-1")
                .run(context -> assertThat(context).getFailure().hasStackTraceContaining("maxCelebrities"));
        runner("streampulse.feed.follower-page-size=0")
                .run(context -> assertThat(context).getFailure().hasStackTraceContaining("followerPageSize"));
    }

    @Test
    @DisplayName("a missing section fails the boot")
    void missingSectionFailsTheBoot() {
        new ApplicationContextRunner()
                .withUserConfiguration(Props.class)
                .withPropertyValues("streampulse.feed.watch-topic=x")
                .run(context -> assertThat(context).hasFailed());
    }
}
