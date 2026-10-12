/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.loadgen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class TokenProviderTest {

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-12T10:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Test
    void fetchesOnceAndReusesTheTokenWhileItIsFresh() throws Exception {
        MutableClock clock = new MutableClock();
        AtomicInteger calls = new AtomicInteger();
        TokenProvider provider =
                new TokenProvider(clock, () -> new TokenProvider.Token("tok-" + calls.incrementAndGet(), 300));

        assertThat(provider.current()).isEqualTo("tok-1");
        clock.advance(Duration.ofSeconds(200));
        assertThat(provider.current()).isEqualTo("tok-1");
        assertThat(calls).hasValue(1);
    }

    @Test
    void refreshesBeforeExpiryUsingASafetyMargin() throws Exception {
        MutableClock clock = new MutableClock();
        AtomicInteger calls = new AtomicInteger();
        TokenProvider provider =
                new TokenProvider(clock, () -> new TokenProvider.Token("tok-" + calls.incrementAndGet(), 300));

        provider.current();
        clock.advance(Duration.ofSeconds(280));

        assertThat(provider.current()).isEqualTo("tok-2");
    }

    @Test
    void propagatesFetchFailureAndRetriesOnTheNextCall() throws Exception {
        MutableClock clock = new MutableClock();
        AtomicInteger calls = new AtomicInteger();
        TokenProvider provider = new TokenProvider(clock, () -> {
            if (calls.incrementAndGet() == 1) {
                throw new IOException("keycloak down");
            }
            return new TokenProvider.Token("ok", 300);
        });

        assertThatThrownBy(provider::current).isInstanceOf(IOException.class);
        assertThat(provider.current()).isEqualTo("ok");
    }
}
