/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.loadgen;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

public final class TokenProvider {

    private static final Duration REFRESH_MARGIN = Duration.ofSeconds(30);

    public record Token(String accessToken, long expiresInSeconds) {}

    @FunctionalInterface
    public interface Fetcher {
        Token fetch() throws IOException, InterruptedException;
    }

    private final Clock clock;
    private final Fetcher fetcher;
    private String accessToken;
    private Instant refreshAt = Instant.MIN;

    public TokenProvider(Clock clock, Fetcher fetcher) {
        this.clock = clock;
        this.fetcher = fetcher;
    }

    public synchronized String current() throws IOException, InterruptedException {
        if (accessToken == null || !clock.instant().isBefore(refreshAt)) {
            Token token = fetcher.fetch();
            accessToken = token.accessToken();
            refreshAt = clock.instant().plusSeconds(token.expiresInSeconds()).minus(REFRESH_MARGIN);
        }
        return accessToken;
    }
}
