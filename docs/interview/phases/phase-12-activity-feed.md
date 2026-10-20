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

**Answer:** Fan-out on write means that when an author posts, I copy the entry into every follower's timeline straight away. Reads are then cheap, because a reader's feed is already one list, but writes are expensive for a popular author. Fan-out on read keeps one list per author and merges the lists of everyone you follow at read time, so writes are cheap and reads cost more. My service is a hybrid. It pushes for most authors and pulls for the few whose follower count is at or above a threshold. In both cases the entry is always written to the author's own outbox, so the read path can find it.

**Example:** `services/activity-feed/src/main/java/com/streampulse/feed/application/FanOutService.java:40-46`
```java
FanOutStrategy strategy = policy.strategyFor(graph.followerCount(watch.userId()));

// Always, whichever strategy applies: an author who crosses the threshold later still has
// the entries the read path now has to find.
store.appendToOutbox(entry);

int written = strategy == FanOutStrategy.ON_WRITE ? pushToFollowers(watch.userId(), entry) : 0;
```
The strategy is chosen per event from the follower count, and the push is skipped when it is `ON_READ`.

**Code:** `services/activity-feed/src/main/java/com/streampulse/feed/application/FanOutService.java:38-49` (the write path), `docs/adr/0021-hybrid-fan-out-feed.md:11-17` (the two strategies and where each breaks)

### B2. Why a Redis sorted set for a timeline?

**Answer:** A sorted set gives me ordering and range reads by score for free, and I use the event time in milliseconds as the score. Adding the same member again rewrites that member, so a replayed event cannot create a duplicate. After each add I trim the set to a maximum length, keeping the newest entries, and I refresh the key's expiry. Those three commands are not atomic together. I accepted that because each of them is idempotent, so running the whole write again after a failure is safe.

**Example:** `services/activity-feed/src/main/java/com/streampulse/feed/adapter/out/redis/RedisTimelineStore.java:91-99`
```java
/**
 * ZADD, then keep only the newest {@code max} (ranks run oldest-first, so 0..-(max+1) is everything
 * but the top {@code max}), then refresh the expiry. Not atomic across the three, and each is idempotent.
 */
private static void write(RedisOperations<String, String> ops, String key, FeedEntry entry, int max, Duration ttl) {
    ops.opsForZSet().add(key, entry.member(), entry.score());
    ops.opsForZSet().removeRange(key, 0, -(max + 1L));
    ops.expire(key, ttl.toSeconds(), TimeUnit.SECONDS);
}
```
The three calls are the add, the trim and the expiry, and the comment states why not being atomic is acceptable.

**Code:** `services/activity-feed/src/main/java/com/streampulse/feed/adapter/out/redis/RedisTimelineStore.java:24-34` (the key layout), `services/activity-feed/src/test/java/com/streampulse/feed/adapter/out/redis/RedisTimelineStoreIT.java:62-78` (the idempotency test)

### B3. Why does the follow graph live in Postgres and not Redis?

**Answer:** The follow graph is durable relational data with rules that the database can enforce, and Redis holds only derived timelines that can be rebuilt in principle. The table has a primary key on follower and followee, a check that nobody follows themselves, and a counter table whose count cannot go negative. There is also an index by followee, so the fan-out can read "who follows this author" by keyset, index-only. A test checks that the constraints hold without the application in front of them. I would not put the graph in Redis, because a lost key would lose the source of truth, not a cache.

**Example:** `services/activity-feed/src/main/resources/db/migration/V1__follow_graph.sql:5-21`
```sql
CREATE TABLE follows (
    follower_id text        NOT NULL,
    followee_id text        NOT NULL,
    created_at  timestamptz NOT NULL,
    PRIMARY KEY (follower_id, followee_id),
    CHECK (follower_id <> followee_id)
);
...
CREATE INDEX follows_by_followee ON follows (followee_id, follower_id);
...
CREATE TABLE follower_counts (
    user_id   text   PRIMARY KEY,
    followers bigint NOT NULL CHECK (followers >= 0)
);
```
Point at the primary key, the self-follow check, the `followers >= 0` check and the index that serves the fan-out.

