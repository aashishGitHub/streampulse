# RESUME HERE — state of work as of 2026-09-29

Single place to pick the work back up.

**Phase 2 is complete and merged. `main` is clean, one branch, no worktrees, 149 tests
passing including Testcontainers integration tests against moto, Redis and Keycloak.**

The next session starts with a design decision, not with code — see §3.

---

## 1. Where things stand

```
$ git log --oneline -9
d9487e5  Phase 2 close-out, and correct the inflated test counts
b51ad7b  Pin the gRPC port to 0 in the Keycloak and health-probe tests
95accc8  Phase 2: real OIDC against Keycloak, and health probes that have been seen to move
36ce59f  Add RESUME-HERE.md
096ebd2  Phase 2: gRPC as a second ingest protocol, over the same use case
7229585  Phase 2: retry with backoff and jitter, outside the breaker (REQ-RS-01)
05c6cd5  Phase 2: circuit breaker + timeout, and the data-loss bug it uncovered
352b134  Phase 2: JWT auth, per-tenant quotas, graceful drain
fc14688  Phase 2: ingest gateway on AWS Kinesis, with Kafka as a comparison adapter
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

## 3. The next session starts here — a decision, then Phase 3

**The plan specifies Kafka Streams for stream processing. The pivot to Kinesis (ADR-0008)
leaves no direct equivalent, and that choice has not been made.**

| Option | Runs free + locally? | Notes |
|---|---|---|
| Kinesis Data Analytics (Flink) | Flink locally, yes; KDA itself, no | closest to the managed AWS story; heaviest to run |
| Lambda consumer | via moto Lambda or `sam local` | plan already flags moto-Lambda on Mac as unverified |
| KCL application | plan flags it as **possibly flaky against moto** | closest to "real" Kinesis consumption |
| Keep Kafka Streams behind the `kafka` profile | yes, free | the Kafka adapter already exists; splits the story across two backbones |

Decide this first, write ADR-0012, then follow the spec-driven loop in
`StreamPulse_Project_Plan.md` §3: `requirements.md` → `design.md` → contracts → `tasks.md` →
failing acceptance tests → code → MILESTONES with real output.

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
