/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.kata.cache;

import java.util.HashMap;
import java.util.Map;

/**
 * LRU (Least Recently Used) cache with O(1) {@code get} and {@code put}.
 *
 * <h2>Why this exercise matters</h2>
 *
 * It is a confirmed interview question for this track, but more importantly it is the
 * only data structure most engineers are asked to <em>build</em> that they also
 * <em>operate</em>: every cache in front of a database is this, plus eviction policy and
 * metrics. In StreamPulse it becomes the title-metadata cache in {@code query-api}
 * (BS-02 / REQ-CC-03).
 *
 * <p><b>The interviewer is not testing whether you know {@code LinkedHashMap}.</b> Java
 * ships an LRU in three lines (see {@link #usingLinkedHashMap}), and reaching for it ends
 * the conversation. They want to see you reason about why <em>two</em> data structures are
 * needed.
 *
 * <h2>The core insight: one structure cannot do both jobs</h2>
 *
 * <pre>
 *   Requirement                          Structure that gives it in O(1)
 *   ─────────────────────────────────    ───────────────────────────────
 *   "find the value for key K"           HashMap
 *   "which key was used least recently"  ordered list
 *   "move an entry to most-recent"       DOUBLY linked list
 * </pre>
 *
 * A HashMap has no order. A list has no lookup. So we run both, and they point at the same
 * nodes:
 *
 * <pre>
 *   map:  {"a" → ●,  "b" → ●,  "c" → ●}
 *                │        │        │
 *                ▼        ▼        ▼
 *   head ⇄ [ c ] ⇄ [ b ] ⇄ [ a ] ⇄ tail
 *          MRU                LRU
 *          ▲                    ▲
 *          new + recently used  evicted first
 * </pre>
 *
 * <h2>Why the list must be DOUBLY linked</h2>
 *
 * On a cache hit we move a node to the front, which means unlinking it from wherever it
 * currently sits. To unlink a node you must reach its <em>predecessor</em>. In a singly
 * linked list finding the predecessor costs O(n) — which would make {@code get} O(n) and
 * defeat the whole point. The {@code prev} pointer is what buys O(1).
 *
 * <h2>Why sentinel (dummy) head and tail nodes</h2>
 *
 * Without them, every insert and remove needs null checks: "is this the first node? the
 * last? the only one?". With permanent, never-removed {@code head} and {@code tail}
 * sentinels, every real node is guaranteed to have both neighbours, and the link/unlink
 * code has <b>no special cases at all</b>. Two wasted objects buy a provably simpler
 * implementation — a trade worth making and worth explaining out loud.
 *
 * <h2>Complexity</h2>
 *
 * <pre>
 *   get  O(1)    map lookup + constant-time relink
 *   put  O(1)    map insert + constant-time relink (+ constant-time eviction)
 *   space O(capacity)
 * </pre>
 *
 * <h2>Thread safety</h2>
 *
 * <b>This class is NOT thread-safe.</b> That is deliberate — see
 * {@link ThreadSafeLruCache} for the concurrent variant and, more importantly, for why a
 * <em>read</em> needs the write lock here.
 *
 * @param <K> key type
 * @param <V> value type
 */
public class LruCache<K, V> {

    /**
     * A doubly linked list node. Private and static: it is an implementation detail, and
     * static because it does not need a reference to the enclosing cache (a non-static
     * inner class would silently retain one, which is a real memory-leak source).
     */
    private static final class Node<K, V> {
        final K key; // kept so eviction can remove the entry from the map
        V value;
        Node<K, V> prev;
        Node<K, V> next;

        Node(K key, V value) {
            this.key = key;
            this.value = value;
        }
    }

    private final int capacity;
    private final Map<K, Node<K, V>> map;

    /** Sentinels. {@code head.next} is the most recently used; {@code tail.prev} the least. */
    private final Node<K, V> head = new Node<>(null, null);

    private final Node<K, V> tail = new Node<>(null, null);

