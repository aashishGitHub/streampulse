/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.ingest.adapter.out.redis;

import com.streampulse.ingest.domain.IdempotencyStore;
import com.streampulse.platform.domain.EventId;
import com.streampulse.platform.domain.TenantId;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Redis-backed idempotency, using {@code SET NX EX}.
 *
 * <h2>Why SET NX EX and not GET-then-SET</h2>
 *
 * <pre>
 *   ❌  if (!redis.hasKey(k)) { redis.set(k); publish(); }
 *
 *       thread A: hasKey → false ┐
 *       thread B: hasKey → false ┘  both see absent
 *       thread A: set, publish
 *       thread B: set, publish      ← the event is now in Kafka TWICE
 *
 *   ✅  if (redis.setIfAbsent(k, "1", ttl)) { publish(); }
 *
 *       ONE round trip that tests and sets atomically on the server.
 *       Exactly one caller gets `true`. There is no window.
 * </pre>
 *
 * The bug in the first form is invisible in testing and appears in production as quietly
 * doubled metrics. {@code setIfAbsent} with a TTL maps to Redis {@code SET key val NX EX},
 * which is atomic because Redis executes commands one at a time.
 *
 * <h2>Why the TTL is not optional</h2>
 *
 * Without expiry the dedup set grows forever — one key per event, indefinitely. The TTL
 * bounds it and defines the deduplication window: a retry arriving after 24 hours is treated
 * as new. That is an acceptable trade, because a client retrying a day later has bigger
 * problems, and the alternative is an ever-growing Redis.
 *
 * <h2>Why failures do not throw</h2>
 *
 * A Redis outage must not stop telemetry. We return {@link ClaimResult#unavailable()} and the
 * use case fails open — publishing without deduplicating and incrementing a metric
 * (REQ-IG-14). Losing data is worse than duplicating it <em>here</em>; for a payments system
 * the correct choice is the opposite. See ADR-0007.
 */
public class RedisIdempotencyStore implements IdempotencyStore {

    private static final Logger log = LoggerFactory.getLogger(RedisIdempotencyStore.class);

    private final StringRedisTemplate redis;
    private final Duration ttl;

    public RedisIdempotencyStore(StringRedisTemplate redis, Duration ttl) {
        this.redis = redis;
        this.ttl = ttl;
    }

    @Override
    public ClaimResult claimAll(TenantId tenant, List<EventId> eventIds) {
        List<EventId> claimed = new ArrayList<>(eventIds.size());
        List<EventId> alreadySeen = new ArrayList<>();

        try {
            for (EventId id : eventIds) {
                // Key is namespaced by tenant, so one tenant's ids can never suppress
                // another's — that would be silent cross-tenant data loss.
                String key = id.idempotencyKey(tenant);

                Boolean isNew = redis.opsForValue().setIfAbsent(key, "1", ttl);

                // setIfAbsent returns a BOXED Boolean and can be null (for example inside a
                // pipeline or transaction, where the result is not yet available). Treating
                // null as false would silently drop events, so we treat it as "claimed" —
                // consistent with failing open.
                if (isNew == null || isNew) {
                    claimed.add(id);
                } else {
                    alreadySeen.add(id);
                }
            }
            return ClaimResult.of(claimed, alreadySeen);

        } catch (DataAccessException e) {
            // Spring translates every Redis/Lettuce failure into DataAccessException, so this
            // covers connection refused, timeouts and pool exhaustion alike.
            //
            // Log at WARN, not ERROR: this is a handled degradation, not an outage. Logging
            // it as ERROR would page someone for a condition the system is designed to absorb.
            log.warn("Idempotency store unavailable, failing open: {}", e.getMessage());
            return ClaimResult.unavailable();
        }
    }

    @Override
    public void release(TenantId tenant, List<EventId> eventIds) {
        if (eventIds.isEmpty()) {
            return;
        }
        try {
            // DELETE, not "set to expired". Removing the key is what makes the id claimable
            // again by the retry, which is the whole point.
            //
            // One round trip for the batch rather than one per id: this runs on the failure
            // path, which is exactly when the system is already under strain and least able
            // to afford N round trips.
            List<String> keys =
                    eventIds.stream().map(id -> id.idempotencyKey(tenant)).toList();
            redis.delete(keys);

        } catch (DataAccessException e) {
            // Swallowed on purpose. The caller is ALREADY throwing a publish failure; letting
            // this one escape would replace a precise error ("the broker rejected the batch")
            // with a misleading one ("Redis is down"), and the operator would debug the wrong
            // system.
            //
            // The cost of a failed release is bounded: those ids stay claimed until the TTL
            // expires, so that one retry is lost. Recorded at WARN so it is visible if it
            // ever becomes common rather than rare.
            log.warn(
                    "Could not release {} idempotency claims for tenant {}; a retry of these "
                            + "events will be treated as a duplicate until the TTL expires: {}",
                    eventIds.size(),
                    tenant.value(),
                    e.getMessage());
        }
    }
}