**Code:** `services/activity-feed/src/main/resources/db/migration/V1__follow_graph.sql:5-21` (the schema), `services/activity-feed/src/main/java/com/streampulse/feed/adapter/out/postgres/JdbcFollowGraph.java:46-52` (the keyset query), `services/activity-feed/src/test/java/com/streampulse/feed/adapter/out/postgres/JdbcFollowGraphIT.java:95` (constraints hold without the application)

### B4. What is keyset (cursor) pagination, and why not offset?

**Answer:** A cursor says "continue strictly after this item", so the next page starts from a position in the ranking, not from a row number. With offsets, an item inserted between two requests shifts every later row, so a reader sees a repeat or misses an item. In my feed the cursor carries the snapshot time, the score and the entry id, and the pager returns the first item that sorts strictly after it, using the entry id to break ties. A service test pages through a feed while new entries arrive between pages and shows no repeat and no skip. The limit is that this protects against inserts only; a removal such as an unfollow can still shift a later page.

**Example:** `services/activity-feed/src/main/java/com/streampulse/feed/domain/FeedPager.java:31-40`
```java
private static int firstAfter(List<RankedEntry> ordered, Cursor cursor) {
    for (int i = 0; i < ordered.size(); i++) {
        RankedEntry item = ordered.get(i);
        int byScore = Double.compare(cursor.score(), item.score());
        if (byScore > 0 || (byScore == 0 && item.entry().member().compareTo(cursor.entryId()) > 0)) {
            return i;
        }
    }
    return ordered.size();
}
```
The condition is "lower score, or the same score and a later id", which is the strict "after".

**Code:** `services/activity-feed/src/main/java/com/streampulse/feed/domain/FeedPager.java:14-29` (the page), `services/activity-feed/src/test/java/com/streampulse/feed/application/FeedServiceTest.java:111-146` (pages across concurrent inserts), `services/activity-feed/src/test/java/com/streampulse/feed/domain/FeedPagerTest.java:80-88` (pages partition the list)

### B5. Why cap a timeline's length and give it an expiry?

**Answer:** A timeline is derived data that is written for every follower, so without a bound the memory grows with every event. Each write trims the set to a maximum length, which is 200 entries by default, and refreshes a seven day expiry. Both defaults are targets, not measured limits. I did measure Redis memory once, on one laptop with a `redis:7-alpine` container: a sorted set is stored compactly up to 128 entries, and at 129 it converted and cost about twice as much per entry. A full 200-entry timeline measured about 31.7 KB. The expiry has a cost I did not solve: a returning user whose timeline expired sees only celebrity entries until new ones arrive.

**Example:** `services/activity-feed/src/main/resources/application.yml:99-105`
```yaml
follower-page-size: 500
timeline:
  max-length: 200
  ttl: 7d
outbox:
  max-length: 1000
  ttl: 7d
```
These are the bounds that `RedisTimelineStore.write` applies on every push.

**Code:** `services/activity-feed/src/main/resources/application.yml:99-105` (the bounds), `docs/feed-design.md:113-148` (the measured memory table and the arithmetic), `docs/feed-design.md:276-294` (the expired-timeline case, marked not built)

---

## Mid

### M1. How is a redelivered watch event made harmless?

**Answer:** Idempotency is structural, not checked. A feed entry's member string is built from the event id, the author and the title, and its score is the event's own time, so a replay adds the same member with the same score and changes nothing. I rejected a `seen` guard key, because a crash between marking the event seen and finishing the fan-out would lose the fan-out for good. The listener acknowledges only after the fan-out is done, so a crash means redelivery, never loss. Tests cover this at the service level and against real Redis. I did not rerun the mutation check that drops idempotency and expects the duplicate tests to go red, so that is unverified.