    public LruCache(int capacity) {
        if (capacity <= 0) {
            // Fail loudly at construction. A zero-capacity cache that silently evicts
            // everything is the kind of bug that looks like "the cache isn't working".
            throw new IllegalArgumentException("capacity must be > 0, was " + capacity);
        }
        this.capacity = capacity;
        // Size the map so it never resizes: HashMap grows at load factor 0.75, and a
        // resize rehashes every entry. Small detail, free to get right.
        this.map = new HashMap<>((int) (capacity / 0.75f) + 1, 0.75f);
        head.next = tail;
        tail.prev = head;
    }

    /**
     * Returns the value for {@code key}, or {@code null} if absent, and marks the entry as
     * most recently used.
     *
     * <p><b>Note the side effect.</b> A "read" mutates the list. That is what makes the
     * thread-safe version interesting: this is not a read-only operation, so a read lock
     * would not be enough.
     */
    public V get(K key) {
        Node<K, V> node = map.get(key);
        if (node == null) {
            return null;
        }
        moveToFront(node); // the access is what makes it "recently used"
        return node.value;
    }

    /** Inserts or updates {@code key}, evicting the least recently used entry if full. */
    public void put(K key, V value) {
        Node<K, V> existing = map.get(key);

        if (existing != null) {
            // Update in place. Do NOT create a new node — the map would then point at a
            // node that is no longer in the list, and the two structures would diverge.
            existing.value = value;
            moveToFront(existing);
            return;
        }

        // Evict BEFORE inserting, so size never exceeds capacity even momentarily.
        if (map.size() == capacity) {
            Node<K, V> lru = tail.prev; // the node just before the tail sentinel
            unlink(lru);
            map.remove(lru.key); // this is why Node keeps its key
        }

        Node<K, V> node = new Node<>(key, value);
        map.put(key, node);
        linkAtFront(node);
    }

    /** Number of entries currently held. */
    public int size() {
        return map.size();
    }

    /** True if {@code key} is present. Does NOT count as an access — order is unchanged. */
    public boolean containsKey(K key) {
        return map.containsKey(key);
    }

    // ---------------------------------------------------------------------
    // List surgery. Each of these is O(1) and, thanks to the sentinels, has
    // no null checks and no special cases.
    // ---------------------------------------------------------------------

    /**
     * Removes {@code node} from the list by connecting its neighbours to each other.
     *
     * <pre>
     *   before:   [prev] ⇄ [node] ⇄ [next]
     *   after:    [prev] ⇄⇄⇄⇄⇄⇄⇄⇄⇄ [next]
     * </pre>
     */
    private void unlink(Node<K, V> node) {
        node.prev.next = node.next;
        node.next.prev = node.prev;
    }

    /**
     * Inserts {@code node} immediately after the head sentinel (the MRU position).
     *
     * <pre>
     *   before:   head ⇄ [first] ...
     *   after:    head ⇄ [node] ⇄ [first] ...
     * </pre>
     *
     * Order matters: wire the new node's pointers before rewiring the neighbours, or you
     * will overwrite the reference you still need.
     */
    private void linkAtFront(Node<K, V> node) {
        node.prev = head;
        node.next = head.next;
        head.next.prev = node;
        head.next = node;
    }

    /** Unlink then re-link at the front. The whole "recently used" mechanic, in two calls. */
    private void moveToFront(Node<K, V> node) {
        unlink(node);
        linkAtFront(node);
    }

    /**
     * Returns keys from most- to least-recently-used. Test-only: it walks the list, so it
     * is O(n) and exists purely so tests can assert on eviction ORDER rather than only on
     * what survived.
     */
    java.util.List<K> keysMruToLru() {
        var keys = new java.util.ArrayList<K>(map.size());
        for (Node<K, V> n = head.next; n != tail; n = n.next) {
            keys.add(n.key);
        }
        return keys;
    }

    /**
     * The three-line version, for contrast.
     *
     * <p>{@code LinkedHashMap} with {@code accessOrder=true} maintains access order and
     * calls {@code removeEldestEntry} after each insert. It is the right choice in
     * production code and the wrong answer in an interview, because it demonstrates
     * nothing about your understanding.
     *
     * <p>Know it, mention it, then implement the real thing:
     * <em>"In production I'd use LinkedHashMap or Caffeine — let me build it out so you
     * can see the mechanics."</em>
     */
    public static <K, V> Map<K, V> usingLinkedHashMap(int capacity) {
        return new java.util.LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > capacity;
            }
        };
    }
}
