/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.ingest.adapter.out.redis;

import com.streampulse.ingest.domain.RateLimiter;
import com.streampulse.platform.domain.TenantId;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * Distributed per-tenant rate limiting: a token bucket in Redis, refilled lazily, consumed
 * atomically by a Lua script.
 *
 * <h2>Why Lua and not three Redis calls</h2>
 *
 * The naive version is a race:
 *
 * <pre>
 *   ❌  tokens = GET bucket        both replicas read 5
 *       if (tokens >= 3)           both decide yes
 *       SET bucket (tokens - 3)    both write 2 — six tokens spent from a budget of five
 * </pre>
 *
 * Redis executes a script <b>atomically</b>: nothing else runs between its first and last
 * line. So read-decide-write becomes one indivisible operation, and the result is correct
 * no matter how many gateway replicas are calling at once.
 *
 * <p>This is the same insight as {@code SET NX EX} for idempotency: the fix for a
 * check-then-act race is not a faster check, it is making the whole thing one operation.
 *
 * <h2>Why lazy refill rather than a background job</h2>
 *
 * There is no timer. Each call computes how many tokens <em>would</em> have accrued since
 * the last one and adds them, capped at the bucket size:
 *
 * <pre>
 *   elapsed = now - lastRefill
 *   tokens  = min(capacity, tokens + elapsed * refillRatePerSecond)
 * </pre>
 *
 * A background refiller across thousands of tenants would be a scheduling problem and a
 * source of drift; lazy refill is stateless, exact, and costs nothing for idle tenants.
 *
 * <h2>Why a token bucket rather than a fixed window</h2>
 *
 * A fixed window ("1000 per minute") allows a double burst at the boundary: 1000 at 11:59:59
 * and another 1000 at 12:00:00. A token bucket smooths that — the burst is capped by the
 * bucket size, and the sustained rate by the refill rate. Two dials, each meaning something:
 * <b>capacity</b> is how big a spike you tolerate, <b>refill rate</b> is the long-run limit.
 *
 * <h2>Why distributed, not Resilience4j's RateLimiter</h2>
 *
 * Resilience4j's limiter is per JVM. With four replicas, a "1000/s" limit becomes 4000/s —
 * and it silently changes every time you scale. A tenant quota is a business fact, so it
 * must live somewhere shared. Resilience4j is still the right tool for protecting a
 * <em>downstream</em> callee from us; this protects <em>us</em> from a tenant.
 */
public class RedisTokenBucketRateLimiter implements RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RedisTokenBucketRateLimiter.class);

    /**
     * KEYS[1] = bucket key · ARGV[1] = capacity · ARGV[2] = refill/sec
     * ARGV[3] = now (ms) · ARGV[4] = permits requested · ARGV[5] = key TTL (sec)
     *
     * <p>Returns 1 if the permits were consumed, 0 otherwise.
     */
    private static final String TOKEN_BUCKET_SCRIPT =
            """
            local capacity   = tonumber(ARGV[1])
            local refillRate = tonumber(ARGV[2])
            local now        = tonumber(ARGV[3])
            local requested  = tonumber(ARGV[4])
            local ttl        = tonumber(ARGV[5])

            -- HMGET returns false for a key that does not exist yet: a new tenant starts
            -- with a FULL bucket, which is the friendly default.
            local state      = redis.call('HMGET', KEYS[1], 'tokens', 'updatedAt')
            local tokens     = tonumber(state[1])
            local updatedAt  = tonumber(state[2])

            if tokens == nil then
              tokens    = capacity
              updatedAt = now
            end

            -- LAZY REFILL: credit whatever accrued since the last call, capped at capacity.
            local elapsedSeconds = math.max(0, (now - updatedAt) / 1000.0)
            tokens = math.min(capacity, tokens + (elapsedSeconds * refillRate))

            local allowed = 0
            if tokens >= requested then
              tokens  = tokens - requested
              allowed = 1
            end

            redis.call('HMSET', KEYS[1], 'tokens', tokens, 'updatedAt', now)
            -- TTL so an idle tenant's bucket is reclaimed. Safe to lose: the bucket simply
            -- reappears full, which is the same state a long idle period would produce.
            redis.call('EXPIRE', KEYS[1], ttl)

            return allowed
            """;

    private final StringRedisTemplate redis;
    private final RedisScript<Long> script;
    private final long capacity;
    private final long refillPerSecond;
    private final long keyTtlSeconds;
    private final boolean failOpen;

    public RedisTokenBucketRateLimiter(
            StringRedisTemplate redis, long capacity, long refillPerSecond, long keyTtlSeconds, boolean failOpen) {
        this.redis = redis;
        this.script = new DefaultRedisScript<>(TOKEN_BUCKET_SCRIPT, Long.class);
        this.capacity = capacity;
        this.refillPerSecond = refillPerSecond;
        this.keyTtlSeconds = keyTtlSeconds;
        this.failOpen = failOpen;
    }

    @Override
    public boolean tryConsume(TenantId tenant, int permits) {
        String key = tenant.keyPrefix() + ":ratelimit";
        try {
            Long allowed = redis.execute(
                    script,
                    List.of(key),
                    String.valueOf(capacity),
                    String.valueOf(refillPerSecond),
                    String.valueOf(System.currentTimeMillis()),
                    String.valueOf(permits),
                    String.valueOf(keyTtlSeconds));
            return allowed != null && allowed == 1L;

        } catch (DataAccessException e) {
            // Same reasoning as the idempotency store (ADR-0007): Redis being down must not
            // stop telemetry. WARN, not ERROR — a handled degradation, not an outage.
            log.warn(
                    "Rate limiter unavailable for tenant {}, failing {}: {}",
                    tenant,
                    failOpen ? "open" : "closed",
                    e.getMessage());
            return failOpen;
        }
    }
}
