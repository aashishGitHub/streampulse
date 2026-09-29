# RESUME HERE — state of work as of 2026-09-29

Single place to pick the work back up. Written when two parallel agents were cut off
mid-flight by a weekly API rate limit, so **read the git section first** — the branch layout
is not what you would expect.

---

## 1. Git state — READ THIS FIRST

There are three branches and the main checkout is **not** on `main`.

```
main                                   05c6cd5   circuit breaker + timeout
bs09-grpc-ingest              ← HEAD   096ebd2   = 05c6cd5 + 7229585 (retry) + gRPC
phase2/oidc-keycloak-and-health-probes d35501b   = 05c6cd5 + Keycloak/health (NO retry)
```

```
05c6cd5 (main)
   ├── 7229585 retry ──── 096ebd2 gRPC          [bs09-grpc-ingest]  ← working tree is here
   └── d35501b Keycloak + health probes         [phase2/oidc-keycloak-and-health-probes]
```

### What went wrong, so it does not happen again

Two agents were launched with `isolation: "worktree"`. That isolated the **Java notes repo**
(the working directory), *not* `streampulse`, which is a different repo. One agent therefore
worked directly in the shared `streampulse` checkout and switched its branch.

**The retry commit `7229585` was written while that branch switch had already happened, so it
landed on `bs09-grpc-ingest` instead of `main`.** Nothing is lost, but `main` does not contain
the retry work.

`7229585`'s parent is `05c6cd5`, so putting it on `main` is a clean fast-forward:

```bash
git checkout main
git merge --ff-only 7229585      # main now has retry; gRPC stays on its own branch
```

> Lesson for next time: when parallelising work on a repo that is **not** the current working
> directory, give each agent an explicit `git worktree` of *that* repo, or have them work on
> named branches they create themselves and never `checkout` in the shared tree.

---

## 2. What is DONE and verified (on `main` + `7229585`)

Phase 0, 1 and most of Phase 2. Verified means: I ran it and read the output.

| Work | Commit | Evidence |
|---|---|---|
| Build foundation, conventions, ArchUnit | `41deee0` | — |
| Contracts, Avro, BACKWARD compatibility gate | `3e0f37a` | — |
| Ingest on Kinesis (moto), Kafka as comparison | `fc14688` | — |
| JWT auth, per-tenant Redis quotas, graceful drain | `352b134` | 13 ITs pass |
| **Docker socket fix** + circuit breaker + timeout + **data-loss bug** | `05c6cd5` | 132 tests, 0 failures |
| **Retry with backoff + jitter (REQ-RS-01)** | `7229585` | 139 tests, 0 failures |

### The three findings worth not re-deriving

**a) Testcontainers hangs → suspect the socket, not the daemon.** `DOCKER_HOST` was pinned to
`~/Library/Containers/com.docker.docker/Data/docker.raw.sock`. A Docker Desktop upgrade made
that socket dead-but-present: it still accepts `connect()` and never replies. The guard was
`if (socket.exists())`, which a dead socket passes. Symptom is *silence* — no error, no
containers — which looks exactly like a broken daemon. It was misdiagnosed twice as
"Docker Desktop is unresponsive". Fixed in `build-logic/.../streampulse.java-conventions.gradle.kts`
by ordering candidates (`~/.docker/run/docker.sock` first). The probe is in the comment there.
ITs went from hanging 20 minutes to passing in 19 seconds.

**b) A latent data-loss bug, found by asking what a breaker does to a failed batch.** Event ids
were claimed in Redis (`SET NX EX`) *before* publishing and never released on failure, so the
client's retry came back as a duplicate: `202 Accepted`, zero published, client drops its copy.
Silent permanent loss reported as success. Fixed with `IdempotencyStore.release(...)` as a
compensating action. Both directions are pinned by tests, including one that reproduces the
old behaviour, so nobody deletes `release()` as dead defensive code.

**c) Mutation testing is how the ordering claims were established.** Every load-bearing claim
about decorator order was checked by breaking the code and confirming the suite goes red:

| Mutation | Result |
|---|---|
| `TimeLimiter( CircuitBreaker( … ) )` | ordering test fails: expected OPEN, was CLOSED — **9 of 10 tests still passed** |
| retry moved inside the breaker | attempt-counting test fails: expected OPEN, was CLOSED |
| `ofExponentialBackoff` (no jitter) | jitter test fails: 2 distinct intervals, not more |

Keep this discipline. A suite that passes in both orderings is not testing the ordering.

---

## 3. What is UNVERIFIED — the two agent branches

Both agents were killed by the rate limit near the end of their runs. Their work **compiles**
and their Docker-free tests pass, but:

> **I have not reviewed either branch's code for correctness, and I have not seen their
> integration tests pass.** Docker Desktop is currently stopped, so every Testcontainers test
> errored at startup. Treat their MILESTONES claims as unconfirmed.

### `bs09-grpc-ingest` (096ebd2) — gRPC ingest, BS-09

~3,100 lines. `telemetry.proto`, `ProtoCodegenTask.kt`, `TelemetryIngestGrpcService`,
`JwtAuthInterceptor`, `GrpcStatusMapper`, `GrpcTenantContext`, `GrpcServerLifecycle`,
`GrpcConfig`/`GrpcProperties`, ADR-0010, `specs/02-ingest-gateway/requirements.md`, and a new
`IngestArchitectureRulesTest`. Tests include `ProtocolParityTest`, `GrpcIngestInProcessTest`,
`GrpcDeadlineCancellationTest`, `GrpcStatusMappingTest`.

