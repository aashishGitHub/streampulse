# RESUME HERE — state of work as of 2026-10-12

Single place to pick the work back up.

**Phase 2 is merged to `main`. Branch `phase3-kafka-slice` (committed as `7d1e4ba`; not pushed yet)
holds the whole 5-day interview slice: Kafka backbone (ADR-0012), stream-processor, sink-writer,
query-api, load generator, observability config, `docs/script.md` and `docs/deep-dive.md`.
460 tests, 0 failures. The pipeline has been run end to end: 1,500 events/s, p99 ingest 68.7 ms,
freshness 5.2 to 5.4 s per window. All recorded in MILESTONES.md, Phase 3 Days 2-5.**

History was rewritten once, before anything was pushed, to author everything as the personal
identity (`aashishGitHub`). Commit hashes in this file and in MILESTONES.md are the NEW ones; the
pre-rewrite history is in `~/streampulse-before-rewrite.bundle` (delete once you no longer need it).

---

## 1. Where things stand

```
$ git log --oneline -9
c62cf2d  Phase 2 close-out, and correct the inflated test counts
444d84a  Pin the gRPC port to 0 in the Keycloak and health-probe tests
4f55b95  Phase 2: real OIDC against Keycloak, and health probes that have been seen to move
61c7289  Add RESUME-HERE.md
275ea22  Phase 2: gRPC as a second ingest protocol, over the same use case
4a057e1  Phase 2: retry with backoff and jitter, outside the breaker (REQ-RS-01)
efad591  Phase 2: circuit breaker + timeout, and the data-loss bug it uncovered
da34a38  Phase 2: JWT auth, per-tenant quotas, graceful drain
7e7f66d  Phase 2: ingest gateway on AWS Kinesis, with Kafka as a comparison adapter
```

```
$ ./gradlew clean build
BUILD SUCCESSFUL in 46s
```

| Module | tests | failures |
|---|---|---|
| `libs/contracts` | 28 | 0 |
| `libs/platform` | 39 | 0 |
| `services/ingest-gateway` | 82 | 0 |
| **TOTAL** | **149** | **0** |

### What `services/ingest-gateway` can do

Batched telemetry over **REST and gRPC**, both through the same `IngestUseCase` — proven by
`ProtocolParityTest`, which decodes the Avro off Kinesis and compares every envelope field
across REST, gRPC unary and gRPC streaming, and proves deduplication spans both protocols.

Behind that: Keycloak OIDC with real JWKS/issuer/audience validation, per-tenant Redis token
buckets, a bounded in-flight semaphore for backpressure, atomic idempotency claims,
`Retry( CircuitBreaker( TimeLimiter( publish ) ) )` on the publisher, ordered graceful drain
via `SmartLifecycle`, and actuator health groups that have been *seen to move*.

Everything runs free and locally: moto fakes Kinesis, DynamoDB Local, Keycloak in Docker.

---

## 2. Things worth not re-deriving

**a) Testcontainers hangs → suspect the socket, not the daemon.** `DOCKER_HOST` was pinned to
`~/Library/Containers/…/docker.raw.sock`. A Docker Desktop upgrade made that socket
dead-but-present: it accepts `connect()` and never replies. The guard was
`if (socket.exists())`, which a dead socket passes. The symptom is *silence* — no error, no
containers — which looks exactly like a broken daemon, and was misdiagnosed as one twice.
Fixed in `build-logic/…/streampulse.java-conventions.gradle.kts`; the curl probe is in the
comment there. ITs went from hanging 20 minutes to passing in 19 seconds.

**b) Claims about decorator ordering get established by mutation, not by assertion.** Break
the code, confirm the suite goes red:

| Mutation | Result |
|---|---|
| `TimeLimiter( CircuitBreaker( … ) )` | ordering test fails — but **9 of 10 tests still passed** |
| retry moved inside the breaker | attempt-counting test fails |
| `ofExponentialBackoff` (no jitter) | jitter test fails: 2 distinct intervals, not more |

A suite that passes in both orderings is not testing the ordering. Keep this discipline.

**c) Two latent defects were found by *combining* work, not by writing it.**
- Adding a circuit breaker surfaced silent data loss: ids were claimed in Redis before
  publishing and never released on failure, so a retry came back as a duplicate — `202`,
  zero published, client drops its copy. Fixed with a compensating `release(...)`, with a
  test that reproduces the old behaviour so nobody deletes it as dead code.
- Merging gRPC with the health probes surfaced a port-9090 collision that **cannot reproduce
  when either test class runs alone** — it needs two `@SpringBootTest` contexts in one JVM.
  Anything binding a fixed port is a shared global between contexts, like a static field.

**d) Test counts were inflated by 25 for three entries.** `kata/build/test-results` survived
the module's removal from `settings.gradle.kts` and kept being summed. Corrected in
MILESTONES; the counting script now reads the module list from `settings.gradle.kts`. Also
corrected: those tables were formatted as `$ ./gradlew build` output, and Gradle prints no
such table. They are markdown tables now, labelled as such.

---

## 3. The next session starts here

Nothing is blocking. In priority order:

1. **Push both branches** (`main`, `phase3-kafka-slice`) once the private repo exists under
   `aashishGitHub`. There was no remote before this session: until pushed, this exists on one disk.