**Example:** `services/activity-feed/src/main/java/com/streampulse/feed/domain/FeedEntry.java:46-49`
```java
/** Also the entry's identity everywhere: dedupe key, cursor tiebreak. */
public String member() {
    return eventId + ":" + author.value() + ":" + title.value();
}
```
The event id inside the member is what makes the sorted-set write a no-op the second time.

**Code:** `services/activity-feed/src/main/java/com/streampulse/feed/domain/FeedEntry.java:46-49` (the member), `services/activity-feed/src/main/java/com/streampulse/feed/application/FanOutService.java:15-18` (idempotent as a whole), `services/activity-feed/src/test/java/com/streampulse/feed/application/FanOutServiceTest.java:73-86` (redelivery leaves one entry per follower), `specs/12-activity-feed/tasks.md:41` (T12-23, unticked)

**If they push:** "Is it exactly-once?" No. Delivery is at-least-once and the write is idempotent, so the effect is once; the listener comment says the redelivery is harmless for that reason.

### M2. Why sign the cursor?

**Answer:** The cursor carries state that the server trusts afterwards: the snapshot time, the score and the entry id. If a client could edit it, they could change where the page starts or which snapshot is used. So I sign it with HMAC-SHA256, bind the signature to the user id, and compare the tag in constant time so the comparison does not leak how many leading bytes matched. The signature is integrity, not secrecy: the contents are readable. The tests flip every single byte of a valid token and cover a truncated token, trailing bytes, another user's cursor, a wrong secret, a short secret, an oversized token, and that the error message does not echo the token. The default signing secret is for development only.

**Example:** `services/activity-feed/src/main/java/com/streampulse/feed/domain/CursorCodec.java:70-75`
```java
byte[] payload = Arrays.copyOfRange(raw, 0, raw.length - MAC_BYTES);
byte[] mac = Arrays.copyOfRange(raw, raw.length - MAC_BYTES, raw.length);
// Constant time: a byte-by-byte comparison tells an attacker how many leading bytes matched.
if (!MessageDigest.isEqual(mac, sign(user, payload))) {
    throw invalid();
}
```
`MessageDigest.isEqual` is the constant-time comparison, and `sign` mixes the user id into the MAC.

**Code:** `services/activity-feed/src/main/java/com/streampulse/feed/domain/CursorCodec.java:57-91` (decode), `services/activity-feed/src/main/java/com/streampulse/feed/domain/CursorCodec.java:93-104` (sign, with the separator byte), `services/activity-feed/src/test/java/com/streampulse/feed/domain/CursorCodecTest.java:58-72` (every single byte flipped), `services/activity-feed/src/main/resources/application.yml:95-96` (the dev-only secret)

**If they push:** "Why a zero byte after the user id?" So that two different (user, payload) splits cannot sign alike; the comment in `sign` says that, and I did not find a test aimed at that case.

### M3. How does the follow counter stay correct under concurrency?

**Answer:** The follow row is inserted with `ON CONFLICT DO NOTHING`, and the counter is incremented only when that insert really changed a row, in the same transaction. So a repeated follow, or sixteen threads following the same account at once, moves the counter exactly once. Both statements touch the follow row first and the counter second, always in that order, so two transactions cannot deadlock on the pair. The integration tests run the identical follow from 16 threads and 32 distinct followers at once against real Postgres. The weakness is that the counter row is a hot row when one account is being followed in bulk; I would shard the counter or batch the increments, and that is not built.

**Example:** `services/activity-feed/src/main/java/com/streampulse/feed/adapter/out/postgres/JdbcFollowGraph.java:75-87`
```java
public boolean follow(UserId follower, UserId followee) {
    return Boolean.TRUE.equals(transaction.execute(status -> {
        int inserted = jdbc.update(
                INSERT_FOLLOW,
                follower.value(),
                followee.value(),
                OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
        if (inserted == 1) {
            jdbc.update(INCREMENT, followee.value());
        }
        return inserted == 1;
    }));
}
```
`inserted == 1` is the guard: a conflict returns 0 rows and the counter is left alone.

