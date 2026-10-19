# Phase 12 — Activity feed: Design

The system-design write-up (numbers, alternatives, scaling) is `docs/feed-design.md`; the
decision record is ADR-0021. This file is the flow of control, the data flow, and the failure
modes of the code in `services/activity-feed`.

## Flow of control

### Write path (asynchronous, replayable)

```
client ── POST /v1/watching {userId, titleId, eventId?} ──▶ WatchController     [request thread]
                                                              │ validate (Bean Validation + UserId/TitleId)
                                                              ▼
                                                           WatchService  assigns eventId (if absent) and occurredAt (Clock)
                                                              │
                                                              ▼
                                                           WatchPublisher ─▶ activity.watch.v1  (key = userId, 6 partitions)
                                                              │  waits for the broker ack (acks=all)
   ◀──────────────── 202 {eventId}   |   503 problem+json ────┘  broker unavailable: NOT accepted


activity.watch.v1 ─▶ WatchEventListener.onBatch(≤500 records, Acknowledgment)    [listener thread, 3 consumers]
   │  decode each ── undecodable/invalid ──▶ activity.watch.v1.dlt (original bytes + key + dlt-reason)
   │  FanOutService.handle(event)                                    ← synchronous, same thread
   │     1. count    = FollowGraph.followerCount(author)             Postgres, one PK read
   │     2. strategy = FanOutPolicy.strategyFor(count)               pure: count >= threshold ⇒ ON_READ
   │     3. TimelineStore.appendToOutbox(entry)                      ALWAYS; ZADD + trim + TTL
   │     4. ON_WRITE only: page through followers (keyset, 500 per page)
   │           TimelineStore.pushToTimelines(page, entry)            ONE pipelined round trip per page
   │  ack.acknowledge()                                              ← offsets commit HERE, never earlier
   ▼
failure anywhere above: throw ─▶ DefaultErrorHandler redelivers the whole poll with backoff;
the redelivery is absorbed by the member identity (below).
```

The Kafka consumer is not thread-safe and the work is synchronous, so the acknowledgement is on
the listener thread by construction. There is no batch writer here: unlike sink-writer the
unit of work is a fan-out, not a row, and one thread per partition keeps a user's events in
order.

### Read path (synchronous, no joins over content)

```
client ── GET /v1/feed?userId=&cursor=&limit= ──▶ FeedController
                                                     │ Bean Validation: ids, 1 ≤ limit ≤ 100
                                                     ▼
FeedService.read(user, cursorToken, limit)
   1. asOf     = cursor.asOf if a cursor was sent, else Clock.now           ← the SNAPSHOT instant
   2. followees = FollowGraph.followeesOf(user)    one indexed query: followee + its follower count
   3. celebrities = followees where FanOutPolicy says ON_READ  (capped at the N most followed)
   4. timeline = TimelineStore.timeline(user, asOf, 200)        ZREVRANGEBYSCORE ≤ asOf
      outbox   = TimelineStore.outboxes(celebrities, asOf, 50)  one pipelined round trip
   5. FeedMerger.merge(timeline, outbox, followedSet, asOf)
        drop: not followed now (stale after an unfollow), newer than asOf, duplicate entryId,
        and older watches of the same (author, title)
   6. Ranker.rank(candidates, asOf)            score = recency × affinity, total order
   7. FeedPager.page(ranked, cursor, limit)    keyset: strictly after (score, entryId)
   8. CursorCodec.encode(user, next)           HMAC-signed, bound to the user
```

The only SQL on the read path is step 2: a primary-key range scan over the READER's own follows
joined to a counter table by primary key. It is bounded by the number of accounts one person
follows, not by posts. The trap in SD2 is a join over content (follows ⋈ posts ORDER BY time),
which this does not do.

## Data flow and stores

### Postgres (schema `activity_feed`, Flyway)

```
follows(follower_id, followee_id, created_at)   PK (follower_id, followee_id)
                                                 INDEX follows_by_followee (followee_id, follower_id)
follower_counts(user_id PK, followers >= 0)     updated in the SAME transaction as the follow row
```

- PK `(follower_id, followee_id)`: uniqueness, and "whom does X follow" is a prefix scan.
- `(followee_id, follower_id)`: "who follows X" as a keyset scan, index-only, with a stable
  order so paging by `follower_id > :after` needs no OFFSET.
- The counter is written only when the follow row actually changed (`ON CONFLICT DO NOTHING`
  reports 0 rows for a repeat), so repeats and races do not drift it. It is a hot row for a
  very popular account; see docs/feed-design.md for the mitigation, which is not built.

