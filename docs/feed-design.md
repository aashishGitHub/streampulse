# Designing a feed: "friends are watching" (interview question SD2)

> "Design a Twitter-like platform: follows, a feed, a prioritized dashboard."

This is the system-design write-up behind `services/activity-feed` (Phase 12). The code is a
single-node, no-auth implementation of the core of the answer. This document says what the
answer is at scale, where the code stops, and what it does **not** do.

**Honesty rules for this document.** Every number below is an **assumption** or a **target**
unless it is marked *measured*. The only measured figures are the Redis memory sizes in
section 4, taken once from a `redis:7-alpine` container (Redis 7.4.11) on a laptop. Nothing here
is a production incident, and no figure is a claim about any real company's traffic. Real
follow graphs are heavily skewed; I do not have a verified source for the shape, so the skew
below is a stated assumption, not a fact.

The decision record is [ADR-0021](adr/0021-hybrid-fan-out-feed.md); the flow and failure modes
of the code are in [specs/12-activity-feed/design.md](../specs/12-activity-feed/design.md).

---

## 1. What the interviewer is testing

| Prompt word | What they want to hear | Where it is in the code |
|---|---|---|
| follows | a follow graph with the right indexes and a follower count | `V1__follow_graph.sql`, `JdbcFollowGraph` |
| a feed | fan-out on write into per-user lists for ordinary accounts | `FanOutService`, `RedisTimelineStore.pushToTimelines` |
| the celebrity problem | fan-out on read for accounts with millions of followers, merged at read time | `FanOutPolicy`, `FeedService`, `RedisTimelineStore.outboxes` |
| likes | async counters, not row updates on the hot path | **not built** (section 7) |
| a prioritized dashboard | a ranking step: recency, engagement, affinity | `Ranker` (recency and affinity; no engagement) |
| scale | cursor pagination, hot keys, consistency, failure | `Cursor`, `FeedPager`, sections 5 and 6 |

The two traps named in the question bank, and how this design avoids them:

- **Pure fan-out on write.** One celebrity post is millions of writes. Avoided by the
  threshold: the push is bounded by `celebrityThreshold` writes per event.
- **SQL joins at read time.** `follows JOIN events ORDER BY time LIMIT 20` costs more the more
  people you follow and the more they post, on the hot path. Avoided: events are never in SQL.
  The one read-path query lists *the reader's own* followees, joined to a counter table by
  primary key, so it is bounded by how many accounts one person follows.

## 2. Scope: what "friends are watching" means here

A user starts watching a title; their followers see "X is watching T". There are two entities,
users and titles, and one event, `WatchEvent{eventId, userId, titleId, occurredAt}`.

**An honest gap.** Nothing in StreamPulse's telemetry carries a user id: events have only a
`sessionId`. So nothing in the real pipeline emits this event. It is defined as its own
contract (`activity.watch.v1`, `api/asyncapi`) and the only producer today is
`POST /v1/watching` on this service. Connecting a real producer would need a session-to-user
mapping that does not exist in this repo.

## 3. Assumptions and the arithmetic they lead to

### 3.1 Assumptions (all of them guesses; change them and the conclusions scale)

| Quantity | Assumed value | Note |
|---|---|---|
| Registered users | 50 million | |
| Daily active users (DAU) | 10 million | 20% of registered |
| Watch starts per DAU per day | 3 | |
| Feed reads per DAU per day | 20 | |
| Peak to average | 5x | |
| Average follows per user | 200 | so about 10 billion follow edges |
| Celebrity threshold | 10,000 followers | a **target**, section 3.4 |
| Share of follow edges pointing at accounts at or above the threshold | 50% | **pure assumption** about skew |
| Celebrities followed per reader (average) | 5 | |
| Sustained timeline writes per Redis shard | 100,000 / s | **not measured**; Phase 9 would |

### 3.2 Rates