**Code:** `services/activity-feed/src/main/java/com/streampulse/feed/adapter/out/postgres/JdbcFollowGraph.java:26-37` (the two SQL statements), `services/activity-feed/src/test/java/com/streampulse/feed/adapter/out/postgres/JdbcFollowGraphIT.java:105-129` (the concurrency tests)

**If they push:** "Would the counter drift on unfollow?" The unfollow decrements only when the delete removed a row, in the same transaction, and the column has a `>= 0` check; I did not run a concurrent unfollow test beyond what `unfollowRemovesOnce` covers.

### M4. What happens at the push/pull boundary?

**Answer:** An author at or above the threshold is a celebrity, so the check is `>=`: at the threshold a push would already cost as many writes as the threshold calls too many. The default of 10,000 is a target I picked so the biggest push is about twenty pages of 500 followers. It is not measured. The outbox is always written, whichever strategy applies, so an author who crosses the threshold stays correct on the read path, and the merge removes duplicates between timeline and outbox. What is not handled is hysteresis: an author hovering around the threshold can flip between modes, and one who drops back below it loses sight of entries that were written only to the outbox.

**Example:** `services/activity-feed/src/main/java/com/streampulse/feed/domain/FanOutPolicy.java:14-21`
```java
public FanOutStrategy strategyFor(long followerCount) {
    return isCelebrity(followerCount) ? FanOutStrategy.ON_READ : FanOutStrategy.ON_WRITE;
}

/** {@code >=}: at the threshold the push would already cost as many writes as the threshold says is too many. */
public boolean isCelebrity(long followerCount) {
    return followerCount >= celebrityThreshold;
}
```
The same `isCelebrity` is used at write time and at read time, which is why the two can disagree for a moving follower count.

**Code:** `services/activity-feed/src/main/java/com/streampulse/feed/domain/FanOutPolicy.java:14-21` (the rule), `services/activity-feed/src/test/java/com/streampulse/feed/domain/FanOutPolicyTest.java:24-29` (the boundary test), `services/activity-feed/src/main/resources/application.yml:93-94` (the target)

**If they push:** "How would you add hysteresis?" Use two thresholds, one to become a celebrity and a lower one to stop being one, so a count wobbling in between does not flip the mode. I have not built or tested that.

### M5. When does the listener acknowledge, and how are a poison record and an outage kept apart?

**Answer:** The listener polls a batch, fans out every record, and acknowledges only after all of them are done, so the offset is never committed ahead of durable work. An outage of Redis or Postgres makes the fan-out throw, so the listener does not acknowledge, and the container redelivers the whole poll with backoff and never skips. That is safe because every store write is idempotent by event id. A record that cannot be decoded is different: it is parked on a dead-letter topic with a reason, and if parking itself fails the exception is not caught, because acknowledging past a record I could not park would lose it. An outage therefore dead-letters nothing. I tested this with unit tests and an integration test, not with a real mid-fan-out crash.

**Example:** `services/activity-feed/src/main/java/com/streampulse/feed/adapter/in/kafka/WatchEventListener.java:46-61`
```java
public void onBatch(List<ConsumerRecord<String, byte[]>> records, Acknowledgment ack) {
    for (ConsumerRecord<String, byte[]> record : records) {
        switch (decoder.decode(record.value())) {
            case WatchEventDecoder.Decoded.Valid valid -> fanOut.handle(valid.watch());
            case WatchEventDecoder.Decoded.Rejected rejected -> deadLetter(record, rejected.reason());
        }
    }
    ack.acknowledge();
}

private void deadLetter(ConsumerRecord<String, byte[]> record, String reason) {
    // Not caught: acknowledging past a record we could not park would lose it.
    deadLetters.deadLetter(record.key(), record.value(), reason);
    metrics.deadLettered();
    log.warn("Dead-lettered {}-{}@{}: {}", record.topic(), record.partition(), record.offset(), reason);
}
```
The `ack.acknowledge()` sits after the loop, so any exception in the loop skips it.