2. **Read `docs/script.md` aloud once**, then `docs/deep-dive.md`. Figures tagged
   "(not measured: ...)" were never measured: do not quote them.
3. **Measurements still missing** (each is a Phase 9 item): the saturation point (raise the
   tenant quota first: it caps ingest at 2,000 events/s), flush latency and queue depth, peak lag
   and drain time, overview p99 under load, SSE streams per instance, MVC-vs-WebFlux.
4. **End-to-end event accounting** (every event sent appears in exactly one aggregate): asserted
   in pieces, never over a whole run.
5. **A processor crash mid-window**; `api/openapi/ingest-v1.yaml` still says saturation is 429
   while ingest returns 503; query-api has no auth; no outbox, schema registry or Kubernetes.

To run it: `make up-core`, `make run-all`, `make wait-services`, then
`LOADGEN_CLIENT_SECRET=<ingest-gateway client secret from docker/keycloak/realms/streampulse-realm.json> make loadgen`.
Run a short warm-up first, or the first window of the measured run closes a leftover one and
skews freshness (see MILESTONES, Day 5).

Things learned that will bite again:

- **`apache/kafka:3.9.0` does not start under Testcontainers 1.21.2** (advertised listener
  `0.0.0.0` rejected). **3.9.1** starts. Compose and the ITs pin 3.9.1.
- **Avro decodes strings as `Utf8`, not `String`.** `Utf8` never equals a `String`. Call
  `toString()` before using one as a key or in a comparison.
- **Gradle 9 fails a `Test` task that discovers no tests.** `integrationTest` sets
  `failOnNoDiscoveredTests = false` because `libs:*` have no `*IT` classes.
- `*IT` classes run only under `./gradlew integrationTest` (`make it`); `./gradlew test` is
  Docker-free. `check` runs both.
- Prometheus is on host port **9091**: ingest's gRPC server owns 9090.
- A test that finishes faster than a timeout cannot find a timeout bug. The idle live feed
  failed every poll in the real stack and passed every test (`LiveFeedIdleIT` now covers it).
- A randomised concurrency test can stay green with the lock removed. Prove a concurrency test
  by mutating the code, and prefer a deterministic one (see `BatchWriterTest`).

### Also outstanding from Phase 2 (deliberately deferred, not forgotten)

- **REQ-GR-06, the gRPC benchmark.** Not run, so **every efficiency claim in ADR-0010 is an
  expectation, not a measurement** — and that is the stated reason the protocol exists here.
- `buf lint` / `buf breaking` not in CI. Nothing stops a protobuf field renumber.
- W3C trace context over gRPC (Phase 8); L7 load balancing (Phase 11).
- Resilience4j `Bulkhead` (REQ-RS-05) — the in-flight `Semaphore` already is one in effect.
- Authorization as distinct from authentication — scopes and roles untouched.
- No test sends a real SIGTERM; the drain test calls `stop()` directly.
- **Every number in Phase 2 is explainable, not measured.** Phase 9 replaces them.

---

## 4. Standing constraints — do not violate these

- **No AWS credentials.** Everything runs free and locally: moto + DynamoDB Local with dummy
  credentials `test`/`test`. Kafka is fine (open source). Never introduce anything needing a
  real AWS account or a paid tier.
- **Never invent an API name.** Verify against the actual jar first:
  ```bash
  find ~/.gradle/caches/modules-2/files-2.1/<group> -name '*.jar' | grep -v sources
  javap -cp <jar> <fully.qualified.Class> | grep -i <method>
  ```
  This caught `getWaitDurationInOpenState()`, which does not exist — the real call is
  `getWaitIntervalFunctionInOpenState().apply(1)`, because the open-state wait is modelled as
  a function of the reopen count.
- **MILESTONES.md holds real terminal output**, pasted literally. Anything that is not
  console output must not be formatted as console output.
- **Committed ≠ passing.** If something was not run, MILESTONES says so in as many words.
- **This is a learning repo.** Comments explain *why*, name the trade-off, and say what
  breaks if you do it the other way. Calibrate against `IngestService.java` and
  `ResilientEventPublisher.java`.
- **Parallel agents:** `isolation: "worktree"` isolates the *working directory's* repo. This
  repo is not the working directory (that is the Java notes repo), so an agent working here
  shares this checkout and can switch its branch under you. Give agents an explicit
  `git worktree` of **this** repo, or have them create and stay on their own branch.

---

## 5. Where the rest of the context lives

| What | Where |
|---|---|
| Full plan, phases 0–14, BS-01..10, all REQ ids | `~/Documents/GitHub/Java/StreamPulse_Project_Plan.md` |
| Interview Q&A study notes, 19 files | `~/Documents/GitHub/Java/interview-qa/` |
| Verified build/test output per phase, with corrections | `MILESTONES.md` |
| Design decisions and trade-offs | `docs/adr/0001…0011` |
| Per-phase specs | `specs/` |

**On the study notes:** `interview-qa/05-resilience4j.md` Q10 had the retry/breaker counting
**backwards** and was corrected in Java repo commit `4a1e435` from the measured mutation
result — retry *outside* the breaker means each attempt is recorded; retry *inside* means the
breaker wraps the whole loop and sees one result. The other Q&A files were written before any
code existed. Treat them as unverified until the matching phase is built, and correct them
from measurement the same way.
