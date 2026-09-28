# MILESTONES

A build log of what was **actually run and observed** — not what was intended.

Convention borrowed from the author's `ticket-booking` project: every entry pastes real
terminal output. Where something was skipped, deferred or only partly verified, it says so.
A milestone log that only records successes is marketing, not engineering.

---

## Phase 0 — Foundation · 2026-09-28 · ✅ core complete

### Environment

```
openjdk version "21.0.12.1" 2026-08-18
OpenJDK Runtime Environment Homebrew (build 21.0.12.1)
Docker version 29.5.3 · Docker Compose v5.1.4
Apple Silicon (arm64) · 32 GB RAM · 12 cores
```

### Gradle bootstrap

No Gradle was installed. The distribution was downloaded and its SHA-256 verified against
the value published by `services.gradle.org/versions/current` **before** being executed:

```
expected: bafd5ce9cfaea0fbccfdc8439a1ac42fbd4cd9c89dc9a988228d8a2639a58e6c
actual:   bafd5ce9cfaea0fbccfdc8439a1ac42fbd4cd9c89dc9a988228d8a2639a58e6c
✅ CHECKSUM OK
Welcome to Gradle 9.8.0!
```

The same checksum is pinned in `gradle/wrapper/gradle-wrapper.properties`, so every future
`./gradlew` invocation re-verifies the distribution.

> **Note on the first attempt:** the initial download produced a checksum mismatch. It was
> not tampering — `curl` without `-L` had saved a 169-byte HTTP 307 redirect page instead of
> the archive. Recorded because "checksum mismatch" should always be investigated rather
> than retried blindly.

### Build

```
$ ./gradlew build
BUILD SUCCESSFUL in 14s
```

39 tests pass:

```
ArchitectureRulesTest > no field injection anywhere                        PASSED
ArchitectureRulesTest > the domain layer stays free of framework imports   PASSED
ArchitectureRulesTest > inbound adapters go through the use case           PASSED
ArchitectureRulesTest > no java.util.Date or Calendar — use java.time      PASSED

TenantIdTest > normalisation > lowercasesAndTrims()                        PASSED
TenantIdTest > normalisation > two spellings of the same tenant are equal  PASSED
TenantIdTest > normalisation > toString is the bare value                  PASSED
TenantIdTest > accepts valid identifiers > acceptsWellFormed  [7 cases]    PASSED
TenantIdTest > rejects ... > rejectsMalformed                 [8 cases]    PASSED
TenantIdTest > rejects ... > rejectsNull / rejectsBlank / rejectsTooLong   PASSED
TenantIdTest > key layout > keyPrefixIsDefinedInExactlyOnePlace()          PASSED

EventIdTest  > validation > acceptsCanonicalUuid()                         PASSED
EventIdTest  > validation > uppercase input is normalised for dedup        PASSED
EventIdTest  > validation > rejectsMalformed                  [5 cases]    PASSED
EventIdTest  > validation > the original parse failure is preserved        PASSED
EventIdTest  > generation > randomIdsAreUnique()              [10k ids]    PASSED
EventIdTest  > idempotency key > isNamespacedByTenant()                    PASSED
EventIdTest  > idempotency key > sameEventDifferentTenantsDoNotCollide()   PASSED
```

### Quality gates — verified by breaking them

A gate nobody has seen fail is not a gate. Each was deliberately violated, the failure
observed, and the change reverted.

**1. Spotless catches misformatted code**

```
$ printf 'class    Bad  {int   x;}' >> LruCache.java && ./gradlew spotlessCheck
> The following files had format violations:
      src/main/java/com/streampulse/kata/cache/LruCache.java
  Run './gradlew :kata:spotlessApply' to fix these violations.
BUILD FAILED in 900ms
```

**2. The value-object tests catch a widened validation rule**

These gates were first verified against the algorithm katas that Phase 0 originally shipped
(commit `41deee0`); the katas were subsequently removed as out of scope, and the equivalent
gate now sits on the domain primitives. Deleting the `fresh > 0` guard in the old BFS code
produced:

