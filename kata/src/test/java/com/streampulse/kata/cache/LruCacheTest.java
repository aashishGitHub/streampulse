/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.kata.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link LruCache}.
 *
 * <h2>How to read this file</h2>
 *
 * Notice what is being asserted. The interesting tests are not "does it store a value" —
 * they are about <b>eviction order</b>, because that is the only part an LRU can get
 * subtly wrong. A cache that stores and retrieves correctly but evicts the wrong entry
 * still "works" in every smoke test and quietly destroys your hit rate in production.
 *
 * <p>{@code @Nested} groups tests by the state they share, so the class reads like a
 * specification rather than a pile of methods.
 */
class LruCacheTest {

    @Nested
    @DisplayName("basic storage")
    class BasicStorage {

        @Test
        void returnsStoredValue() {
            var cache = new LruCache<String, Integer>(2);
            cache.put("a", 1);
            assertThat(cache.get("a")).isEqualTo(1);
        }

        @Test
        void returnsNullForMissingKey() {
            var cache = new LruCache<String, Integer>(2);
            assertThat(cache.get("nope")).isNull();
        }

        @Test
        void updatingExistingKeyDoesNotGrowTheCache() {
            var cache = new LruCache<String, Integer>(2);
            cache.put("a", 1);
            cache.put("a", 2);

            assertThat(cache.size()).isEqualTo(1); // update, not insert
            assertThat(cache.get("a")).isEqualTo(2);
        }

        @Test
        void rejectsNonPositiveCapacity() {
            // Fail at construction, not silently at the first eviction.
            assertThatThrownBy(() -> new LruCache<String, Integer>(0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("capacity must be > 0");
        }
    }

    @Nested
    @DisplayName("eviction — the part that actually matters")
    class Eviction {

        @Test
        void evictsLeastRecentlyUsedWhenFull() {
            var cache = new LruCache<String, Integer>(2);
            cache.put("a", 1);
            cache.put("b", 2);
            cache.put("c", 3); // capacity exceeded → "a" is the LRU and goes

            assertThat(cache.get("a")).isNull();
            assertThat(cache.get("b")).isEqualTo(2);
            assertThat(cache.get("c")).isEqualTo(3);
        }

        @Test
        @DisplayName("a get() counts as a use and changes who gets evicted")
        void readingAnEntryProtectsItFromEviction() {
            var cache = new LruCache<String, Integer>(2);
            cache.put("a", 1);
            cache.put("b", 2);

            cache.get("a"); // ← "a" is now the most recently used, so "b" is the LRU

            cache.put("c", 3);

            assertThat(cache.get("a")).isEqualTo(1); // survived because we read it
            assertThat(cache.get("b")).isNull(); // evicted instead
        }

        @Test
        @DisplayName("put() on an existing key also counts as a use")
        void updatingAnEntryProtectsItFromEviction() {
            var cache = new LruCache<String, Integer>(2);
            cache.put("a", 1);
            cache.put("b", 2);

            cache.put("a", 99); // an update is an access too

            cache.put("c", 3);

            assertThat(cache.get("a")).isEqualTo(99);
            assertThat(cache.get("b")).isNull();
        }

        @Test
        @DisplayName("internal order is MRU → LRU, asserted directly")
        void maintainsExplicitRecencyOrder() {
            // Asserting the ORDER, not just which keys survived, is what catches an
            // off-by-one in the link/unlink code. Without this you can pass every other
            // test with a list that is subtly reversed.
            var cache = new LruCache<String, Integer>(3);
            cache.put("a", 1);
            cache.put("b", 2);
            cache.put("c", 3);
            assertThat(cache.keysMruToLru()).containsExactly("c", "b", "a");

            cache.get("a"); // "a" jumps to the front
            assertThat(cache.keysMruToLru()).containsExactly("a", "c", "b");

            cache.put("d", 4); // evicts "b", the tail
            assertThat(cache.keysMruToLru()).containsExactly("d", "a", "c");
        }

        @Test
        void capacityOfOneAlwaysHoldsOnlyTheNewest() {
            // The degenerate case. Sentinel nodes are what make this need no special code.
            var cache = new LruCache<String, Integer>(1);
            cache.put("a", 1);
            cache.put("b", 2);

            assertThat(cache.size()).isEqualTo(1);
            assertThat(cache.get("a")).isNull();
            assertThat(cache.get("b")).isEqualTo(2);
        }

        @Test
        void neverExceedsCapacityUnderHeavyChurn() {
            // An INVARIANT test: no matter the access pattern, size <= capacity.
            // Invariants hold under every input, which makes them far stronger evidence
            // than any single scripted scenario.
            var cache = new LruCache<Integer, Integer>(10);
            for (int i = 0; i < 1_000; i++) {
                cache.put(i, i);
                cache.get(i / 2); // a mix of hits and misses
                assertThat(cache.size()).isLessThanOrEqualTo(10);
            }
        }
    }

    @Nested
    @DisplayName("containsKey does not disturb recency")
    class ContainsKey {

        @Test
        void containsKeyIsNotAnAccess() {
            var cache = new LruCache<String, Integer>(2);
            cache.put("a", 1);
            cache.put("b", 2);

            cache.containsKey("a"); // a peek, deliberately NOT a use

            cache.put("c", 3);

            assertThat(cache.get("a")).isNull(); // still the LRU, still evicted
        }
    }

    @Nested
    @DisplayName("the LinkedHashMap shortcut behaves the same")
    class LinkedHashMapVariant {

        @Test
        void matchesOurImplementationsEvictionBehaviour() {
            // Proves the shortcut is equivalent — which is exactly why you must be able
            // to say "I know it exists, here is why I'm writing the real one anyway."
            var jdk = LruCache.<String, Integer>usingLinkedHashMap(2);
            jdk.put("a", 1);
            jdk.put("b", 2);
            jdk.get("a");
            jdk.put("c", 3);

            assertThat(jdk).containsOnlyKeys("a", "c");
        }
    }
}
