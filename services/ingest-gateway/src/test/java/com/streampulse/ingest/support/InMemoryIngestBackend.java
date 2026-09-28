/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.ingest.support;

import com.streampulse.ingest.domain.EventPublisher;
import com.streampulse.ingest.domain.IdempotencyStore;
import com.streampulse.ingest.domain.RateLimiter;
import com.streampulse.platform.domain.EventId;
import com.streampulse.platform.domain.TenantId;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Replaces the Redis and Kinesis adapters with in-memory ones, so a test can boot the whole
 * application and still start exactly one container.
 *
 * <h2>Why a security test should not also run moto and Redis</h2>
 *
 * {@code IngestEndToEndIT} already proves the Kinesis round trip and Redis {@code SET NX EX}
 * against the real things. Running them again here would add roughly ten seconds per run to
 * re-prove something, and would give a Keycloak failure three plausible-looking suspects.
 *
 * <p>A test with one container has one reason to fail. That is worth more than the fidelity
 * being given up, because the fidelity is already covered elsewhere.
 *
 * <h2>Why @Primary rather than @MockitoBean</h2>
 *
 * {@code IngestConfig} still defines its real beans, so its wiring — including the
 * circuit-breaker decorator — is exercised as written. Only the outermost edge is swapped.
 * A mock would also have to be told how to behave, and the assertion "the tenant on the
 * published record is streamflix" would then be asserting the stub.
 *
 * <p>Distinct from {@code FakeAdapters} in the application package, which models failure
 * modes for the unit suite. This one has no knobs on purpose: it records, and that is all.
 */
@TestConfiguration
public class InMemoryIngestBackend {

    /** Everything that reached the publisher, in order, with the tenant it was published as. */
    public static final class RecordingPublisher implements EventPublisher {

        private final List<TenantScopedEvents> batches = new CopyOnWriteArrayList<>();

        @Override
        public CompletableFuture<Void> publishAll(TenantScopedEvents events) {
            batches.add(events);
            return CompletableFuture.completedFuture(null);
        }

        public List<TenantScopedEvents> batches() {
            return List.copyOf(batches);
        }

        /** The tenant of the most recent batch — the claim the security tests care about. */
        public String lastTenant() {
            if (batches.isEmpty()) {
                throw new AssertionError("nothing was published");
            }
            return batches.get(batches.size() - 1).tenantId();
        }

        public void clear() {
            batches.clear();
        }
    }

    @Bean
    @Primary
    RecordingPublisher recordingPublisher() {
        return new RecordingPublisher();
    }

    /**
     * {@code Set.add} on a concurrent key set is atomic and returns false when the element was
     * already present — the same contract Redis {@code SET NX} provides, which is why this is
     * a fake and not a stub.
     */
    @Bean
    @Primary
    IdempotencyStore inMemoryIdempotencyStore() {
        Set<String> seen = ConcurrentHashMap.newKeySet();
        return new IdempotencyStore() {
            @Override
            public ClaimResult claimAll(TenantId tenant, List<EventId> eventIds) {
                List<EventId> claimed = new java.util.ArrayList<>();
                List<EventId> already = new java.util.ArrayList<>();
                for (EventId id : eventIds) {
                    // Namespaced by tenant, matching the production key layout.
                    if (seen.add(tenant.value() + ":" + id.value())) {
                        claimed.add(id);
                    } else {
                        already.add(id);
                    }
                }
                return ClaimResult.of(claimed, already);
            }

            @Override
            public void release(TenantId tenant, List<EventId> eventIds) {
                eventIds.forEach(id -> seen.remove(tenant.value() + ":" + id.value()));
            }
        };
    }

    /** No quota here: a 429 would make a security test fail for a reason that is not security. */
    @Bean
    @Primary
    RateLimiter unlimitedRateLimiter() {
        return RateLimiter.allowAll();
    }
}