```
RottingOrangesTest > the off-by-one trap > adjacent fresh orange takes exactly 1 minute, not 2 FAILED
    expected: 1  but was: 2
```

The *named* failure-mode test caught it first and most precisely — the argument for writing
tests against the specific bug rather than only the happy path. `TenantIdTest`'s eight
parameterized rejection cases serve the same purpose now: loosening the regex to permit a
`/` fails `rejectsMalformed[2] candidate=has/slash` immediately.

**3. ArchUnit catches an architecture violation**

Adding a class returning `java.util.Date`:

```
ArchitectureRulesTest > no java.util.Date or Calendar — use java.time FAILED
    Architecture Violation [Priority: MEDIUM] - Rule 'no classes should depend on classes
    that have fully qualified name 'java.util.Date' ...' was violated (2 times):
    Method <com.streampulse.platform.Temp.now()> calls constructor <java.util.Date.<init>()>
    Method <com.streampulse.platform.Temp.now()> has return type <java.util.Date>
```

### Problem hit and resolved

**Type-safe version-catalog accessors are unavailable inside precompiled script plugins.**
`libs.assertj` inside `build-logic` fails to compile with `Unresolved reference`. The widely
circulated workaround injects the generated `LibrariesForLibs` class via a `files(...)`
reflection trick.

Resolved instead with the documented `VersionCatalogsExtension` API. More verbose, but it
fails loudly naming the missing alias, and it teaches the model rather than a hack.
Documented in `specs/00-foundation/design.md` §3.

### Measurements

| NFR | Target | Observed |
|---|---|---|
| NFR-F0-01 incremental build | < 5 s | ~1 s (`UP-TO-DATE`) |
| NFR-F0-02 full build | < 3 min | 3 s warm · ~2.5 min first run (distribution + dependency download) |
| NFR-F0-03 no Docker needed | always | ✅ Docker not started for any Phase 0 task |

### Deferred to later phases

Honest scope notes:

- `docker/compose.yml` is **written but never started.** Phase 0 needs no containers; the
  images are declared so the system's shape is visible, and the tags need verification
  before first use.
- The CI workflow is **committed but has not run** — no remote configured yet.
- `integrationTest` source set arrives with Testcontainers in Phase 2.

### Scope change — algorithm katas removed

Phase 0 originally shipped a `kata` module (`LruCache`, `RottingOranges`) as a
coding-interview track. **Removed**: StreamPulse is for learning the Java ecosystem in a
cloud environment, and DSA practice is a different skill that was diluting the project's
focus.

The code remains in git history at commit `41deee0` if it is ever wanted, and the full
worked solutions live in `../Java/interview-qa/17-coding-lru-and-graphs.md`.

It was replaced by `TenantId` and `EventId` — value objects that are genuinely load-bearing
from Phase 2 onward, and that teach the same Java fundamentals (records, compact
constructors, validation at the boundary, `equals`/`hashCode` correctness) on material the
project actually uses.

```
$ ./gradlew build
BUILD SUCCESSFUL in 3s
```

### Next

Phase 1 — contracts and schemas. Spec first.

---

## Phase 1 — Contracts & Schemas · 2026-09-28 · ✅ complete

### What landed

`libs/contracts`: four Avro schemas generating 20 Java classes, a build-time schema-evolution
gate, three OpenAPI documents and an AsyncAPI topic contract.

```
$ ./gradlew clean build
BUILD SUCCESSFUL

$ ./gradlew test --rerun-tasks
67 tests passing
```

### Measured — event size

Real numbers from `SerializationRoundTripTest`, not estimates:

```
Heartbeat serialized size: 134 bytes
Avro 134 bytes vs JSON ~409 bytes (67% smaller)
```

NFR-C1-04 set a 400-byte budget for the highest-volume event; observed 134. The saving is
almost entirely from Avro not writing field names on every record. This is also the baseline
the Phase 9 compression work will be measured against.

