/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.query.adapter.out.redis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.streampulse.query.application.LatestMetricsPort;
import com.streampulse.query.domain.LatestMetric;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import reactor.core.publisher.Flux;

/**
 * Reads the hash {@code sp:latest:{tenant}} (hot-store contract). The reactive client is used from a
 * blocking port: {@code block} is legal because the caller is a worker of the bounded executor, never
 * an event-loop thread, and the timeout bounds how long that worker can be held.
 *
 * <p>Reads the whole tenant hash and filters in memory; a tenant with very many series should move to
 * a per-title key. Not done: not needed at this scale.
 */
public final class RedisLatestMetrics implements LatestMetricsPort {

    private static final Logger LOG = LoggerFactory.getLogger(RedisLatestMetrics.class);

    private final ReactiveStringRedisTemplate redis;
    private final ObjectMapper json;
    private final String keyPrefix;
    private final Duration timeout;

    public RedisLatestMetrics(
            ReactiveStringRedisTemplate redis, ObjectMapper json, String keyPrefix, Duration timeout) {
        this.redis = redis;
        this.json = json;
        this.keyPrefix = keyPrefix;
        this.timeout = timeout;
    }

    @Override
    public List<LatestMetric> latest(String tenantId, String titleId) {
        List<LatestMetric> latest = redis.<String, String>opsForHash()
                .entries(keyPrefix + tenantId)
                .flatMap(entry -> Flux.fromStream(parse(entry, titleId).stream()))
                .collectList()
                .block(timeout);
        return latest == null ? List.of() : latest;
    }

    /** Field is {@code title:region:metric}; parsed from the right because a title id may contain a colon. */
    private Optional<LatestMetric> parse(Map.Entry<String, String> entry, String wantedTitle) {
        String field = entry.getKey();
        int metricAt = field.lastIndexOf(':');
        int regionAt = metricAt <= 0 ? -1 : field.lastIndexOf(':', metricAt - 1);
        if (regionAt < 0) {
            LOG.warn("skipping malformed latest-metric field '{}'", field);
            return Optional.empty();
        }
        String title = field.substring(0, regionAt);
        if (!title.equals(wantedTitle)) {
            return Optional.empty();
        }
        try {
            JsonNode value = json.readTree(entry.getValue());
            return Optional.of(new LatestMetric(
                    title,
                    field.substring(regionAt + 1, metricAt),
                    field.substring(metricAt + 1),
                    Instant.ofEpochMilli(value.required("windowStart").asLong()),
                    Instant.ofEpochMilli(value.required("windowEnd").asLong()),
                    value.required("value").asDouble(),
                    value.required("sampleCount").asLong(),
                    Instant.ofEpochMilli(value.required("computedAt").asLong())));
        } catch (Exception malformed) {
            LOG.warn("skipping unreadable latest-metric value for field '{}': {}", field, malformed.toString());
            return Optional.empty();
        }
    }
}