It also **moved `TenantResolver` from `adapter/in/rest/` to `adapter/in/`** so both protocols
share it. Sensible, but it is a cross-cutting move — check it merged cleanly.

Last measured: `152 tests, 2 failures` — both `initializationError`, both Docker-only
(`IngestEndToEndIT`, `ProtocolParityTest`).

**The agent flagged its own MILESTONES entry:** two command-output excerpts were *lightly
trimmed* rather than literal. It was about to re-run and paste real output when it was cut off.
Fix that before trusting the entry — the house rule is real terminal output.

### `phase2/oidc-keycloak-and-health-probes` (d35501b) — real OIDC + probes

~1,680 lines. Keycloak in `docker/compose.yml` with a realm import, `KeycloakOidcIT`,
`KeycloakTestRealm`, `HealthProbesIT`, `InMemoryIngestBackend`, a `rogue-realm.json` for the
wrong-issuer negative test, and ADR-0011.

Last measured: 2 `initializationError` failures (`IngestEndToEndIT`, `KeycloakOidcIT`) — both
Docker-only. `HealthProbesIT` did **not** fail, so it appears not to need Docker.

**This branch does not contain the retry commit.** It branches from `05c6cd5`.

---

## 4. Next steps, in order

```bash
# 0. Start Docker Desktop first. Confirm it is actually up:
curl -s --max-time 6 --unix-socket ~/.docker/run/docker.sock http://localhost/v1.44/version
#    Expect {"Version":"29.5.3",...}. Empty output or a missing socket = not running.

# 1. Put retry on main (clean fast-forward)
git checkout main && git merge --ff-only 7229585

# 2. Verify the gRPC branch for real
git checkout bs09-grpc-ingest && ./gradlew build
#    Then REVIEW it — protocol parity and the TenantResolver move especially.
#    Then mutation-test its key claims the way ADR-0009's were done.

# 3. Verify the Keycloak branch (rebase onto main first so it has retry)
git checkout phase2/oidc-keycloak-and-health-probes && git rebase main && ./gradlew build

# 4. Merge both into main once each is actually verified, then delete the branches.
```

Do not merge either branch on the strength of its own MILESTONES entry.

---

## 5. Still outstanding after those merge

- **Resilience4j `Bulkhead`** (REQ-RS-05). The in-flight `Semaphore` in `IngestService` is
  already a semaphore bulkhead in effect. Whether to replace it with the library's is a Phase 9
  decision, once there are measurements to justify either way.
- **Breaker and retry numbers are guesses** — chosen to be explainable, not measured. Phase 9
  replaces them with real data.
- **A Phase 3 fork that needs a decision.** The plan specifies Kafka Streams for stream
  processing, but the pivot to Kinesis (ADR-0008) means there is no direct equivalent. Options:
  Kinesis Data Analytics (Flink), a Lambda consumer, or a KCL application — all of which must
  still run free and locally on moto. **This is the next real design decision and it is not
  made yet.** Note the plan itself warns KCL against moto may be flaky.
- Phases 3–14 of `StreamPulse_Project_Plan.md`.

---

## 6. Standing constraints — do not violate these

- **No AWS credentials.** Everything runs free and locally: moto + DynamoDB Local with dummy
  credentials `test`/`test`. Kafka is fine (open source). Never introduce anything needing a
  real AWS account or a paid tier.
- **Never invent an API name.** Verify against the actual jar before using it:
  ```bash
  find ~/.gradle/caches/modules-2/files-2.1/<group> -name '*.jar' | grep -v sources
  javap -cp <jar> <fully.qualified.Class> | grep -i <method>
  ```
  This caught `getWaitDurationInOpenState()`, which does not exist — the real call is
  `getWaitIntervalFunctionInOpenState().apply(1)`, because the open-state wait is modelled as a
  function of the reopen count.
- **MILESTONES.md holds real terminal output**, pasted literally. Never trimmed, never
  reconstructed from memory, never "what it would have said".
- **Committed ≠ passing.** If something was not run, MILESTONES says so in as many words.
- This is a **learning repo**. Comments explain *why*, name the trade-off, and say what breaks
  if you do it the other way. Calibrate against `IngestService.java` and
  `ResilientEventPublisher.java`.

---

## 7. Where the rest of the context lives

| What | Where |
|---|---|
| Full project plan, phases 0–14, BS-01..10, all REQ ids | `~/Documents/GitHub/Java/StreamPulse_Project_Plan.md` |
| Interview Q&A study notes, 19 files | `~/Documents/GitHub/Java/interview-qa/` |
| Verified build/test output, per phase, with corrections | `MILESTONES.md` (this repo) |
| Design decisions and their trade-offs | `docs/adr/0001…0011` |

**Note on the study notes:** `interview-qa/05-resilience4j.md` Q10 had the retry/breaker
counting **backwards** and was corrected in Java repo commit `4a1e435` using the measured
mutation result. Retry *outside* the breaker means each attempt is recorded; retry *inside*
means the breaker wraps the whole loop and sees one result. Other Q&A files were written before
any code existed — treat them as unverified until the matching phase is built, and correct them
from measurement the same way.