```
watch events      10M x 3 / 86,400 s            ≈   347 / s average     ≈ 1,740 / s at peak
feed reads        10M x 20 / 86,400 s           ≈ 2,315 / s average     ≈ 11,600 / s at peak
```

### 3.3 The three strategies, side by side

Writes assume authors are as likely to watch as anyone, so each event reaches the mean number
of followers of an author: edges / authors = 200.

| | Pure push | Pure pull | Hybrid (this design) |
|---|---|---|---|
| Timeline writes per second, average | 347 x 200 ≈ **69,000** | 0 | (1 - 0.5) x 69,000 ≈ **35,000** |
| Timeline writes per second, peak | ≈ 347,000 | 0 | ≈ 174,000 |
| Worst single event | **20,000,000** (one 20M-follower account) | 1 write | **10,000** (the threshold) |
| Outbox reads per feed read | 0 | 200 | about 5 (celebrities only), one pipelined round trip |
| Outbox reads per second, peak | 0 | 11,600 x 200 ≈ **2.3 million** | 11,600 x 5 ≈ **58,000** |

Redis commands: each timeline write is three commands (`ZADD`, `ZREMRANGEBYRANK`, `EXPIRE`),
so 35,000 writes per second is about 105,000 commands per second on average.

One celebrity event under pure push: 20,000,000 writes. At the assumed 100,000 writes per
second per shard and 8 shards, that is 20,000,000 / 800,000 = **25 seconds** of the whole
cluster's write capacity for one post, while ordinary traffic keeps arriving. Under the hybrid
it is one write.

The hybrid does not remove work, it moves the unbounded part to the one place where it is
cheap to bound: a handful of outbox reads per feed read.

### 3.4 Choosing the threshold

The threshold trades two costs:

- **Too high:** the biggest single push grows (threshold x 3 commands) and a burst of near-
  threshold authors posting together lengthens fan-out lag for everyone behind them.
- **Too low:** more accounts are pull accounts, so every reader pays more outbox reads, and a
  mid-sized account's outbox becomes a key read by a large share of readers.

10,000 is a target chosen so the largest push is 20 pages of 500 followers. It is
configuration (`streampulse.feed.celebrity-threshold`) precisely because the right number is
found by measuring fan-out lag and outbox-read cost, which Phase 9 has not done.

## 4. Memory (the one place there are measured numbers)

Measured once on `redis:7-alpine` (Redis 7.4.11, default configuration) with `MEMORY USAGE`
after writing entries through `RedisTimelineStore`. Member = `eventId:author:title`, 62 bytes
for a 12-character author and title.

| Entries in the sorted set | `MEMORY USAGE` | Per entry | Encoding |
|---|---|---|---|
| 1 | 160 B | 160 B | listpack |
| 10 | 832 B | 83 B | listpack |
| 100 | 8,256 B | 83 B | listpack |
| 128 | 10,304 B | 80 B | listpack |
| 129 | 21,844 B | 169 B | **skiplist** |
| 200 | 31,656 B | 158 B | skiplist |
| 500 | 84,904 B | 170 B | skiplist |

A sorted set is stored compactly (a listpack) while it has few entries *and* short members,
then converted to a skiplist plus hash, which costs about twice as much per entry. The
conversion point seen here is 129 entries, which matches Redis's documented default of 128
(`zset-max-listpack-entries`); the member-size limit is also 64 bytes by default
(`zset-max-listpack-value`). You may want to verify both against the Redis configuration docs
for your version. The member here is 62 bytes, just under it: a longer user or title id pushes
even a small timeline into the skiplist. Ids may be up to 64 characters in this service, so
that can happen.

### 4.1 Timelines

The default timeline length is 200 (target), so each full timeline is skiplist-encoded at about
**31.7 KB** (measured). A timeline fills fast: a user following 200 people who each watch about
3 titles a day receives about 600 entries a day.

```
active users only (10M):    10,000,000 x 31.7 KB ≈ 317 GB
every registered user (50M): 50,000,000 x 31.7 KB ≈ 1.6 TB
at length 128 (listpack):   10,000,000 x 10.3 KB ≈ 103 GB     (about 3x less, for 36% fewer entries)
```