### Redis (single node)

```
sp:feed:tl:{userId}    ZSET  score = event time (ms)   member = "{eventId}:{authorId}:{titleId}"   precomputed timeline
sp:feed:out:{authorId} ZSET  same score and member                                                  the author's outbox
```

- The member carries everything an entry needs, so the read path needs no second lookup.
- **Idempotency by event id.** The member contains the event id and the score is the event's
  own time, so a redelivered event `ZADD`s the same member with the same score: a no-op. There
  is no "seen" key, which would have a check-then-write window in which a crash loses the
  fan-out. Ids are restricted to `[A-Za-z0-9_-]{1,64}`, so `:` cannot occur inside one.
- Write: `ZADD`, `ZREMRANGEBYRANK key 0 -(max+1)` (keep the newest `max`), `EXPIRE`. Pipelined per
  page of followers. Not atomic across the three, which is fine: each is idempotent.
- No hash tags: no command touches two keys. Cluster implications are in docs/feed-design.md.

### Kafka

`activity.watch.v1`, key = `userId`, 6 partitions; Avro `WatchEvent{eventId, userId, titleId,
occurredAt}` via `AvroBinaryCodec` (ADR-0021 for Avro over JSON). Dead letters on
`activity.watch.v1.dlt` (3 partitions). Consumer group `activity-feed`, manual ack,
`read_committed`, cooperative-sticky.

## The ranking formula

```
age       = max(0, asOf − occurredAt)
recency   = 0.5 ^ (age / halfLife)                     halfLife = 30 min (target)
n         = number of entries on the same title among the merged candidates
            (one per followed author, after the per-(author,title) collapse)
affinity  = 1 + ln(n)                                  n = 1 ⇒ 1.0 ; n = 2 ⇒ 1.69 ; n = 10 ⇒ 3.30
score     = recency × affinity
order     = score descending, then entryId ascending   (a total order: entryId is unique)
```

Affinity is real and cheap because every candidate already comes from someone the reader
follows: "n of the people you follow are watching this" needs no extra lookup. It is NOT
personalised by the reader's history. `StrictMath` is used, not `Math`: a cursor stores a score
and the next page may be served by another JVM; `Math.pow` and `Math.log` may differ by an ulp
between the interpreter and the JIT, `StrictMath` is bit-exact.

## The cursor, and why not an offset

```
token = base64url( version | asOf | score(double bits) | entryId | HMAC-SHA256(secret, userId | payload) )
```

An offset ("skip the first 20") is a position in a list that changes between requests. When a
new entry ranks near the top, the entry that was 20th is now 21st: page 2 shows it again. When
an entry disappears, one is skipped. A keyset cursor says "strictly after THIS item in the
ordering" and is unaffected by what sits before it.

Ranking adds a second problem a plain time-ordered feed does not have: scores change with `now`
(decay) and with the candidate set (affinity). The cursor therefore also pins **asOf**, the
instant of the first page. Every later page ignores entries newer than `asOf` and computes
decay at `asOf`, so the candidate set and every score are the same on every page. New entries
show up when the client fetches afresh (a "new posts" refresh), which is how timelines behave
in practice.

What this does not give: if an entry that existed at `asOf` is REMOVED while paging (an unfollow,
a timeline trim at capacity, an expiry), the affinity of other entries on its title can change
and a later page can shift. A stateless cursor cannot prevent that; a server-side snapshot
could, at the cost of state. The requirement (REQ-AF-34) is about inserts, and says so.

The HMAC makes a tampered token detectable and binds it to a user, so one user's cursor cannot
be replayed as another's. It is integrity, not secrecy: the contents are readable.

## Hexagonal layout

```
domain/        UserId, TitleId, WatchStarted, FeedEntry, FanOutPolicy, FanOutStrategy, Ranker, RankedEntry,
               FeedMerger, FeedPager, FeedPage, Cursor, CursorCodec, Followee,
               FollowGraph (port), TimelineStore (port)                     ← JDK only; ArchUnit enforces it
application/   WatchService, FanOutService, FeedService, FollowService,
               WatchPublisher (port), DeadLetterPort, FeedMetrics (port)
adapter/in/web/       WatchController, FollowController, FeedController, ProblemAdvice
adapter/in/kafka/     WatchEventListener, WatchEventDecoder
adapter/out/kafka/    KafkaWatchPublisher, KafkaDeadLetterPublisher
adapter/out/postgres/ JdbcFollowGraph
adapter/out/redis/    RedisTimelineStore
config/        FeedConfig (composition root), FeedProperties (validated), MicrometerFeedMetrics
```

