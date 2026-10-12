# Phase 5 — Query API: Tasks

Order is the order of work. Each line: failing test first, then code. Tick only with passing
output in MILESTONES.md.

- [x] T5-01 spec, design, ADR-0014 written; OpenAPI gains the overview operation, Postgres in the description
- [x] T5-02 `QueryArchitectureRulesTest` fails → module skeleton with the layered packages
- [x] T5-03 `TitleOverviewServiceTest` fails (all five cases) → `TitleOverviewService`, `BoundedExecutor`, ports, records
- [x] T5-04 mutation check: remove `orTimeout`, `slowCallIsDegradedAndTheBudgetHolds` goes red; restore
- [x] T5-05 `EntryIdTest` fails → `EntryId` ordering and parsing
- [x] T5-06 `CoalescerTest` (virtual time) fails → `Coalescer`
- [x] T5-07 `HeartbeatTest` (virtual time) fails → `Heartbeat`
- [x] T5-08 `JdbcMetricHistoryIT` fails → `JdbcMetricHistory` (Postgres container, DDL from the hot-store contract)
- [x] T5-09 `QueryControllersTest` fails → `OverviewController`, `MetricQueryController`, problem-detail advice
- [x] T5-10 `BlockingIsolationTest` fails → composition root runs the ports on the named executor
- [x] T5-11 `SseResumeIT` fails → `RedisLiveFeed`, `SseStreamController`
- [x] T5-12 `QueryApiWiringTest` fails → `QueryProperties`, `QueryConfig`, `application.yml`
- [x] T5-13 `./gradlew :libs:contracts:test --rerun` still green after the OpenAPI edit

## Not done (cut, stated rather than hidden)

- Authentication and authorization (see requirements, Honest gaps).
- Micrometer metrics for executor saturation and degradation counts.
- Per-instance fan-out beyond one shared stream reader; load numbers (Phase 9).
- An automated test for the Redis-down retry loop (observed by hand only).
- `queryMetric` accepts only CONCURRENT_VIEWERS and ERROR_RATE (the sink writes no others); `source` is always "store".