Two levers matter more than any tuning of the above, and the second is not built:

1. Cap the length at the listpack threshold when 128 entries are enough for a page-deep feed.
2. **Do not fan out to inactive users.** This implementation writes to every follower. Skipping
   users who have not read their feed recently, and rebuilding their timeline on return, cuts
   the 5x between registered and active users. Not built.

### 4.2 Outboxes

An outbox is written for every event (ADR-0021 point 3) and expires in 7 days. With about 3
watches a day that is about 21 entries per active author, a listpack at about 80 B each:

```
10,000,000 authors x 21 entries x 80 B ≈ 17 GB
```

### 4.3 Total, and what it implies

About 320 GB at the default length, about 120 GB at 128. That does not fit one node, so a real
deployment shards by user id. See section 8 on cluster behaviour.

Postgres: 10 billion follow edges at an *estimated* 100 to 150 bytes each including the two
indexes is of the order of 1 to 1.5 TB. I have not measured this; verify with
`pg_relation_size`. It is too large for one instance to be comfortable, so at that scale the
graph is sharded by follower id (or lives in a purpose-built store). This implementation uses
one Postgres.

## 5. The design

### 5.1 Write path

```
POST /v1/watching ─▶ validate ─▶ Kafka activity.watch.v1 (key = userId, acks=all) ─▶ 202
                                      │
                                      ▼   consumer group "activity-feed", 3 threads, manual ack
                              FanOutService
                                 count = follower count (Postgres, one PK read)
                                 outbox ZADD (always)
                                 ON_WRITE: page through followers, pipelined ZADD + trim + EXPIRE
                              ack
```

- **Asynchronous and replayable.** The request returns once Kafka has the event. A fan-out of
  10,000 writes never sits on a request thread, and a bug in fan-out can be fixed and the topic
  replayed.
- **Key = userId.** One user's events stay in order on one partition. A "hot user" is a hot
  partition, which is fine: a person starts watching a title a few times an hour.
- **Ack after durable.** The offset is committed only after the Redis writes returned. A crash
  in between causes a redelivery, never a loss. At-least-once delivery plus an idempotent write
  is effectively-once.
- **Idempotency by event id.** The timeline member contains the event id and the score is the
  event's own time, so replaying an event writes the same member with the same score. No
  `seen` key, which would have a crash window between marking and writing.
- **Outage versus poison.** A Redis or Postgres outage makes the listener throw; the container
  redelivers with exponential backoff and never skips. An undecodable record is parked on
  `activity.watch.v1.dlt` with a reason. The two are never mixed up: an outage dead-letters
  nothing.

### 5.2 Read path

```
GET /v1/feed?userId=&cursor=&limit=
  followees = SELECT followee, its follower count FROM follows LEFT JOIN follower_counts   (PK scan)
  celebrities = followees at or above the threshold, the 100 most followed
  timeline = ZREVRANGEBYSCORE sp:feed:tl:{user} -inf asOf LIMIT 0 200
  outboxes = pipelined ZREVRANGEBYSCORE sp:feed:out:{celebrity} -inf asOf LIMIT 0 50
  merge  ─ drop authors no longer followed, entries newer than asOf, duplicates, older repeats
  rank   ─ recency decay x affinity
  page   ─ keyset after the cursor; sign the next cursor
```

### 5.3 Ranking (and what it becomes at scale)

```
score = 0.5 ^ (age / halfLife)  x  (1 + ln n)         halfLife = 30 min (target)
```

`n` is the number of entries on the same title among the candidates, each from a followed
author. It is "how many of the people you follow are watching this", real and free because the
candidates are already limited to followed authors. It is not personalised by the reader's
history, and there is no engagement term because nothing here records engagement.

At scale this becomes a pipeline, and I am describing a general pattern here, not any
particular company's system:

1. **Candidate generation**: the timeline, celebrity outboxes, and possibly recommendations.
2. **A light ranker**: this formula, to cut thousands of candidates to a few hundred cheaply.
3. **A heavy ranker**: a model scoring the survivors with features (the reader's history with
   the author and the title, engagement counts, time of day), trained offline on what readers
   actually watched or ignored.
4. **Re-ranking** for diversity (not ten entries about one title) and policy (blocks, mutes).

Pagination changes with that. A model's scores are not reproducible at an `asOf`, so the keyset
cursor stops working: instead the ranked list of ids is stored server-side for a few minutes
under a session id, and the cursor is a position in that stored list. The cost is state.

### 5.4 Cursor pagination: why not an offset

An offset is a position in a list that changes between requests. If a new entry ranks near the
top between page 1 and page 2, what was the 20th item is now the 21st, and page 2 shows it
again. If one disappears, an item is skipped.

The cursor here is `(asOf, score, entryId)`: "strictly after this entry in the ranking as it
stood at `asOf`". Every page ranks the same candidates at the same instant, because entries
newer than `asOf` are ignored and decay is computed at `asOf`. New entries appear when the
client fetches afresh.

It is signed (HMAC-SHA256) and bound to the user, so a tampered, truncated, foreign or garbage
token is a 400. The signature is integrity, not secrecy.

**What it does not guarantee.** It covers inserts, as the requirement says. If an entry that
existed at `asOf` is *removed* while a client pages (the author is unfollowed, the timeline is
trimmed at capacity, an entry expires), the affinity of other entries on the same title can
change and a later page can shift. Server-side snapshots fix that at the cost of state.

### 5.5 Likes and engagement (not built)

SD2 asks for likes "as async counters, not row updates on the hot path". Nothing here records
likes. The design, if it were built:

- `INCR sp:feed:likes:{entryId}` in Redis on each like: O(1), no Postgres row on the hot path.
- A flusher reads and resets a batch of counters every few seconds
  (`GETDEL` or `INCRBY` of the delta) and writes the totals to Postgres in one batched upsert.
- The ranker reads counts from Redis. A lost counter after a Redis failure loses at most one
  flush interval of likes, which is the honest trade for a like, and would not be for a payment.

## 6. Edge cases and consistency

| Case | Behaviour | Built? |
|---|---|---|
| A follower unfollows | Their timeline still holds the author's entries until trimmed or expired. The read path filters entries whose author is not in the reader's current followee set. | yes, tested |
| A follower re-follows | Entries still in the timeline reappear. A *new* follower of an ordinary author sees nothing from before they followed; a new follower of a celebrity sees the outbox's history at once. That asymmetry is inherent to the hybrid; a backfill on follow would remove it. | not built |
| An author crosses the threshold upward | Earlier entries sit in followers' timelines *and* in the outbox. The merge de-duplicates by entry id. | yes, tested |
| An author drops back below the threshold | Entries written only to the outbox while they were a celebrity are no longer read by anyone. No hysteresis. | not built |
| Follower count is stale at fan-out | The count picks the strategy; the followers are then read in keyset pages, so memory stays bounded by the page whatever the real number is. | yes |
| Redelivery, client retry, broker duplicate | One entry per follower (member identity). | yes, tested at three levels |
| A hot outbox | The most-followed celebrity's outbox is read by a large share of all feed reads: a hot key. Mitigations: an in-process cache of celebrity outboxes with a few seconds' TTL, read replicas, or splitting the key. | not built |
| The follower counter row | Updated in the follow transaction, so bulk-following one account serialises on one row. Mitigation: shard the counter, or `INCR` in Redis and flush in batches. | not built (concurrency of distinct followers is tested at 32) |
| A returning user whose timeline expired | They see only celebrity entries and what arrives next. A cold-start rebuild from followees' outboxes is the fix. | not built |
| Redis loses a timeline | Same as above: it is derived data, rebuildable in principle, not rebuilt here. | not built |
| Read-your-writes | `POST /v1/watching` is 202: the entry shows in followers' feeds after Kafka and the consumer, normally well under a second locally; no bound is measured or promised. | |

