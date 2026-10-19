# Phase 12 — Activity feed: Tasks

Each line: failing test first, then code. Tick only with passing output.

## Specs and contracts
- [x] T12-01 requirements, design, ADR-0021, `docs/feed-design.md` written before any code
- [x] T12-02 `SchemaCompatibilityTest` for `WatchEvent` fails (no baseline) → `WatchEvent.avsc`, baseline copy
- [x] T12-03 AsyncAPI channels `activityWatch`, `activityWatchDlt` (6 and 3 partitions, `.v1` names)

## Domain (pure, no containers)
- [x] T12-04 `UserIdTest`, `FeedEntryTest` fail to compile → `UserId`, `TitleId`, `WatchStarted`, `FeedEntry`
- [x] T12-05 `FanOutPolicyTest` (below, exactly at, above the threshold) fails → `FanOutPolicy`, `FanOutStrategy`
- [x] T12-06 `RankerTest` fails → `Ranker`, `RankedEntry`
- [x] T12-07 `FeedMergerTest` fails → `FeedMerger`
- [x] T12-08 `FeedPagerTest` (partition without repeat or skip) fails → `FeedPager`, `FeedPage`, `Cursor`
- [x] T12-09 `CursorCodecTest` (garbage, tampered, foreign, wrong secret, truncated, oversized) fails → `CursorCodec`
- [x] T12-10 `FeedArchitectureRulesTest` fails → hexagonal package layout

## Application (fakes, no containers)
- [x] T12-11 `FanOutServiceTest` (boundary, duplicate, paging) fails → `FanOutService`, ports `FollowGraph`, `TimelineStore`
- [x] T12-12 `FeedServiceTest` (merge, unfollow, concurrent inserts, bad cursor, celebrity cap) fails → `FeedService`
- [x] T12-13 `FollowServiceTest`, `WatchServiceTest` fail → `FollowService`, `WatchService`, `WatchPublisher`

## Adapters
- [x] T12-14 `WatchEventDecoderTest` fails → `WatchEventDecoder`
- [x] T12-15 `WatchEventListenerTest` (ack after fan-out, not on failure, dead letter) fails → `WatchEventListener`
- [x] T12-16 `WatchControllerTest`, `FollowControllerTest`, `FeedControllerTest` fail → controllers, `ProblemAdvice`
- [x] T12-17 `JdbcFollowGraphIT` fails → Flyway `V1__follow_graph.sql`, `JdbcFollowGraph`
- [x] T12-18 `RedisTimelineStoreIT` fails → `RedisTimelineStore`
- [x] T12-19 `FeedPropertiesTest`, `MicrometerFeedMetricsTest`, `FeedWiringTest` fail → config, `application.yml`
- [x] T12-20 `WatchFeedIT` (normal, duplicate, celebrity, pagination, unfollow, poison) fails → `KafkaWatchPublisher`, `KafkaDeadLetterPublisher`, listener container factory

## Mutation checks (each restored afterwards)

Ticked in 2026-10-19 close-out from passing-test evidence only (T12-02 through T12-20). These
three were NOT rerun — proving them means actively breaking and restoring code, not reading a
test-results XML — so they stay unticked and open:

- [ ] T12-21 fan-out policy always ON_WRITE → celebrity tests red
- [ ] T12-22 cursor replaced by an offset → concurrent-insert pagination tests red
- [ ] T12-23 idempotency by event id dropped → duplicate tests red

## Not built (see `docs/feed-design.md`)
Engagement counters, auth, rate limits, inactive-user skipping, a real producer of the watch signal.