Spring MVC, not WebFlux: every call blocks on Postgres, Redis or Kafka, and virtual threads
(ADR-0006) make blocking cheap; WebFlux earns its keep for streaming (query-api's SSE), which
this service does not do.

## Failure modes

| Failure | Behaviour | Test that proves it |
|---|---|---|
| Follower count exactly at the threshold | celebrity: no follower view written | `FanOutPolicyTest.exactlyAtTheThresholdIsACelebrity`, `FanOutServiceTest.authorAtTheThresholdIsWrittenToTheOutboxOnly` |
| Celebrity watch | follower views untouched, entry still in the feed via the merge | `WatchFeedIT.aCelebrityWatchIsNotWrittenToFollowerTimelinesButAppearsInTheFeed` |
| Same event delivered twice | one entry per follower | `FanOutServiceTest.redeliveredEventLeavesOneEntryPerFollower`, `FeedEntryTest.entryIdChangesWithTheEventId`, `WatchFeedIT.theSameEventTwiceLeavesOneEntryPerFollower` |
| Crash between the view writes and the ack | redelivery, absorbed by the member identity | `WatchEventListenerTest.doesNotAckWhenTheFanOutFails`, `WatchFeedIT.theSameEventTwiceLeavesOneEntryPerFollower` |
| Fan-out cannot reach Redis or Postgres | listener throws, no ack, redelivery with backoff, nothing dead-lettered | `WatchEventListenerTest.doesNotAckWhenTheFanOutFails` |
| Undecodable or invalid record | dead-lettered with reason, next record processed | `WatchEventDecoderTest`, `WatchEventListenerTest.deadLettersARejectedRecordAndContinues`, `WatchFeedIT.anUndecodableRecordIsDeadLetteredAndTheNextOneIsStillProcessed` |
| Dead-letter topic unavailable | listener throws, no ack (acking would lose the record) | `WatchEventListenerTest.doesNotAckWhenTheDeadLetterFails` |
| Broker down at `POST /v1/watching` | 503 problem+json, never 202 | `WatchControllerTest.reports503WhenTheEventCannotBeRecorded`, `WatchServiceTest.propagatesAPublishFailure` |
| Invalid id, limit or body | 400 problem+json | `WatchControllerTest`, `FeedControllerTest`, `UserIdTest` |
| Repeated follow / unfollow | one row, count changes once | `JdbcFollowGraphIT.repeatedFollowCountsOnce`, `FollowServiceTest` |
| Concurrent identical follows | one row, count 1 | `JdbcFollowGraphIT.concurrentIdenticalFollowsCountOnce` |
| Self follow | rejected | `FollowServiceTest.aUserCannotFollowThemselves` |
| Unfollow with entries still in the view | filtered at read | `FeedServiceTest.anUnfollowedAuthorsStaleEntryIsNotReturned`, `WatchFeedIT.anUnfollowHidesTheStaleEntry` |
| New entries arrive between pages | no repeat, no skip | `FeedPagerTest.pagesPartitionTheListWithoutRepeatOrSkip`, `FeedServiceTest.pagesAcrossConcurrentInsertsWithoutRepeatOrSkip`, `WatchFeedIT.pagesAcrossConcurrentInsertsWithoutRepeatOrSkip` |
| Garbage, altered, foreign or oversized cursor | 400 problem+json | `CursorCodecTest`, `FeedServiceTest.anInvalidCursorIsRejected`, `FeedControllerTest.rejectsAnInvalidCursorWith400` |
| Reader follows very many celebrities | only the N most followed are merged | `FeedServiceTest.onlyTheMostFollowedCelebritiesAreMerged` |
| Author crosses the threshold | older entries are in both the view and the outbox; merged once | `FeedMergerTest.dropsADuplicateEntryId` |
| Timeline longer than the bound | oldest entries trimmed | `RedisTimelineStoreIT.trimsATimelineToItsBound` |
| Redis returns a member that does not parse | skipped, not fatal | `RedisTimelineStoreIT.skipsAMalformedMember` |
| Layers violated | build fails | `FeedArchitectureRulesTest` |
| Bad configuration | boot fails | `FeedPropertiesTest` |
| Context cannot assemble | caught with no Docker | `FeedWiringTest` |
