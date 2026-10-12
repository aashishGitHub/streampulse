/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.loadgen;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;

/** Not thread-safe unless the injected RandomGenerator is; the runner builds batches on one thread. */
public final class BatchBuilder {

    private static final String[] DEVICE_TYPES = {"WEB", "MOBILE_IOS", "MOBILE_ANDROID", "SMART_TV", "SET_TOP_BOX"};
    private static final String[] REGIONS = {"us-east-1", "us-west-2", "eu-west-1", "ap-south-1"};
    private static final int TITLES = 50;
    private static final int LATE_MIN_MILLIS = 1_000;
    private static final int LATE_MAX_MILLIS = 4_000;

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Clock clock;
    private final RandomGenerator random;
    private final Supplier<UUID> uuids;
    private final String[] sessionPool;
    private final double lateFraction;

    public BatchBuilder(Clock clock, RandomGenerator random, Supplier<UUID> uuids, int sessions, double lateFraction) {
        this.clock = clock;
        this.random = random;
        this.uuids = uuids;
        this.lateFraction = lateFraction;
        this.sessionPool = new String[sessions];
        for (int i = 0; i < sessions; i++) {
            sessionPool[i] = "sess-loadgen-" + i;
        }
    }

    public List<LoadEvent> events(int count) {
        if (count < 1 || count > LoadGenConfig.MAX_BATCH) {
            throw new IllegalArgumentException("batch must hold 1.." + LoadGenConfig.MAX_BATCH + " events");
        }
        Instant now = clock.instant();
        List<LoadEvent> events = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Instant occurredAt = random.nextDouble() < lateFraction
                    ? now.minusMillis(LATE_MIN_MILLIS + random.nextInt(LATE_MAX_MILLIS - LATE_MIN_MILLIS + 1))
                    : now;
            events.add(new LoadEvent(
                    uuids.get().toString(),
                    sessionPool[random.nextInt(sessionPool.length)],
                    "title-" + random.nextInt(TITLES),
                    DEVICE_TYPES[random.nextInt(DEVICE_TYPES.length)],
                    REGIONS[random.nextInt(REGIONS.length)],
                    occurredAt.toString(),
                    eventType()));
        }
        return events;
    }

    public byte[] buildJson(int count) {
        try {
            return JSON.writeValueAsBytes(new Batch(events(count)));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("could not serialise batch", e);
        }
    }

    private String eventType() {
        int roll = random.nextInt(100);
        if (roll < 80) {
            return "HEARTBEAT";
        }
        if (roll < 90) {
            return "SESSION_START";
        }
        return roll < 95 ? "BUFFERING_START" : "ERROR";
    }

    public record Batch(List<LoadEvent> events) {}
}