### The schema-evolution gate — proven by breaking it

Added a required field (no default) to a released schema:

```
$ ./gradlew :libs:contracts:test
SchemaCompatibilityTest > TelemetryEvent can read data written by its baseline FAILED
    TelemetryEvent is NOT backward compatible with its baseline.
      - READER_FIELD_MISSING_DEFAULT_VALUE at /fields/10 (abTestBucket)

    Most likely you added a field without a default, renamed one, or narrowed a type.
    See specs/01-contracts/design.md section 4.
    If the change is genuinely intended, update the baseline in
    src/test/resources/baseline/ IN THE SAME COMMIT so a reviewer sees both halves.
```

Reverted afterwards. Three permanent fixtures under `src/test/resources/incompatible/`
(added-required-field, renamed-field, narrowed-type) keep the gate honest — if someone
weakens the check, those tests go green and the weakening is visible.

The counterpart test matters as much: adding a field **with** a default passes. The lesson is
not "adding fields is dangerous".

> **Note on the failure message.** Avro's own `toString` embeds both complete schemas in
> every incompatibility — roughly 10 KB of JSON per failure for our event model. The first
> version of this test printed that verbatim. It was replaced with a summary of type,
> location and field name, because a developer scrolling past 10 KB of JSON is a developer
> who stops reading test output.

### Problems hit and resolved

**Avro codegen took three attempts.** Recorded because each failed for a different and
non-obvious reason:

1. The community Avro Gradle plugin — Gradle 9 support unverified, and a learning project
   should not be blocked on a third party's release cadence.
2. `avro-tools` via `JavaExec` — **cannot be resolved at all**. Its 1.12.x POM declares
   `trevni-core-tests.jar` and `trevni-avro-tests.jar`, which were never published to Maven
   Central. The `nodeps` classifier avoids that but then needs `jopt-simple` and friends
   supplied by hand.
3. `SpecificCompiler.main` — defaults to *protocol* mode, so a record schema fails with
   "No protocol name specified".

Resolved with a custom `AvroCodegenTask` in `build-logic/` calling
`SpecificCompiler.compileSchema(File[], File)` directly. `@InputDirectory`/`@OutputDirectory`
make it incremental and cacheable. Documented in ADR-0004.

**Logical types generate `java.time.Instant`, not `long`.** `timestamp-millis` produces
`Instant` accessors, which broke the first draft of the round-trip test. Worth knowing: it
keeps generated code inside the "no `java.util.Date`" architecture rule for free.

### Design decisions recorded

- **ADR-0004** Avro for events — with the measured size comparison, and the Avro-resolves-by-name
  vs Protobuf-resolves-by-number contrast that explains why both are used.
- **ADR-0005** BACKWARD compatibility + versioned topic names + frozen baselines.
- Envelope + payload union on **one** topic, so per-session ordering survives.
- `sessionId` as partition key: ordering is a correctness property, repartitioning for
  per-title aggregation is only a performance cost.
- `config.changes.v1` is compacted, so its payload is a full snapshot and a delete is a
  null tombstone.

### Deferred

- Schema Registry — Phase 2. The build-time gate needs no infrastructure; the registry adds
  runtime enforcement.
- TypeScript client generation from OpenAPI — Phase 7, with the frontend.
- Protobuf/gRPC contracts — Phase 2, with the gRPC adapter.
- Topics are **documented but not created**. Phase 2 creates them from the AsyncAPI document.

### Next

Phase 2 — ingest gateway. Spring Boot enters the build for the first time, which is also
where the Gradle 9 / Spring Boot plugin compatibility risk gets tested in isolation.

---

## Phase 2 (continued) — security, quotas, graceful drain · 2026-09-29 · ⚠️ partially verified

### What landed

Closing the gaps left open when Phase 2 first shipped a working vertical slice.