**Consistency model.** The write path is eventually consistent by design. A feed read is
consistent *within a pagination session* (the snapshot) but not across sessions. Counts and
classification are read at event time and read time and can disagree briefly.

## 7. What this implementation does NOT do

- **No authentication.** `followerId` is a query parameter; with auth it becomes the
  principal. Anyone can watch or follow as anyone.
- **No user directory.** Any well-formed id may follow or be followed.
- **No per-user rate limit** on any endpoint.
- **No engagement counters**, so no engagement term in the ranking (section 5.5 is the design).
- **No skipping of inactive users**, and no cold-start rebuild of an expired timeline.
- **No hysteresis** around the threshold; **no backfill** on follow.
- **Single Redis node, single Postgres, single Kafka broker** in the tests and locally.
- **No cache** in front of celebrity outboxes.
- **No real producer** of the watch event (section 2).
- **Numbers are targets**, none measured except section 4.

## 8. Redis Cluster implications

Keys are independent per user (`sp:feed:tl:{user}`, `sp:feed:out:{author}`) and no command
touches two keys, so no hash tags are needed and keys spread evenly by user. What changes:

- **Pipelines.** Followers of one author hash to different slots. A single pipelined batch
  across slots is not valid in cluster mode; the batch has to be grouped by slot (or node) and
  sent per node. I am not certain how `StringRedisTemplate.executePipelined` behaves against a
  cluster connection in this Spring Data Redis version; VERIFY before relying on it. The
  batching code in `RedisTimelineStore` would need a grouping step.
- **Hot keys.** Per-user timelines distribute well; celebrity outboxes do not (section 6).
- **Resharding** moves slots; the data is derived and has a TTL, so a lost timeline degrades to
  the cold-start case rather than to data loss.

## 9. How it is tested, and what each test cannot prove

- **Pure classes** (`FanOutPolicy`, `Ranker`, `FeedMerger`, `FeedPager`, `CursorCodec`):
  unit tests with no containers, including the threshold boundary at exactly the threshold,
  cursor walks that partition a list for several page sizes, every single-byte tamper of a
  cursor, and ties.
- **Services against in-memory fakes**: fan-out (boundary, duplicate, paging), feed read (merge,
  unfollow, concurrent inserts, celebrity cap).
- **Adapters against real stores** (`*IT`, Testcontainers: Kafka 3.9.1, Postgres 16, Redis 7):
  follow graph (race, keyset, index use), timeline store (trim, TTL, idempotency, a malformed
  member), and the whole service through HTTP.
- **Not proven:** behaviour under a mid-fan-out crash or an infrastructure outage (the
  injected-failure paths are unit-tested; the idempotency that makes a crash safe is what the
  duplicate test exercises), any latency or throughput figure, multi-node Redis, and anything
  about a real traffic shape.

## 10. Running it

```
make up-core                                  # Kafka, Postgres, Redis (docker/compose.yml, profile core)
./gradlew :services:activity-feed:bootRun     # :8087

curl -s -XPOST 'localhost:8087/v1/users/alice/follow?followerId=bob'
curl -s -XPOST localhost:8087/v1/watching -H 'content-type: application/json' \
     -d '{"userId":"alice","titleId":"dune"}'
curl -s 'localhost:8087/v1/feed?userId=bob&limit=10'
curl -s  localhost:8087/v1/users/alice/followers
```

The topics must exist (the compose broker has auto-create off). Lines for `kafka-init` in
`docker/compose.yml`, following the existing ones:

```
$$T --bootstrap-server $$B --create --if-not-exists --topic activity.watch.v1 --partitions 6 --replication-factor 1
$$T --bootstrap-server $$B --create --if-not-exists --topic activity.watch.v1.dlt --partitions 3 --replication-factor 1
```
