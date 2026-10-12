/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.query.adapter.out.redis;

import com.streampulse.query.adapter.in.sse.LiveFeed;
import com.streampulse.query.domain.EntryId;
import com.streampulse.query.domain.MetricUpdate;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.Limit;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.stream.StreamReceiver;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.util.retry.Retry;

/**
 * ONE reader of the shared stream per instance, fanned out to every client through a hot sink.
 * Cost scales with instances, not with connected clients (REQ-QA-10).
 */
public final class RedisLiveFeed implements LiveFeed {

    private static final Logger LOG = LoggerFactory.getLogger(RedisLiveFeed.class);
    private static final String BEGINNING = "0-0";

    private final ReactiveStringRedisTemplate redis;
    private final String streamKey;
    private final int replayLimit;
    private final boolean autoStart;
    private final StreamReceiver<String, MapRecord<String, String, String>> receiver;

    // directBestEffort: a subscriber that cannot keep up misses entries instead of stalling the reader or the
    // other clients. Each client's Coalescer, not this sink, is what keeps a slow client current.
    private final Sinks.Many<MetricUpdate> hot = Sinks.many().multicast().directBestEffort();
    private final AtomicReference<String> cursor = new AtomicReference<>();
    private final AtomicLong readFailures = new AtomicLong();
    private volatile Disposable reading;

    /**
     * @param pollTimeout how long one blocking read waits for new entries. MUST be shorter than the Redis command
     *     timeout: the read is a single command, so a block time at or above the timeout makes every idle poll
     *     fail and retry forever. The library default (2 s) equals this service's configured timeout.
     */
    public RedisLiveFeed(
            ReactiveStringRedisTemplate redis,
            String streamKey,
            int replayLimit,
            boolean autoStart,
            Duration pollTimeout) {
        this.redis = redis;
        this.streamKey = streamKey;
        this.replayLimit = replayLimit;
        this.autoStart = autoStart;
        this.receiver = StreamReceiver.create(
                redis.getConnectionFactory(),
                StreamReceiver.StreamReceiverOptions.builder()
                        .pollTimeout(pollTimeout)
                        .build());
    }

    public void start() {
        if (!autoStart || reading != null) {
            return;
        }
        reading = read().doOnNext(record -> cursor.set(record.getId().getValue()))
                .flatMap(record -> Mono.justOrEmpty(toUpdate(record)))
                .doOnNext(hot::tryEmitNext)
                .retryWhen(Retry.backoff(Long.MAX_VALUE, Duration.ofMillis(500))
                        .maxBackoff(Duration.ofSeconds(10))
                        .doBeforeRetry(signal -> {
                            readFailures.incrementAndGet();
                            LOG.warn(
                                    "live feed read failed, retrying: {}",
                                    signal.failure().toString());
                        }))
                .subscribe(update -> {}, failure -> LOG.error("live feed stopped", failure));
    }

    /** Failed reads of the shared stream since start. Non-zero on an idle feed means a misconfigured timeout. */
    public long readFailures() {
        return readFailures.get();
    }

    public void stop() {
        Disposable current = reading;
        if (current != null) {
            current.dispose();
        }
        hot.tryEmitComplete();
    }

    @Override
    public Flux<MetricUpdate> live() {
        return hot.asFlux();
    }

    @Override
    public Flux<MetricUpdate> replayAfter(EntryId after) {
        // Inclusive start, then drop the boundary entry ourselves: it does not depend on the server honouring
        // an exclusive range bound.
        return redis.<String, String>opsForStream()
                .range(
                        streamKey,
                        Range.rightUnbounded(Range.Bound.inclusive(after.toString())),
                        Limit.limit().count(replayLimit))
                .flatMap(record -> Mono.justOrEmpty(toUpdate(record)))
                .filter(update -> update.id().compareTo(after) > 0);
    }

    /**
     * Reads from an explicit cursor rather than "$" (newer than whenever the server handles the read): a
     * restart or a re-subscribe after an outage continues from the last entry seen, so nothing in between is skipped.
     */
    private Flux<MapRecord<String, String, String>> read() {
        return Flux.defer(() -> {
            String known = cursor.get();
            Mono<String> start = known != null ? Mono.just(known) : tailId();
            return start.flatMapMany(id -> receiver.receive(StreamOffset.create(streamKey, ReadOffset.from(id))));
        });
    }

    private Mono<String> tailId() {
        return redis.<String, String>opsForStream()
                .reverseRange(streamKey, Range.unbounded(), Limit.limit().count(1))
                .next()
                .map(record -> record.getId().getValue())
                .defaultIfEmpty(BEGINNING)
                .doOnNext(id -> cursor.compareAndSet(null, id));
    }

    /** A poison entry is skipped, never allowed to kill the shared reader. */
    private Optional<MetricUpdate> toUpdate(MapRecord<String, String, String> record) {
        Map<String, String> fields = record.getValue();
        try {
            return Optional.of(new MetricUpdate(
                    EntryId.parse(record.getId().getValue()),
                    required(fields, "tenantId"),
                    fields.getOrDefault("titleId", ""),
                    required(fields, "region"),
                    required(fields, "metric"),
                    Instant.ofEpochMilli(Long.parseLong(required(fields, "windowStart"))),
                    Instant.ofEpochMilli(Long.parseLong(required(fields, "windowEnd"))),
                    Double.parseDouble(required(fields, "value")),
                    Long.parseLong(required(fields, "sampleCount")),
                    Instant.ofEpochMilli(Long.parseLong(required(fields, "computedAt")))));
        } catch (RuntimeException malformed) {
            LOG.warn("skipping unreadable live-feed entry {}: {}", record.getId(), malformed.toString());
            return Optional.empty();
        }
    }

    private static String required(Map<String, String> fields, String name) {
        String value = fields.get(name);
        if (value == null) {
            throw new IllegalArgumentException("missing field " + name);
        }
        return value;
    }
}