**Security (REQ-IG-07/08/09).** Spring Security OAuth2 resource server. The tenant now comes
from a verified JWT claim via `TenantResolver`, never from a header or the body. The defence
is structural: `EventDto` has no tenant field at all, so there is nothing to accidentally
trust. CSRF is disabled with a stated reason — bearer tokens are not attached automatically
by the browser, so there is nothing for an attacker to ride on. That answer changes the
moment this service authenticates with a cookie.

**Per-tenant quotas (REQ-IG-20).** A token bucket in Redis, consumed by a Lua script.

Why Lua rather than three Redis calls:

```
❌  tokens = GET bucket      both replicas read 5
    if (tokens >= 3)          both decide yes
    SET bucket (tokens - 3)   both write 2 — six tokens spent from a budget of five
```

Redis runs a script atomically, so read-decide-write becomes one indivisible operation. Same
insight as `SET NX EX` for idempotency: the fix for a check-then-act race is not a faster
check, it is making the whole thing one operation.

Deliberately NOT Resilience4j's RateLimiter, which is per-JVM: with four replicas a "1000/s"
limit silently becomes 4000/s and changes every time you scale. A tenant quota is a business
fact, so it lives somewhere shared.

**429 vs 503.** Kept distinct because a good client reacts differently: 429 means *your*
quota is gone and retrying elsewhere will not help; 503 means *we* are saturated and another
instance may cope.

**Graceful drain (BS-01 / REQ-IG-27).** `IngestDrainLifecycle` at `SmartLifecycle` phase 100.
Phases stop in descending order, so readiness flips to `REFUSING_TRAFFIC` first, the HTTP
connector stops next, and only then do we wait for in-flight batches. The permits *are* the
in-flight count, so "all permits returned" is the drain condition. A timeout logs at ERROR
rather than passing silently — that is the case where a deploy can actually lose work.

### Verified

```
$ ./gradlew :services:ingest-gateway:test --tests '*IngestServiceTest*' \
            :libs:platform:test :libs:contracts:test
82 tests passing
```

Including three new quota tests. The one worth naming:

```
per-tenant quota > a rejected batch leaves the in-flight permits untouched   PASSED
```

That asserts the quota check runs **before** acquiring a permit. If it ran after, a throttled
tenant would consume capacity that healthy tenants need — turning their problem into
everyone's.

### NOT verified at the time — and the diagnosis below was WRONG. See the correction that follows.

The security integration tests are **written but unrun**. Mid-session the Docker daemon
stopped responding:

```
$ timeout 10 docker info
NO - daemon unresponsive          # hung, no containers, no error

$ ps -p 54187 -o etime=
20:10                             # the test task sat at :services:ingest-gateway:test
                                  # for 20 minutes with nothing to show
```

The eight tests added to `IngestEndToEndIT` — missing token, garbage token, **expired**
token, token with no tenant claim, and the body-cannot-override-the-tenant test — have
therefore never executed. They are committed unverified, and this note exists so nobody
mistakes "committed" for "passing".

To run them once Docker Desktop is restarted:

```bash
./gradlew :services:ingest-gateway:test --tests '*IngestEndToEndIT*'
```

The most important of the eight is `bodyCannotOverrideTheTenant`: a caller authenticated as
`streamflix` sends a body also claiming `tenantId: sportsnow`, and the record must still be
written as `streamflix`.

---

## Phase 2 (continued) — the correction, the breaker, and a data-loss bug

### CORRECTION: Docker Desktop was never broken

The entry above blamed an unresponsive daemon. **That was wrong**, and the wrong diagnosis
cost the rest of that session.

What was actually true: `docker info` responded fine (29.5.3). The build was pointing
Testcontainers at a **dead socket**.

The convention plugin pinned `DOCKER_HOST` to
`~/Library/Containers/com.docker.docker/Data/docker.raw.sock`, which had been the live
engine socket when that code was written. After a Docker Desktop upgrade it still exists
and still accepts `connect()` — and then never replies. Verified:

```
$ docker context ls
desktop-linux *   Docker Desktop   unix:///Users/…/.docker/run/docker.sock   ← the live one

$ curl -s --max-time 8 --unix-socket ~/.docker/run/docker.sock localhost/v1.44/version
{"Version":"29.5.3","ApiVersion":"1.54","MinAPIVersion":"1.40", …}          ✅ instant

$ curl -s --max-time 8 --unix-socket ~/Library/…/Data/docker.raw.sock localhost/v1.44/version
                                                                             ❌ hangs, no output
```

**The lesson worth keeping: a unix socket existing does not mean it answers.** The check
was `if (socket.exists())`, and a dead socket passes it. The failure mode is silence — no
error, no containers, no timeout — which looks exactly like a broken daemon and is not one.
That resemblance is what made the wrong diagnosis so easy, twice.

The fix orders the candidates by what Docker Desktop actually serves today
(`~/.docker/run/docker.sock` first, the historical path last) and records the curl probe in
the comment so the next person re-checks in ten seconds instead of losing an afternoon.
The comment already carried a `VERIFY: …may change between versions` note. It was right.

### Verified — the security integration tests, at last

```
$ ./gradlew :services:ingest-gateway:test --tests '*IngestEndToEndIT*'

IngestEndToEndIT > no token is rejected with 401                              PASSED
IngestEndToEndIT > a garbage token is rejected with 401                       PASSED
IngestEndToEndIT > an EXPIRED token is rejected — expiry is actually checked  PASSED
IngestEndToEndIT > a valid token with NO tenant claim fails closed            PASSED
IngestEndToEndIT > a malformed tenant claim is rejected before anything is published  PASSED
IngestEndToEndIT > the BODY cannot override the tenant — the token wins       PASSED
… 13 tests

BUILD SUCCESSFUL in 19s
```

Nineteen seconds, against twenty minutes of hanging. Same tests, same machine, one socket
path.

### A data-loss bug the circuit breaker work uncovered

Adding a breaker meant asking what happens to a batch that fails to publish. The answer was
worse than expected.

`IngestService` claims event ids in Redis (`SET NX EX`) **before** publishing. If the
publish then failed, the claims stayed behind. The client's retry found every id already
claimed, received `202 Accepted` with zero events published, and dropped its copy.

Silent, permanent data loss — reported to the caller as success. No metric moves, no alert
fires. A breaker makes it strictly worse: it fails fast for its whole 30-second open
window, so *every* batch in that window would be swallowed instead of the occasional one.

Fixed with `IdempotencyStore.release(...)` as a compensating action. A claim is a
**reservation**, and a reservation that is never used has to be given back.

Both directions are pinned by tests, so nobody deletes `release()` later as defensive code
that never fires:

```
IngestServiceTest > publish failures
  > REQ-IG-22: a failed publish leaves no claim behind, so the retry works       PASSED
  > without the release, that retry is silently swallowed -- the bug being guarded PASSED
```

The second test drives the fake store's `releaseFails` flag, which reproduces the pre-fix
behaviour exactly. It asserts the damage: `published == 0`, `duplicates == 3`, no exception.

### Verified — circuit breaker and time limiter (REQ-IG-21, REQ-RS-02, REQ-RS-06)

`ResilientEventPublisher` decorates any `EventPublisher` with
`CircuitBreaker ( TimeLimiter ( publish ) )`. Ten tests drive the full state machine.

**The ordering was verified by mutation, not by assertion.** Swapping the two decorator
lines and re-running:

```
THE ORDERING PROOF: a hanging broker times out, and the breaker counts it FAILED
    expected: OPEN
     but was: CLOSED
```

**Nine of the ten tests still passed with the wrong order.** Only the test written for that
specific property caught it. With the breaker inside the time limiter, a permanently
hanging dependency never trips it — which is the failure mode that matters most, because
with virtual threads there is no pool to exhaust and no thread-count alarm to trip, just a
growing pile of blocked threads each holding an in-flight permit.

This is the clearest demonstration so far of `interview-qa/14-testing.md` Q8: a passing
test suite proves the code passes the tests. Mutating the code is how you find out whether
the tests would have noticed.

