# ADR-0021 — Hybrid fan-out: push for ordinary authors, pull for celebrities

**Status:** accepted · **Date:** 2026-10-13 · **Phase:** 12 · **Interview question:** SD2

## Context

"Friends are watching" is a feed: when a user starts watching a title, the people who follow
them see "X is watching T". The cost of building a feed depends on one number, the author's
follower count, and that number varies by orders of magnitude between accounts.

| Strategy | Work per watch | Work per feed read | Breaks when |
|---|---|---|---|
| Push (fan-out on write) | one write per follower | one read of the reader's own list | an author has millions of followers |
| Pull (fan-out on read) | one write | one lookup per followed author, merged | a reader follows thousands, or a hot author is read by millions |

A single strategy is a trade of one failure for the other, so the strategy must depend on the
author, and the read path must be able to combine both.

## Decision

1. **The strategy is chosen per author, per event, by follower count.** `FanOutPolicy`:
   `followers >= celebrityThreshold` means pull, otherwise push. The threshold is configuration.
   Its default, **10,000, is a target**, picked so that the largest push is on the order of ten
   thousand writes; it is not measured. Exactly at the threshold is a celebrity: the boundary
   belongs to the cheaper write.
2. **Push** writes the entry into each follower's Redis sorted set (score = event time in
   milliseconds), bounded (`ZREMRANGEBYRANK`) and expiring, one pipelined round trip per page of
   500 followers. **Pull** leaves it in the author's own outbox, a sorted set of the same shape.
3. **The outbox is written for every event, pushed or not.** It costs one write, and it is what
   makes the read path correct for an author who crosses the threshold later: their earlier
   entries are in followers' timelines AND in the outbox, and the merge de-duplicates.
4. **Read** merges the reader's timeline with the outbox of each celebrity they follow (capped
   at the 100 most followed), drops authors the reader no longer follows (a stale entry after an
   unfollow), keeps the newest watch per (author, title), ranks, and pages.
5. **Idempotency by event id is structural, not checked.** A timeline member is
   `eventId:author:title`, so a redelivered event `ZADD`s the same member with the same score
   and changes nothing. Rejected: a `SETNX seen:{eventId}` guard, which has a window between
   "mark seen" and "fan out" in which a crash loses the fan-out.
6. **Pagination is keyset, not offset**, and pins a snapshot instant. The cursor is
   `(asOf, score, entryId)`, signed with HMAC-SHA256 and bound to the user. Every page ranks the
   same candidates at the same instant, so arrivals after the first page cannot shift a later one.
7. **The watch signal is its own Kafka topic**, `activity.watch.v1`, key = `userId`, consumed by
   the same service. `POST /v1/watching` answers 202 only after the broker has the event
   (`acks=all`); the fan-out is asynchronous, replayable, and acknowledged after it is durable.
8. **Wire format: Avro** (`WatchEvent`, via `AvroBinaryCodec`), not JSON. Reason: the repo has one
   event convention (ADR-0004), a schema-compatibility gate that fails the build on a breaking
   change, and a shared codec that already handles the `Utf8` trap. JSON would have avoided
   touching `libs/contracts` and would be readable with `kafka-console-consumer`; at a few
   hundred events a second the size difference is irrelevant, so this is a consistency decision,
   not a performance one.
9. **Follow graph: Postgres, schema `activity_feed`, Flyway.** The shared `public` schema already
   holds sink-writer's table, and Flyway refuses a non-empty schema with no history, which is
   why control-plane has its own schema too. A follower count is a counter row changed in the
   same transaction as the follow row, only when that row really changed.
10. **Spring MVC, not WebFlux:** every call blocks on Postgres, Redis or Kafka, and virtual
    threads (ADR-0006) make that cheap; WebFlux earns its keep for streaming, which this service
    does not do.

## Consequences

**Positive**
- A celebrity's watch costs one write however many followers they have. A feed read costs one
  Postgres lookup, one timeline read and one pipelined read of the celebrity outboxes.
- Redelivery, a client retry and a broker-level duplicate are all absorbed by the same
  mechanism, tested at three levels (member identity, fan-out service, end to end).
- The strategy is observable: `GET /v1/users/{id}/followers` reports the count and the strategy
  it selects, and `streampulse.feed.fanout.events{strategy}` counts each.

**Negative**
- Two code paths to reason about, and a classification that is read twice (at write and at
  read) and can disagree for an author hovering around the threshold. There is no hysteresis;
  an author that drops back below the threshold loses sight of entries that were written only to
  the outbox. Not built.
- A pagination snapshot hides entries that arrive after the first page until the client fetches
  afresh. That is the intended semantics, but it is a semantic, not a free property.
- A stateless cursor cannot stop a *removal* (unfollow, trim at capacity, expiry) from shifting a
  later page, because affinity depends on the candidate set. Only inserts are guaranteed.
- The follower counter is a hot row for a very popular account while it is being followed in
  bulk. See docs/feed-design.md.
- Fan-out on write reaches every follower, active or not. Skipping inactive users is the biggest
  memory saving available and is not built.
- **Nothing in the real pipeline emits `WatchEvent`.** Telemetry events carry a session id and
  no user id, so there is no user to attribute a watch to. Today the only producer is the HTTP
  endpoint on this service.

## Alternatives rejected

| Alternative | Why not |
|---|---|
| Pure push | A celebrity watch is millions of writes, and the follower who reads late waits behind them. |
| Pure pull | Every feed read costs a lookup per followed author, and a celebrity outbox becomes one key read by everyone. |
| SQL join at read time (`follows ⋈ events ORDER BY time`) | The classic trap: cost grows with the reader's follows times the events, on the hot path. The one join here is the reader's own followee list against a counter table by primary key. |
| Offset pagination | A position in a list that changes between requests: an insert near the top repeats an item, a removal skips one. |
| A `seen:{eventId}` guard | Check-then-write window; a crash between them loses the fan-out. |
| Likes as row updates | Engagement counters belong in an async Redis `INCR` with a batched flush. Not built; see the design doc. |
| Flyway in `public` | Refuses a non-empty schema with no history (sink-writer's table is there). |
| `schema.sql` like sink-writer | Fine for one table; here there are two tables and indexes whose history is worth keeping. |