**Code:** `services/activity-feed/src/main/java/com/streampulse/feed/adapter/in/kafka/WatchEventListener.java:15-25` (the flow comment), `services/activity-feed/src/test/java/com/streampulse/feed/adapter/in/kafka/WatchEventListenerTest.java:98-145` (ack after fan-out, no ack on failure, dead-letter, no ack when parking fails), `docs/feed-design.md:179-207` (write path and failure handling)

---

## Senior

### S1. Design the read path for a user who follows many celebrities.

**Answer:** I read the reader's followees with their follower counts in one Postgres query, pick the celebrities among them, and keep the most followed, capped at 100. Then I read the reader's own timeline and the outboxes of those celebrities in one pipelined Redis round trip, merge them, drop authors the reader no longer follows, drop anything newer than the snapshot instant, keep the newest entry per author and title, rank, and page. The cap bounds the read cost, but a reader who follows more than 100 celebrities sees a truncated feed. The other cost is the hot key: a very popular celebrity's outbox is read by a large share of all feed reads. Replicating or caching it would help, and neither is built.

**Example:** `services/activity-feed/src/main/java/com/streampulse/feed/application/FeedService.java:79-84`
```java
List<UserId> celebrities = followees.stream()
        .filter(f -> policy.isCelebrity(f.followers()))
        .sorted(Comparator.comparingLong(Followee::followers).reversed())
        .limit(limits.maxCelebrities())
        .map(Followee::id)
        .toList();
```
The `limit` line is the cap that bounds the read, and `sorted` decides which celebrities survive it.

**Code:** `services/activity-feed/src/main/java/com/streampulse/feed/application/FeedService.java:68-96` (the whole read), `services/activity-feed/src/main/java/com/streampulse/feed/domain/FeedMerger.java:23-48` (filtering and de-duplication), `services/activity-feed/src/main/resources/application.yml:106-110` (the read limits, all targets)

**If they push:** "Why keep one entry per author and title?" The feed says "X is watching T", so an older repeat of the same pair is noise; I chose that rule in the merger and did not test alternatives.

### S2. How would you measure whether the fan-out keeps up?

**Answer:** I would measure fan-out lag as a function of follower count, including right at the threshold, along with pipeline round trips per 500 followers and Redis memory per entry. The memory part I did measure once on one laptop: per-entry cost jumps when a sorted set converts from the compact encoding to a skiplist, at 129 entries. The service counts fan-outs per strategy and the celebrity outboxes merged per read. What I did not measure is sustained timeline writes per shard and fan-out lag. The design document's throughput figures are labelled "not measured", and the 100,000 writes per second per shard in its arithmetic is an assumption, not a result.

**Example:** `services/activity-feed/src/main/java/com/streampulse/feed/adapter/out/redis/RedisTimelineStore.java:118-122`
```java
for (UserId author : authors) {
    ops.opsForZSet()
            .reverseRangeByScoreWithScores(
                    outboxKey(author), Double.NEGATIVE_INFINITY, max, 0, perAuthorLimit);
}
```
All the authors' outbox reads share one pipelined round trip, so the round-trip count per feed read does not grow with the number of celebrities.

**Code:** `docs/feed-design.md:113-136` (the measured memory table), `docs/feed-design.md:54-67` (assumptions, including the unmeasured shard rate), `services/activity-feed/src/main/java/com/streampulse/feed/application/FanOutService.java:47` (the fan-out metric call), `services/activity-feed/src/main/java/com/streampulse/feed/application/FeedService.java:89` (the celebrity metric call)

### S3. What is missing before this could take real traffic?

**Answer:** First, a real producer of the watch event. Nothing in the real telemetry pipeline emits it, because telemetry events carry a session id and no user id. The only producer is the service's own `POST /v1/watching` endpoint, and in tests the event is synthetic. Second, authentication and rate limiting: today the follower id is a query parameter, so anyone can act as anyone. Third, skipping inactive users, so I do not push to people who never read; that is the biggest memory saving and it is not built. Fourth, engagement counters. Also, Redis Cluster behaviour of pipelined writes across slots is unverified, and the throughput numbers are targets until a load run measures them.

