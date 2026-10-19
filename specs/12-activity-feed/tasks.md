# Phase 12 — Activity feed: Tasks

Each line: failing test first, then code. Tick only with passing output.

## Specs and contracts
- [x] T12-01 requirements, design, ADR-0021, `docs/feed-design.md` written before any code
- [ ] T12-02 `SchemaCompatibilityTest` for `WatchEvent` fails (no baseline) → `WatchEvent.avsc`, baseline copy
- [ ] T12-03 AsyncAPI channels `activityWatch`, `activityWatchDlt` (6 and 3 partitions, `.v1` names)

## Domain (pure, no containers)
- [ ] T12-04 `UserIdTest`, `FeedEntryTest` fail to compile → `UserId`, `TitleId`, `WatchStarted`, `FeedEntry`
- [ ] T12-05 `FanOutPolicyTest` (below, exactly at, above the threshold) fails → `FanOutPolicy`, `FanOutStrategy`
- [ ] T12-06 `RankerTest` fails → `Ranker`, `RankedEntry`
- [ ] T12-07 `FeedMergerTest` fails → `FeedMerger`
- [ ] T12-08 `FeedPagerTest` (partition without repeat or skip) fails → `FeedPager`, `FeedPage`, `Cursor`
- [ ] T12-09 `CursorCodecTest` (garbage, tampered, foreign, wrong secret, truncated, oversized) fails → `CursorCodec`
- [ ] T12-10 `FeedArchitectureRulesTest` fails → hexagonal package layout

## Application (fakes, no containers)
- [ ] T12-11 `FanOutServiceTest` (boundary, duplicate, paging) fails → `FanOutService`, ports `FollowGraph`, `TimelineStore`
- [ ] T12-12 `FeedServiceTest` (merge, unfollow, concurrent inserts, bad cursor, celebrity cap) fails → `FeedService`
- [ ] T12-13 `FollowServiceTest`, `WatchServiceTest` fail → `FollowService`, `WatchService`, `WatchPublisher`

## Adapters
- [ ] T12-14 `WatchEventDecoderTest` fails → `WatchEventDecoder`
- [ ] T12-15 `WatchEventListenerTest` (ack after fan-out, not on failure, dead letter) fails → `WatchEventListener`
- [ ] T12-16 `WatchControllerTest`, `FollowControllerTest`, `FeedControllerTest` fail → controllers, `ProblemAdvice`
- [ ] T12-17 `JdbcFollowGraphIT` fails → Flyway `V1__follow_graph.sql`, `JdbcFollowGraph`
- [ ] T12-18 `RedisTimelineStoreIT` fails → `RedisTimelineStore`
- [ ] T12-19 `FeedPropertiesTest`, `MicrometerFeedMetricsTest`, `FeedWiringTest` fail → config, `application.yml`
- [ ] T12-20 `WatchFeedIT` (normal, duplicate, celebrity, pagination, unfollow, poison) fails → `KafkaWatchPublisher`, `KafkaDeadLetterPublisher`, listener container factory

## Mutation checks (each restored afterwards)
- [ ] T12-21 fan-out policy always ON_WRITE → celebrity tests red
- [ ] T12-22 cursor replaced by an offset → concurrent-insert pagination tests red
- [ ] T12-23 idempotency by event id dropped → duplicate tests red

## Not built (see `docs/feed-design.md`)
Engagement counters, auth, rate limits, inactive-user skipping, a real producer of the watch signal.
