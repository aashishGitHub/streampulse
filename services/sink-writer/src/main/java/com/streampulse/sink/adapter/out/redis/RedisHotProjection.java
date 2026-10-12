/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.sink.adapter.out.redis;

import com.streampulse.sink.domain.MetricProjection;
import com.streampulse.sink.domain.MetricWindow;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.redis.connection.RedisStreamCommands.XAddOptions;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * The shapes in hot-store-contract.md: a hash of the latest window per series, and one capped
 * stream as the live feed. Both are derived and can be rebuilt from Postgres.
 *
 * <p>One pipelined round trip per batch. Not atomic, and it need not be: a half-applied
 * projection is repaired by the next write or a rebuild.
 */
public final class RedisHotProjection implements MetricProjection {

    static final String LATEST_KEY_PREFIX = "sp:latest:";
    static final String STREAM_KEY = "sp:agg:live";
    static final long STREAM_MAX_LENGTH = 10_000;

    private final StringRedisTemplate redis;

    public RedisHotProjection(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public void project(List<MetricWindow> windows) {
        Map<Series, MetricWindow> latest = latestPerSeries(windows);
        var trim = XAddOptions.maxlen(STREAM_MAX_LENGTH).approximateTrimming(true);

        redis.executePipelined(new SessionCallback<Object>() {
            @Override
            @SuppressWarnings("unchecked")
            public <K, V> Object execute(RedisOperations<K, V> operations) {
                var ops = (RedisOperations<String, String>) operations;
                latest.forEach((series, w) -> ops.opsForHash().put(series.hash(), series.field(), json(w)));
                for (MetricWindow w : windows) {
                    ops.opsForStream().add(STREAM_KEY, entry(w), trim);
                }
                return null;
            }
        });
    }

    /**
     * Within a batch the same series can appear for several windows; the hash keeps the latest
     * window, whatever the order the records arrived in.
     */
    private static Map<Series, MetricWindow> latestPerSeries(List<MetricWindow> windows) {
        var latest = new LinkedHashMap<Series, MetricWindow>();
        var order = Comparator.comparing(MetricWindow::windowStart).thenComparing(MetricWindow::computedAt);
        for (MetricWindow w : windows) {
            var series = new Series(LATEST_KEY_PREFIX + w.tenantId(), w.seriesField());
            latest.merge(series, w, (a, b) -> order.compare(a, b) >= 0 ? a : b);
        }
        return latest;
    }

    private record Series(String hash, String field) {}

    static String json(MetricWindow w) {
        // JSON has no NaN or Infinity; null is the honest encoding of "no usable number".
        String value = Double.isFinite(w.value()) ? Double.toString(w.value()) : "null";
        return "{\"windowStart\":" + w.windowStart().toEpochMilli()
                + ",\"windowEnd\":" + w.windowEnd().toEpochMilli()
                + ",\"value\":" + value
                + ",\"sampleCount\":" + w.sampleCount()
                + ",\"computedAt\":" + w.computedAt().toEpochMilli() + "}";
    }

    static Map<String, String> entry(MetricWindow w) {
        var fields = new LinkedHashMap<String, String>();
        fields.put("tenantId", w.tenantId());
        fields.put("titleId", w.titleId());
        fields.put("region", w.region());
        fields.put("metric", w.metric());
        fields.put("windowStart", Long.toString(w.windowStart().toEpochMilli()));
        fields.put("windowEnd", Long.toString(w.windowEnd().toEpochMilli()));
        fields.put("value", Double.toString(w.value()));
        fields.put("sampleCount", Long.toString(w.sampleCount()));
        fields.put("computedAt", Long.toString(w.computedAt().toEpochMilli()));
        return fields;
    }
}