### API names verified against the jars, not from memory

Every Resilience4j signature was checked with `javap` against the 2.3.0 jars in the Gradle
cache before being used. One was wrong:

```
❌  circuitBreaker.getCircuitBreakerConfig().getWaitDurationInOpenState()   does not exist
✅  circuitBreaker.getCircuitBreakerConfig().getWaitIntervalFunctionInOpenState().apply(1)
```

Resilience4j models the open-state wait as an `IntervalFunction` (attempt → millis), because
it also supports a wait that grows on each reopen. For a fixed wait, attempt 1 is the
configured value. Worth the two minutes: it would otherwise have been a compile error at
best, and a plausible-looking wrong answer in an interview at worst.

### Verified — Retry with backoff and jitter (REQ-RS-01)

Retry sits **outermost**: `Retry ( CircuitBreaker ( TimeLimiter ( publish ) ) )`, matching
the Resilience4j starter's documented aspect order.

Seven new tests. The two load-bearing claims were again checked by mutation rather than
taken on trust:

```
# moving retry INSIDE the breaker
THE ORDERING PROOF: the breaker counts every ATTEMPT, not every batch FAILED
    expected: OPEN
     but was: CLOSED

# swapping ofExponentialRandomBackoff for ofExponentialBackoff
backoff is JITTERED, so retries do not arrive in lockstep FAILED
    [a fixed backoff would produce only two distinct values]
    Expecting size of: to be greater than 2 but was 2
```

Retry outside the breaker means the breaker counts every *attempt*, so a dependency that
is failing outright trips it after 2 batches instead of 4. The tax is having to exclude
`CallNotPermittedException` — a 300ms retry budget cannot outwait a 30s open breaker, so
retrying it only delays the 503 the client needs.

**The rule worth remembering: retry is only safe for FAST failures.** Not "is this error
transient?" but "is it transient *and* cheap?". Every attempt holds the in-flight permit,
so retrying a 5s timeout turns one held permit into 15 seconds of held permit — multiplying
consumption of the very resource the breaker exists to protect, during the incident. So
timeouts are excluded, along with batch rejections that would fail identically anyway.

The exclusion list lives on `ResilientEventPublisher.retryPolicy(...)`, not in Spring
config: which of a publisher's failures are worth another attempt is knowledge about the
publisher. It also means the tests exercise the exact policy production runs, instead of a
copy that drifts.

One thing worth knowing: **retry makes duplicates more likely, and that is accepted**.
Kinesis `PutRecords` is not idempotent. Kafka's producer has `enable.idempotence` with a
producer id and per-partition sequence numbers so the broker can drop a retried duplicate;
Kinesis has no equivalent. Consistent with ADR-0007 — duplicates are absorbed downstream, a
gap is not.

### Full suite

```
$ ./gradlew build
BUILD SUCCESSFUL in 20s

  kata                      tests= 25 failures=0 errors=0
  libs/contracts            tests= 28 failures=0 errors=0
  libs/platform             tests= 39 failures=0 errors=0
  services/ingest-gateway   tests= 47 failures=0 errors=0
  TOTAL                     tests=139 failures=0 errors=0
```

### Still outstanding in Phase 2

Honest scope:

- **Resilience4j `Bulkhead`** (REQ-RS-05) — the in-flight `Semaphore` is already a
  semaphore bulkhead in effect; whether to replace it with the library's is a Phase 9
  decision, once there are measurements to justify either way.
- **gRPC adapter** — BS-09 / REQ-GR-01…07.
- **Keycloak** — tests use a symmetric-key `JwtDecoder` under a test profile. That proves
  expiry, signature and the tenant claim are checked; it does **not** prove Keycloak's JWKS
  rotation, issuer or audience validation. Those need the real issuer.
- **Actuator health groups** — configured in `application.yml`, never asserted by a test.
- **Breaker and retry numbers are guesses.** They are chosen to be explainable, not
  measured. Phase 9 replaces them.
