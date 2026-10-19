# Map: your Q&A notes to this repository

Your sibling notes in `../Java/interview-qa/` are the *concept* answers. This repository is the
*evidence*. For each topic, read the note first for the general answer, then the phase file for "and here
is where I used it".

**Caution.** Those notes were written as study plans before most of the code existed. Where they say what
the project "will" do, trust the phase file for what actually exists. Check every `Verify:` line before
relying on it, and fill in the `> Your story:` placeholders yourself; this pack does not invent your
employment experience.

| Your note | Topic | Where this repo shows it | Phase files |
|---|---|---|---|
| `../Java/interview-qa/01-spring-core.md` | IoC, lifecycle, AOP proxies | Constructor injection enforced by ArchUnit; hand-wired config with no proxies in ingest; the self-invocation lab in the control plane | [0](phases/phase-00-foundation.md), [2](phases/phase-02-ingest-gateway.md), [6](phases/phase-06-control-plane-cdc-alerts.md) |
| `../Java/interview-qa/02-spring-mvc-vs-webflux.md` | MVC, WebFlux, virtual threads | MVC plus virtual threads in ingest (ADR-0006); WebFlux and SSE in the query API (ADR-0014) | [2](phases/phase-02-ingest-gateway.md), [5](phases/phase-05-query-api.md) |
| `../Java/interview-qa/03-concurrency.md` | Locks, pools, `CompletableFuture` | The batch writer's read/write lock and its race test; the 300 ms fan-out on a bounded executor; the fair semaphore in ingest | [4](phases/phase-04-sink-and-storage.md), [5](phases/phase-05-query-api.md), [2](phases/phase-02-ingest-gateway.md) |
| `../Java/interview-qa/04-kafka-and-spring-kafka.md` | Partitions, delivery semantics, DLT, Streams, windowing | Keying by session; exactly-once and its limits; the sink's ack order; windows, grace and suppress; the crash finding | [2](phases/phase-02-ingest-gateway.md), [3](phases/phase-03-stream-processing.md), [4](phases/phase-04-sink-and-storage.md) |
| `../Java/interview-qa/05-resilience4j.md` | Breaker, retry, bulkhead, decorator order | The publisher decorator and the order mutation; Phase 9 breaks things on purpose | [2](phases/phase-02-ingest-gateway.md), [9](phases/phase-09-resilience-and-load.md) |
| `../Java/interview-qa/06-data-layer.md` | JPA, N+1, transactions, Hikari, Flyway | The N+1 lab with counted statements; transaction pitfalls observed on Postgres; the idempotent upsert | [6](phases/phase-06-control-plane-cdc-alerts.md), [4](phases/phase-04-sink-and-storage.md) |
| `../Java/interview-qa/07-cdc-outbox-sse.md` | Dual write, outbox, Debezium, SSE | A real outbox and CDC proven by hand; SSE on both the server and the client | [6](phases/phase-06-control-plane-cdc-alerts.md), [5](phases/phase-05-query-api.md), [7](phases/phase-07-react-dashboard.md) |
| `../Java/interview-qa/08-messaging-rabbitmq-kafka-sqs.md` | Broker choice | SQS used as an alert channel (against moto); Kafka everywhere else; RabbitMQ is **not** used here | [6](phases/phase-06-control-plane-cdc-alerts.md), [10](phases/phase-10-aws-and-terraform.md) |
| `../Java/interview-qa/09-microservices-migration.md` | Boundaries, when not to split | A monorepo on purpose (ADR-0001); service boundaries follow data ownership. No migration was done here | [0](phases/phase-00-foundation.md), [6](phases/phase-06-control-plane-cdc-alerts.md) |
| `../Java/interview-qa/10-grpc.md` | Protobuf, streaming, deadlines, L7 balancing | A second ingest protocol on the same use case; flow control via withheld credit; balancing **not** configured | [2](phases/phase-02-ingest-gateway.md), [1](phases/phase-01-contracts.md), [11](phases/phase-11-containers.md) |
| `../Java/interview-qa/11-observability-and-slos.md` | SLI, SLO, burn rate | Drafted rules, never run; real measurement hygiene stories | [8](phases/phase-08-observability.md), [9](phases/phase-09-resilience-and-load.md) |
| `../Java/interview-qa/12-aws-and-terraform.md` | Terraform, DynamoDB, S3, cross-region | Terraform applied against moto; the compactor; a design-only multi-region note | [10](phases/phase-10-aws-and-terraform.md) |
| `../Java/interview-qa/13-kubernetes-for-stream-apps.md` | Probes, drain, lag scaling | **Design only**; the image and the probe groups exist, manifests do not | [11](phases/phase-11-containers.md) |
| `../Java/interview-qa/14-testing.md` | Slices, Testcontainers, contracts, load | Test layers, mutation checks as a habit, topology tests, Playwright with axe | [0](phases/phase-00-foundation.md), [3](phases/phase-03-stream-processing.md), [7](phases/phase-07-react-dashboard.md) |
| `../Java/interview-qa/15-jvm-performance.md` | Memory, GC, profiling | Heap and thread samples under shedding; **pinned-thread check and flame graphs not done** | [9](phases/phase-09-resilience-and-load.md) |
| `../Java/interview-qa/16-system-design-telemetry-and-feed.md` | The two design rounds | The telemetry pipeline (all phases) and the feed (phase 12) | [01-the-story.md](01-the-story.md), [12](phases/phase-12-activity-feed.md) |
| `../Java/interview-qa/17-coding-lru-and-graphs.md` | Coding round | **Out of scope** for this repo | none |
| `../Java/interview-qa/18-ai-tooling.md` | MCP, agents, spec-driven work | An MCP server with no write tools; the spec-first process in `CLAUDE.md` and `specs/` | [13](phases/phase-13-mcp-server.md) |

## Topics asked often that this repo covers thinly

- **Sagas and distributed transactions.** Not built. The outbox is the nearest relative; say so.
- **Caching strategy and invalidation.** Redis here is a rebuildable projection and an idempotency store,
  not a read-through cache.
- **Authorisation.** Authentication exists on ingest only; scopes and roles are not built anywhere.
- **Multi-instance behaviour.** Everything ran as one instance per service on one machine.
- **Production operations.** No on-call, no runbook (referenced but missing), no real deployment.

For each, say plainly that it was not built and describe how you would approach it. That is a better
answer than a vague claim.