**Code:** `specs/12-activity-feed/tasks.md:43-44` (the not-built list), `docs/feed-design.md:41-50` (the honest gap on the producer), `docs/feed-design.md:296-308` (what the implementation does not do)

**If they push:** "What would you build first?" Authentication and the producer mapping, because without them the feed has no real identity or input; that ordering is my judgement, not something I measured.

### S4. Why would you rather not trust the three unverified mutation checks?

**Answer:** A passing test suite shows that the code passes its tests, not that the tests would notice a regression. For three properties, always pushing, offset instead of a cursor, and dropped idempotency, the evidence that the named tests go red was not collected again. The task list says these were ticked from passing-test evidence only and that proving them means actively breaking and restoring code, so tasks T12-21 to T12-23 stay unticked. I think each named test probably would fail, but I have not shown it, so the claim stays open. That is the same discipline as elsewhere in the repo, where a deliberate mutation is the evidence.

**Example:** `services/activity-feed/src/test/java/com/streampulse/feed/application/FanOutServiceTest.java:73-86`
```java
@DisplayName("the same event delivered twice leaves exactly one entry per follower and one in the outbox")
void redeliveredEventLeavesOneEntryPerFollower() {
    graph.addFollowers(user("alice"), "f", 3);
    WatchStarted watch = watch("alice", "dune", NOW);

    service.handle(watch);
    service.handle(watch);

    for (int i = 0; i < 3; i++) {
        assertThat(store.timelineOf(user("f" + i))).hasSize(1);
    }
    assertThat(store.outboxOf(user("alice"))).hasSize(1);
}
```
This is the kind of test T12-23 is meant to turn red; the point is that it was not shown to.

**Code:** `specs/12-activity-feed/tasks.md:33-41` (the three unticked checks and why), `services/activity-feed/src/test/java/com/streampulse/feed/application/FanOutServiceTest.java:73-86` (the duplicate test), `services/activity-feed/src/test/java/com/streampulse/feed/domain/FanOutPolicyTest.java:24-29` (the boundary test for T12-21), `services/activity-feed/src/test/java/com/streampulse/feed/application/FeedServiceTest.java:111` (the pagination test for T12-22)

### S5. What would change on Redis Cluster?

**Answer:** The keys are independent per user, so they spread evenly and no command touches two keys, which means no hash tags are needed. What changes is the pipelines. The followers of one author hash to different slots, and a single pipelined batch across slots is not valid in cluster mode, so the batch would have to be grouped by slot or node and sent per node, which needs a grouping step in `RedisTimelineStore`. I am not certain how `StringRedisTemplate.executePipelined` behaves against a cluster connection in this Spring Data Redis version, and the design doc carries a VERIFY note for it. Celebrity outboxes also do not distribute well, because they are hot keys. I ran everything on one Redis node, so none of this is tested.

**Example:** `services/activity-feed/src/main/java/com/streampulse/feed/adapter/out/redis/RedisTimelineStore.java:65-75`
```java
redis.executePipelined(new SessionCallback<Object>() {
    @Override
    @SuppressWarnings("unchecked")
    public <K, V> Object execute(RedisOperations<K, V> operations) {
        var ops = (RedisOperations<String, String>) operations;
        for (UserId follower : followers) {
            write(ops, timelineKey(follower), entry, settings.timelineMaxLength(), settings.timelineTtl());
        }
        return null;
    }
});
```
This loop writes many different follower keys in one pipeline, which is the part that would need grouping by slot.

**Code:** `services/activity-feed/src/main/java/com/streampulse/feed/adapter/out/redis/RedisTimelineStore.java:60-76` (the pipelined push), `docs/feed-design.md:310-322` (the cluster implications and the VERIFY note)

---

## Say this honestly

- The watch signal has no real producer. No auth, no rate limits. The 10,000 threshold is a target.
- Mutation checks T12-21 to T12-23 are not verified.

## Stories and numbers

[Numbers](../06-numbers.md): activity feed test count.
