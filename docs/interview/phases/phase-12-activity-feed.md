# Phase 12 — Activity feed (the Twitter-style design, implemented)

**Status:** core complete. 165 tests, 0 failures, all rerun fresh. Not built: engagement counters,
authentication, rate limits, inactive-user skipping, and **a real producer of the watch event** (the
event is synthetic in tests). Three mutation checks in the task list are **unverified** and left unticked.

**One line to say first:** "A hybrid fan-out feed: push each event into followers' Redis timelines for
ordinary authors, and pull from a separate outbox at read time for authors with huge follower counts."

## Code map

| Thing | Where |
|---|---|
| Push or pull decision | `services/activity-feed/src/main/java/com/streampulse/feed/domain/FanOutPolicy.java` |
| Write path (push in pages) | `services/activity-feed/src/main/java/com/streampulse/feed/application/FanOutService.java` |
| Read path (merge, page) | `services/activity-feed/src/main/java/com/streampulse/feed/application/FeedService.java`, `domain/FeedMerger.java`, `domain/Ranker.java` |
| Signed cursor | `services/activity-feed/src/main/java/com/streampulse/feed/domain/CursorCodec.java` |
| Follow graph (Postgres) | `services/activity-feed/src/main/java/com/streampulse/feed/adapter/out/postgres/JdbcFollowGraph.java` |
| Timelines and outboxes (Redis) | `services/activity-feed/src/main/java/com/streampulse/feed/adapter/out/redis/RedisTimelineStore.java` |
| Kafka entry point | `services/activity-feed/src/main/java/com/streampulse/feed/adapter/in/kafka/WatchEventListener.java` |
| Design and measured Redis memory | `docs/feed-design.md` |
| Decision | `docs/adr/0021-hybrid-fan-out-feed.md` |

---

## Beginner

### B1. What is fan-out on write versus fan-out on read?

**A strong answer contains**
- On write: when an author posts, copy it into each follower's timeline; reads are cheap, writes are
  expensive for popular authors.
- On read: keep one list per author and merge at read time; writes are cheap, reads are more expensive.
- The hybrid uses push for most authors and pull for the few with very large audiences.

**Open** `docs/adr/0021-hybrid-fan-out-feed.md`

### B2. Why a Redis sorted set for a timeline?

**A strong answer contains**
- A score (event time) gives ordering and range reads by score; adding the same member again just rewrites it,
  so replays are harmless. Trim to a maximum length and give the key an expiry.

**Open** `services/activity-feed/src/main/java/com/streampulse/feed/adapter/out/redis/RedisTimelineStore.java`

### B3. Why does the follow graph live in Postgres and not Redis?

**A strong answer contains**
- It is durable relational data with constraints: a primary key on (follower, followee), a check that nobody
  follows themselves, a count that cannot go negative, and an index by followee for the fan-out read.
- Redis holds the derived, rebuildable timelines.

**Open** `services/activity-feed/src/main/java/com/streampulse/feed/adapter/out/postgres/JdbcFollowGraph.java`

### B4. What is keyset (cursor) pagination, and why not offset?

**A strong answer contains**
- A cursor says "continue after this item"; with offsets, items inserted between requests shift the pages, so
  a reader sees repeats or skips.
- A test shows pages partition the feed without repeat or skip under concurrent inserts.

**Open** `services/activity-feed/src/main/java/com/streampulse/feed/domain/CursorCodec.java`

---

## Mid

### M1. How is a redelivered watch event made harmless?

**A strong answer contains**
- Idempotency is structural: the sorted-set member is built from the event id, author and title, so a replay
  re-adds the same member. A "seen" guard key was rejected because a crash between the guard and the
  fan-out would lose the fan-out.
- A mutation that drops idempotency is meant to turn duplicate tests red, but that check was **not rerun**.

**Open** `services/activity-feed/src/main/java/com/streampulse/feed/application/FanOutService.java`

### M2. Why sign the cursor?

**A strong answer contains**
- The cursor carries state the server trusts (as-of time, score, entry id). An HMAC bound to the user stops
  tampering and cross-user reuse, and is compared in constant time.
- Tests cover a tampered payload, a truncated token, trailing bytes, another user's cursor, a wrong secret, a
  short secret, an oversized token, and that the error does not echo the token.
- The default secret is for development only.

**Open** `services/activity-feed/src/test/java/com/streampulse/feed/domain/CursorCodecTest.java`

### M3. How does the follow counter stay correct under concurrency?

**A strong answer contains**
- An insert that does nothing on conflict, and the counter increments only when the row actually changed, in
  the same transaction. Tests run many threads following at once and the same follow requested many times.
- Weakness: the counter row is a hot row under bulk follows; shard it or batch increments.

**Open** `services/activity-feed/src/main/java/com/streampulse/feed/adapter/out/postgres/JdbcFollowGraph.java`

### M4. What happens at the push/pull boundary?

**A strong answer contains**
- Authors at or above the threshold (10,000 followers, a target) are pulled. The outbox is **always**
  written, so an author crossing the threshold stays correct on the read path.
- Not handled: hysteresis, so an author hovering around the threshold can flip between modes.

**Open** `services/activity-feed/src/main/java/com/streampulse/feed/domain/FanOutPolicy.java`

---

## Senior

### S1. Design the read path for a user who follows many celebrities.

**A strong answer contains**
- Merge the user's timeline with the outboxes of the most-followed authors they follow, capped (100 here),
  drop unfollowed authors, keep the newest entry per author and title, rank, then page.
- The cap bounds read cost but means a user following more than 100 celebrities sees a truncated feed.
- A hot celebrity outbox is a hot key; consider replicating or caching it.

**Open** `services/activity-feed/src/main/java/com/streampulse/feed/application/FeedService.java`

### S2. How would you measure whether the fan-out keeps up?

**A strong answer contains**
- Fan-out lag as a function of follower count, including at the threshold; Redis memory per entry (a measured
  jump when sorted sets switch encoding past 129 entries); pipeline round trips per 500 followers.
- **Not measured:** sustained timeline writes per shard and fan-out lag. The design doc's throughput figures
  are labelled "not measured".

**Open** `docs/feed-design.md`

### S3. What is missing before this could take real traffic?

**A strong answer contains**
- A real producer of the watch event (today nothing upstream emits it); authentication and rate limiting;
  inactive-user skipping so you do not push to people who never read; engagement counters.
- Redis Cluster behaviour of pipelined writes across slots is unverified.

**Open** `specs/12-activity-feed/tasks.md`

### S4. Why would you rather not trust the three unverified mutation checks?

**A strong answer contains**
- A passing test suite shows the code passes the tests, not that the tests would notice a regression. For
  always-push, offset-instead-of-cursor and dropped idempotency, the evidence the tests go red was not
  re-collected, so the claim stays open.
- That is the same discipline as elsewhere in the repo.

**Open** `specs/12-activity-feed/tasks.md`

---

## Say this honestly

- The watch signal has no real producer. No auth, no rate limits. The 10,000 threshold is a target.
- Mutation checks T12-21 to T12-23 are not verified.

## Stories and numbers

[Numbers](../06-numbers.md): activity feed test count.
