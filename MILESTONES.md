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
(commit `9d69e6a`); the katas were subsequently removed as out of scope, and the equivalent
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

The code remains in git history at commit `9d69e6a` if it is ever wanted, and the full
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

---

## Phase 2 (continued) — gRPC as a second ingest protocol · 2026-09-29 · ✅ core complete, benchmark not done

BS-09 / REQ-GR-01…07. A second inbound adapter over the **same** `IngestUseCase`, with the
parity test that makes "shared use case" a fact rather than a claim.

### What landed

- `libs/contracts/src/main/proto/telemetry.proto` — `StreamEvents` (bidi) and `SendBatch`
  (unary). No `tenant_id` field, deliberately: the tenant can only come from a signed token.
- `ProtoCodegenTask` in `build-logic/`, following the `AvroCodegenTask` precedent.
- `adapter/in/grpc/` — the service, a JWT `ServerInterceptor`, a status mapper, a Context key.
- `GrpcServerLifecycle` — a `SmartLifecycle` at phase 200, so gRPC stops accepting *before*
  the drain at phase 100 starts counting.
- `TenantResolver` moved `adapter.in.rest` → `adapter.in`: one tenant rule, two callers.
- `IngestArchitectureRulesTest` — the module's own ArchUnit rules (see the note below on why
  the ones in `libs:platform` could never have caught anything here).

### The protobuf Gradle plugin was never tried — and here is the evidence that decided it

`AvroCodegenTask` already documents why: the Avro plugin's Gradle 9 support was unverified in
Phase 1 and cost a day. Rather than repeat the experiment, protoc was driven directly. The one
thing that needed *discovering* was that a standalone protoc does not carry the well-known
types:

```
$ ./protoc.exe -I src --java_out=out src/probe.proto
google/protobuf/timestamp.proto: File not found.
probe.proto:5:1: Import "google/protobuf/timestamp.proto" was not found or had errors.
probe.proto:6:16: "google.protobuf.Timestamp" is not defined.

$ unzip -l protobuf-java-3.25.9.jar | grep '\.proto$'
     6449  google/protobuf/timestamp.proto          ← they ship INSIDE the runtime jar
     ... 10 more

$ ./protoc.exe -I src -I wkt --java_out=out \
      --plugin=protoc-gen-grpc-java=./protoc-gen-grpc-java.exe --grpc-java_out=out src/probe.proto
PROTOC OK
```

That probe ran in a scratch directory before a line of Gradle was written, which is why the
task worked first time:

```
$ ./gradlew :libs:contracts:generateProtoJava
> Task :libs:contracts:generateProtoJava
Generated protobuf + gRPC sources from 1 file(s) into …/build/generated/proto/java

BUILD SUCCESSFUL in 56s
```

### API names verified against the jars, not from memory

Same discipline as the Resilience4j work, same payoff — one guess would have been a coin flip.

```
$ javap -cp grpc-stub-1.84.0.jar io.grpc.stub.ServerCallStreamObserver
  public abstract boolean isCancelled();
  public abstract void setOnCancelHandler(java.lang.Runnable);
  public void disableAutoRequest();              ← the one manual flow control needs
  public abstract void request(int);

$ javap -cp grpc-stub-1.84.0.jar io.grpc.stub.CallStreamObserver
  public abstract void disableAutoInboundFlowControl();   ← the OLDER name, on the superclass
```

Both exist and they are not synonyms: `disableAutoInboundFlowControl()` is the older abstract
method on `CallStreamObserver`, `disableAutoRequest()` the current one on
`ServerCallStreamObserver`.

The shutdown phases quoted in ADR-0010 are read out of the Boot jar rather than guessed:

```
$ javap -constants -cp spring-boot-3.5.3.jar \
      org.springframework.boot.web.context.WebServerGracefulShutdownLifecycle
  public static final int SMART_LIFECYCLE_PHASE = 2147482623;

$ javap -c -cp spring-boot-3.5.3.jar \
      org.springframework.boot.web.servlet.context.WebServerStartStopLifecycle
  public int getPhase();
       0: ldc  #42   // int 2147481599
```

And the protobuf version is not a preference, it is a constraint read out of a POM:

```
$ curl -s …/io/grpc/grpc-protobuf/1.84.0/grpc-protobuf-1.84.0.pom | grep -A2 protobuf-java
      <artifactId>protobuf-java</artifactId>
      <version>3.25.9</version>          ← so protoc must be 3.25.9, not "latest"
```

### Verified — the gRPC adapter (24 tests, no Docker, 17s)

```
$ ./gradlew :services:ingest-gateway:test --tests '*Grpc*' --tests '*IngestArchitectureRules*'

IngestArchitectureRulesTest > inbound adapters go through the use case, never straight to an outbound adapter PASSED
IngestArchitectureRulesTest > the core carries no protocol types — neither gRPC nor servlet nor Spring web PASSED
IngestArchitectureRulesTest > the two inbound adapters do not know about each other        PASSED
IngestArchitectureRulesTest > adapters depend on the PORT, not on IngestService            PASSED
GrpcDeadlineCancellationTest > a client deadline reaches the use case: the gRPC Context is cancelled mid-call PASSED
GrpcDeadlineCancellationTest > an explicit client cancellation cancels the server-side Context too PASSED
GrpcIngestInProcessTest > StreamEvents > events are batched to the flush size, and each batch gets one ACK PASSED
GrpcIngestInProcessTest > StreamEvents > each ACK reports the last DURABLE sequence, so the client knows what to drop PASSED
GrpcIngestInProcessTest > StreamEvents > a partial final batch is still flushed when the client half-closes PASSED
GrpcIngestInProcessTest > StreamEvents > a failing batch closes the stream with the mapped status, not with UNKNOWN PASSED
GrpcIngestInProcessTest > authentication > a valid token with NO tenant claim fails closed  PASSED
GrpcIngestInProcessTest > authentication > no token is UNAUTHENTICATED — the gRPC equivalent of 401 PASSED
GrpcIngestInProcessTest > authentication > an EXPIRED token is UNAUTHENTICATED — expiry is really checked PASSED
GrpcIngestInProcessTest > authentication > the tenant comes from the TOKEN — the message has no field to override it PASSED
GrpcIngestInProcessTest > SendBatch > an empty batch is INVALID_ARGUMENT, not an empty success PASSED
GrpcIngestInProcessTest > SendBatch > a batch reaches the use case and the ack reports what was published PASSED
GrpcIngestInProcessTest > SendBatch > every field of the protobuf event survives the mapping to the domain PASSED
GrpcStatusMappingTest > quota exhausted → RESOURCE_EXHAUSTED, with RetryInfo in the trailers PASSED
GrpcStatusMappingTest > a malformed event id → INVALID_ARGUMENT, rejected at the boundary   PASSED
GrpcStatusMappingTest > gateway saturation → UNAVAILABLE, because another instance may well cope PASSED
GrpcStatusMappingTest > an unexpected failure → INTERNAL, and the message is NOT leaked     PASSED
GrpcStatusMappingTest > the event stream being unreachable → UNAVAILABLE, never INTERNAL    PASSED
GrpcStatusMappingTest > sub-second retry advice keeps its milliseconds                      PASSED
GrpcStatusMappingTest > an event id that is valid still reaches the use case                PASSED

BUILD SUCCESSFUL in 17s
```

These run against a **real in-process gRPC server** — real interceptors, real Context
propagation, real deadlines, real `Status` on the wire — with no sockets and nothing mocked
except the use case itself.

### Verified — protocol parity, against a real port, real Redis and moto

```
$ ./gradlew :services:ingest-gateway:test --tests '*ProtocolParityTest*'

ProtocolParityTest > PARITY: REST, gRPC unary and gRPC streaming produce identical records  PASSED
ProtocolParityTest > idempotency is shared: an eventId sent over REST is a duplicate over gRPC PASSED

BUILD SUCCESSFUL in 12s
```

Three front doors, one record shape on Kinesis. The second test is the sharper one: an
`eventId` accepted over REST comes back as a **duplicate** over gRPC — which is only true
because deduplication lives in the use case and neither adapter keeps state of its own.

### The parity test was proven by mutation, not by assertion

A passing test proves the code passes the test. The only way to learn whether it would notice
a real divergence is to introduce one. A single plausible-looking line in the gRPC mapper —
the sort of "harmless normalisation" that gets added in a hurry:

```java
e.getTitleId().trim().toUpperCase(Locale.ROOT),   // MUTATION
```

```
ProtocolParityTest > PARITY: REST, gRPC unary and gRPC streaming produce identical records FAILED
    org.opentest4j.AssertionFailedError: [titleId]
    expected: "title-42"
     but was: "TITLE-42"
        at ProtocolParityTest.allThreeProtocolsProduceIdenticalRecords(ProtocolParityTest.java:331)
```

Note what the failure message does: it **names the field**. That was worth the extra lines —
comparing the two Avro records as whole objects would have printed both and left the reader to
diff them by eye.

The mutation was reverted immediately. The point of the exercise is that this divergence has
**no other detector**: both adapters read correctly on their own, every unit test still
passes, and in production it surfaces months later as "why do the smart-TV numbers not match
the web numbers?".

### Why the module got its own ArchUnit test

`libs/platform/…/ArchitectureRulesTest` states the house rules, but ArchUnit reads compiled
**bytecode from the classpath** — and `libs:platform` does not depend on any service. Its
rules have never seen a single line of `ingest-gateway`, and never will. A rule that cannot
see the code it protects passes forever.

So the gateway now states its own, including the one BS-09 made necessary: nothing in
`..domain..` or `..application..` may depend on `io.grpc..` or `com.google.protobuf..`. A
second protocol is exactly when a hexagon quietly collapses.
- **Resilience4j `Retry` and `Bulkhead`** (REQ-RS-01, REQ-RS-05) — the circuit breaker and
  time limiter are done; retry with exponential backoff + jitter is not. The in-flight
  `Semaphore` is already a semaphore bulkhead in effect; whether to replace it with the
  library's is a Phase 9 decision once there are measurements.
- **gRPC adapter** — BS-09 / REQ-GR-01…07 not started.
- ~~**Keycloak**~~ — closed below.
- ~~**Actuator health groups**~~ — closed below.
- **Breaker thresholds are guesses.** They are chosen to be explainable, not measured.
  Phase 9 replaces them.

---

## Phase 2 (continued) — real OIDC, and probes that have been seen to move · 2026-09-29 · ✅

Closing the last two items above. Both were the same kind of gap: something was
**configured** and nothing had ever **observed it working**.

### What landed

**Real OIDC against Keycloak (REQ-IG-07/08/09, ADR-0011).** `KeycloakOidcIT` boots a real
Keycloak via Testcontainers, importing the *same realm file* `make up-core` mounts. Keycloak
is now in the `core` compose profile with `--import-realm`, so a fresh machine gets a working
IdP with no admin-console clicking.

**Health probes asserted (BS-01 / REQ-LC-01, REQ-LC-02).** `HealthProbesIT` needs no Docker
at all and pins the drain behaviour that BS-01 exists for.

### The thing worth understanding: what a symmetric test token can never prove

`TestJwtSupport` mints HS256 tokens with a 32-byte constant and hands the app a matching
`JwtDecoder`. That genuinely proves signature, expiry and the tenant claim — and it cannot
prove issuer, audience or key rotation, not because the tests are weak but because **in that
setup those things do not exist**:

```
  HS256   one shared secret   →  anyone who can VERIFY a token can also MINT one
                              →  no asymmetry, no trust boundary, nothing for `iss` to protect
                              →  and the test tokens carry no `aud` claim at all
```

A second thing the double was hiding: `application.yml` had **no** resource-server
configuration whatsoever — no `issuer-uri`, no `audiences`. Every test passed because
`TestJwtSupport` supplied the `JwtDecoder` bean. Started for real, the app would have failed
at context refresh with no decoder to build the filter chain from. The test double was not
just masking weak validation; it was masking the absence of any production configuration.

### Verified — the Keycloak suite

```
$ ./gradlew :services:ingest-gateway:test --tests '*KeycloakOidcIT*'

KeycloakOidcIT > the fixture itself > the realm imported, and its discovery document names the issuer Spring was given PASSED
KeycloakOidcIT > a real Keycloak token > REQ-IG-09: RS256, verified against a key this service never held, and the tenant claim wins PASSED
KeycloakOidcIT > a real Keycloak token > a key ROTATION is picked up with no restart, and tokens signed by the OLD key still verify PASSED
KeycloakOidcIT > tokens that must NOT be accepted > a token from a DIFFERENT ISSUER is rejected with 401 — identical claims, foreign key PASSED
KeycloakOidcIT > tokens that must NOT be accepted > a token with the WRONG AUDIENCE is rejected with 401 — same issuer, SAME signing key PASSED

BUILD SUCCESSFUL in 24s
```

Twenty-four seconds, of which ~0.8s is assertions and the rest is Keycloak booting (148
Liquibase changesets against H2). The first run on a cold machine also pays a ~450 MB pull.

### Each negative differs in exactly ONE claim

A test that rejects a token proves nothing unless you know *why*. Observed claim values, not
assumed:

| Case | `iss` | `kid` | `aud` | `tenant` |
|---|---|---|---|---|
| accepted | `…/realms/streampulse` | `QpUunJLwZdbL…` | `[streampulse-ingest, account]` | streamflix |
| wrong audience | `…/realms/streampulse` | **`QpUunJLwZdbL…`** | **`[streampulse-analytics, account]`** | streamflix |
| wrong issuer | **`…/realms/rogue-idp`** | **`dn_O5Tj5tSMb…`** | `[streampulse-ingest, account]` | streamflix |

The audience case is the strong one: **same realm means the same signing key**, so the
signature is genuinely valid and only `aud` can be responsible. The test asserts the `kid`
equality before asserting the 401.

The issuer case is honestly weaker and the comment says so: a foreign realm has its own
keypair, so the signature check fails *first* and `JwtIssuerValidator` is the second line of
defence rather than the one that fired. That ordering is correct — an unknown key should
never reach claim validation.

### Verified by mutation — the audience check

```
$ # delete `audiences` from application.yml and the test's DynamicPropertySource
$ ./gradlew :services:ingest-gateway:test --tests '*KeycloakOidcIT*'

KeycloakOidcIT > a token with the WRONG AUDIENCE is rejected — same issuer, SAME signing key FAILED
    expected: 401 UNAUTHORIZED
     but was: 202 ACCEPTED
5 tests completed, 1 failed
```

**Four of the five still passed.** A read-only analytics-dashboard credential became a valid
**write** credential for telemetry, and only the test written for that property noticed. Same
lesson as ADR-0009's decorator ordering, on different material.

### Verified — key rotation

A second `rsa-generated` provider at priority 200 (Keycloak's default is 100) becomes the
active signer. Observed directly against a live container before the test was written:

```
old kid: QpUunJLwZdbL1eeyoNQCpOO31XBdxT_wn4_RUYEge9s
new kid: ATO39U2IVLlg58SFlY_1Ex85YcTBPMW4vtGBCJ6m0ZM
jwks kids: ['ATO39U2IVLlg…', 'yebGopNonQEp…', 'QpUunJLwZdbL…']  count: 3
```

Both halves are asserted, and the second matters as much: the **old** key stays in the JWKS,
so tokens already in flight keep verifying. A rotation that invalidated them would be an
outage, not a rotation.

That the re-fetch is immediate was verified against the jar rather than assumed —
`spring-security-oauth2-jose` 6.5.1 builds its JWK source with `refreshAheadCache(false)` and
`rateLimited(false)`, so an unknown `kid` forces a fetch on the next request. If it were rate
limited this test would need polling, and saying so would be part of the result.

### Verified — health probes, with no Docker at all

```
$ ./gradlew :services:ingest-gateway:test --tests '*HealthProbesIT*'

HealthProbesIT > REQ-LC-01: a healthy pod answers both probes UP, and neither probe needs a token PASSED
HealthProbesIT > the liveness group contains ONLY livenessState — a dependency in here is a restart storm PASSED
HealthProbesIT > the readiness group contains ONLY readinessState, so the drain is what drives it PASSED
HealthProbesIT > REQ-LC-01: readiness goes OUT_OF_SERVICE during the drain, and liveness stays UP PASSED

BUILD SUCCESSFUL in 10s
```

### Verified by mutation — the drain

Comment out the one line in `IngestDrainLifecycle.stop()`:

```
$ # // AvailabilityChangeEvent.publish(context, ReadinessState.REFUSING_TRAFFIC);
HealthProbesIT > REQ-LC-01: readiness goes OUT_OF_SERVICE during the drain, and liveness stays UP FAILED
    expected: 503 SERVICE_UNAVAILABLE
     but was: 200 OK
4 tests completed, 1 failed
```

Three of four still passed. Without that line a pod keeps reporting itself ready for the
entire drain, so the load balancer keeps sending it work it has already decided to stop
taking — which is the exact failure BS-01 exists to prevent, and it was previously invisible.

### Verified by mutation — the liveness group, and a restart storm on camera

The assertion that the liveness group contains *only* `livenessState` is an assertion about
an **omission**, which is unusual enough to be worth proving is not vacuous. Adding Redis to
it (`include: livenessState,redis`) with no Redis running:

```
HealthProbesIT > REQ-LC-01: a healthy pod answers both probes UP … FAILED
     but was: 503 SERVICE_UNAVAILABLE
HealthProbesIT > the liveness group contains ONLY livenessState … FAILED
HealthProbesIT > REQ-LC-01: readiness goes OUT_OF_SERVICE during the drain … FAILED
     but was: 503 SERVICE_UNAVAILABLE
4 tests completed, 3 failed
```

**Liveness itself went 503** — which in Kubernetes means every pod gets restarted, simultaneously,
because of a dependency the ingest path deliberately fails *open* on (ADR-0007). The classic
outage amplifier, reproduced in ten seconds.

### Verified — `make up-core`, end to end, with no test doubles anywhere

```
$ make up-core && make seed-aws
NAME          IMAGE                            STATUS
sp-keycloak   quay.io/keycloak/keycloak:26.0   Up 18 seconds (healthy)   0.0.0.0:8081->8080/tcp
sp-moto       motoserver/moto:latest           Up 18 seconds (healthy)   0.0.0.0:5001->5000/tcp
sp-postgres   postgres:16-alpine               Up 18 seconds (healthy)   0.0.0.0:5432->5432/tcp
sp-redis      redis:7-alpine                   Up 18 seconds (healthy)   0.0.0.0:6379->6379/tcp
created stream telemetry-raw-v1

$ ./gradlew :services:ingest-gateway:bootRun        # real config, no @TestConfiguration

1. ingest-gateway token      aud=[streampulse-ingest, account]
   {"accepted":1,"duplicates":0,"batchId":"b883f2f9-…","degraded":false}   <- HTTP 202
2. analytics-dashboard token aud=[streampulse-analytics, account]  (SAME signing key)
   <- HTTP 401
      error_description="An error occurred while attempting to decode the Jwt: The aud claim is not valid"
3. no token
   <- HTTP 401
4. probes
   liveness  : {"status":"UP","components":{"livenessState":{"status":"UP"}}}  [HTTP 200]
   readiness : {"status":"UP","components":{"readinessState":{"status":"UP"}}}  [HTTP 200]
```

That 401's `error_description` is the best single line of evidence in this entry: the
resource server naming `aud` as the reason, for a token it otherwise accepted completely.

### Problems hit and resolved

**1. Keycloak rejects unknown JSON properties, so `_comment` keys do not survive an import.**
JSON has no comment syntax, and the realm file is exactly the kind of configuration that
needs prose next to it. `_comment` arrays were tried first and Keycloak 26.0.8 refused to
start:

```
ERROR: Failed to run import
ERROR: Unrecognized field "_comment" (class org.keycloak.representations.idm.RealmRepresentation),
       not marked as ignorable (144 known properties: …)
```

Worth an entry because the guess was plausible — plenty of tools ignore unknown keys — and
because checking cost one container boot rather than a confusing CI failure later. The
reasoning moved to ADR-0011 and to `KeycloakOidcIT`'s Javadoc, and the realm file leans on
`description` fields, which Keycloak *does* accept.

**2. Two realm files in the import directory: both import, then the server fails to start.**

```
INFO  Realm 'streampulse' imported
INFO  Realm 'rogue-idp' imported
ERROR: Failed to start server in (development) mode
ERROR: Session not bound to a realm
```

One file works. **Root cause not established** — recorded as observed behaviour on Keycloak
26.0.8, not as a diagnosis. Sidestepped rather than fought, and the workaround is better
design anyway: only the real realm is imported at boot, and `KeycloakOidcIT` creates the
adversary realm over the admin API. A test fixture in `docker/keycloak/realms/` would
otherwise boot in every developer's local stack.

**3. `bootRun` never had the moto CBOR workaround — pre-existing, found by actually running
the app.** The `aws.cborEnabled=false` system property was set on `tasks.withType<Test>` only.
So the tests had it and `bootRun` did not, and the first real request against `make up-core`
answered:

```
KinesisException: Service returned HTTP status code 500
  (Service: Kinesis, Status Code: 500, Request ID: null) (SDK Attempt Count: 4)
```

No mention of CBOR, of moto, or of a workaround that exists eight lines away in the same
build file. `--args='--aws.cborEnabled=false'` does not help either, because it is a **system**
property and not a Spring one — which looks like it should work and does not. Fixed by also
applying it to `tasks.withType<JavaExec>()` (`bootRun` is a `JavaExec`).

Unrelated to OIDC, and it would have stayed hidden indefinitely: the whole suite was green
the entire time. Running the thing is a different test from testing the thing.

### API names verified against the jars, not from memory

Nothing in this change was written from recall:

| Claim | How it was checked |
|---|---|
| `spring.security.oauth2.resourceserver.jwt.issuer-uri` / `.audiences` | `spring-configuration-metadata.json` inside `spring-boot-autoconfigure-3.5.3.jar` |
| `issuer-uri` ⇒ `withIssuerLocation` + `createDefaultWithIssuer` | `javap -c` on `OAuth2ResourceServerJwtConfiguration$JwtDecoderConfiguration` |
| `audiences` ⇒ `JwtClaimValidator("aud", …)` via `Collections.disjoint` | same bytecode |
| JWKS refresh is **not** rate limited | `javap -c` on `NimbusJwtDecoder$JwkSetUriJwtDecoderBuilder` → `rateLimited(false)` |
| `management.endpoint.health.group` | `spring-configuration-metadata.json` in `spring-boot-actuator-autoconfigure-3.5.3.jar` |
| `REFUSING_TRAFFIC` ⇒ `Status.OUT_OF_SERVICE` | `javap -c` on `ReadinessStateHealthIndicator` |
| `OUT_OF_SERVICE` ⇒ HTTP 503 | `javap -c` on `SimpleHttpCodeStatusMapper` static initialiser |
| Keycloak mapper ids, claim shapes, rotation | run against a live container and the tokens dumped |

### Why a plain GenericContainer rather than dasniko/testcontainers-keycloak

Checked before deciding: the module is real and actively maintained (Keycloak 26.7, Java 11+),
and its README states "no LTS, no backports — stay up to date."

Not adopted, for reasons recorded in ADR-0011. The decisive one is a resolution surprise found
while looking: this build does **not** get Testcontainers from the version catalog. The
catalog says `testcontainers = "1.21.3"`; the resolved jar is **1.21.2**, because
`io.spring.dependency-management` applies the Boot BOM with Maven-style precedence that
overrides Gradle's. Adding a library with its own opinion about that version puts a third
party into an already surprising resolution, for twenty saved lines.

> Follow-up worth doing: the catalog comment implies 1.21.3 is what runs, and it is not.
> Either drop the pin or add `enforcedPlatform`. Left alone here rather than changed in a
> commit about OIDC.

### Full suite

```
$ ./gradlew spotlessApply
BUILD SUCCESSFUL in 1s

$ ./gradlew build
BUILD SUCCESSFUL in 24s

  kata                      tests= 25 failures=0 errors=0 skipped=0
  libs/contracts            tests= 28 failures=0 errors=0 skipped=0
  libs/platform             tests= 39 failures=0 errors=0 skipped=0
  services/ingest-gateway   tests= 73 failures=0 errors=0 skipped=0
  TOTAL                     tests=165 failures=0 errors=0 skipped=0
```

26 of those are new here: 24 adapter/architecture plus 2 parity.

### NOT done in BS-09 — the honest list

- **REQ-GR-06, the benchmark.** No `ghz` run, so **every efficiency claim in ADR-0010 is an
  expectation, not a measurement**. "gRPC is cheaper for this workload" is the reason the
  protocol exists here, and it is currently unproven. Phase 9.
- **REQ-GR-01, `buf lint` / `buf breaking`.** Not wired into CI. The only gate today is that
  protoc parses the file — nothing stops someone renumbering a field, which is the one
  protobuf change that silently corrupts every existing client.
- **REQ-GR-05, W3C trace context.** JWT authentication is done; the tracing interceptor is
  not. Micrometer Tracing arrives in Phase 8, and inventing a propagation story before the
  rest of the system has one would be worse than leaving the gap visible.
- **REQ-GR-07, L7 load balancing.** Documented in ADR-0010 with the three real options and the
  exact `ServerBuilder` methods, but **nothing is configured** — no manifests, no mesh, no
  `maxConnectionAge`. Phase 11 owns the Kubernetes layer.
- **The REST/gRPC divergence on a missing tenant claim** (500 vs `UNAUTHENTICATED`). gRPC is
  right; changing the REST status is a contract change and belongs with the OpenAPI document.
- **`stream-flush-size: 50`** is explainable, not measured — like every other number in
  Phase 2.
- **Keycloak** still absent. The gRPC interceptor shares the symmetric-key test decoder with
  REST, which proves the decoder and the tenant rule are shared. It does **not** prove JWKS
  rotation, issuer or audience validation on either protocol.
$ ./gradlew clean build
BUILD SUCCESSFUL in 40s

  libs/contracts             tests= 28 failures=0 errors=0 skipped=0
  libs/platform              tests= 39 failures=0 errors=0 skipped=0
  services/ingest-gateway    tests= 49 failures=0 errors=0 skipped=0
  TOTAL                      tests=116 failures=0 errors=0 skipped=0
```

`services/ingest-gateway` 40 → **49**: five Keycloak tests and four probe tests.

The total reads lower than the 132 in the previous entry because that count still included
the `kata` module's 25 tests. `kata` is no longer in `settings.gradle.kts` (removed in Phase
0's scope change) and its stale results were being counted. 107 → 116 is the real delta.

**The fast suite stayed fast.** Only one of the nine new tests needs Keycloak's container, and
the four probe tests need no Docker at all:

```
  HealthProbesIT                  4 tests     5.2s      no containers
  IngestEndToEndIT               13 tests     4.0s      moto + redis  (unchanged)
  KeycloakOidcIT                  5 tests     0.8s      + ~23s Keycloak boot
```

`IngestEndToEndIT` and its symmetric-key tokens are untouched and still passing. They were
never the problem; believing they covered issuer and audience was.

### Still outstanding in Phase 2

- **Resilience4j `Retry` and `Bulkhead`** (REQ-RS-01, REQ-RS-05) — unchanged.
- **gRPC adapter** — BS-09 / REQ-GR-01…07 not started.
- **Breaker thresholds are guesses.** Phase 9 replaces them.
- **Authorization, as distinct from authentication.** The token now proves who the caller is
  and which API it was minted for. Nothing yet restricts what it may *do* within ingest —
  scopes and roles are untouched.
- **The realm is not a model of production identity.** `tenant` is a hardcoded mapper, client
  secrets are in git (local-only placeholders, same category as the Postgres password), and
  `sslRequired: none`. Phase 11.
- **The drain test calls `stop()` directly; no test sends a real SIGTERM.** It proves the
  readiness flip and the liveness non-flip, not the full `SmartLifecycle` phase ordering
  under an actual signal. `RollingRestartChaosIT` (BS-01's acceptance test) is still Phase 11.
- **`docker/keycloak/realms/` is imported, never exported.** A change made in the admin
  console is lost on `make nuke` and silently diverges until then. A `make export-realm`
  target would close that loop.

---

## Phase 2 — close-out · 2026-09-29

Both parallel branches merged into `main`. Phase 2 is complete apart from the items listed
at the end.

### CORRECTION: every test total before this entry was 25 too high

Earlier entries present a per-module table under a `$ ./gradlew build` heading, like this:

```
  kata                      tests= 25 failures=0 errors=0
  ...
  TOTAL                     tests=132 failures=0 errors=0
```

Two things are wrong with that, and both are mine.

**It was not Gradle's output.** Gradle does not print a per-module test table. Those numbers
came from a script that summed the JUnit XML under `**/build/test-results/`, formatted to
look like console output. Putting non-output under a `$ command` prompt is exactly what the
house rule against reconstructed output exists to prevent, even when the numbers are real.

**The numbers were also wrong.** `kata` was removed from `settings.gradle.kts` during Phase
0's scope change, but `kata/build/test-results/` was never deleted, so the script kept
counting 25 results from a build dated 28 Sep against a module that no longer exists.

| Entry said | Actually was |
|---|---|
| 132 (circuit breaker + timeout) | 107 |
| 139 (retry) | 114 |
| 165 (gRPC) | 140 |

`kata/build` has been deleted, and the counting script now reads the module list out of
`settings.gradle.kts` and ignores results from anything not in it.

Caught by the Keycloak agent, which noticed its own total had *dropped* to 116 and, instead
of quietly reporting the bigger number, worked out why. That is the behaviour that makes a
MILESTONES file worth reading.

### A defect found only by combining the two branches

Rebasing the Keycloak branch onto the gRPC work turned all four `HealthProbesIT` tests red:

```
org.springframework.context.ApplicationContextException: Failed to start bean 'grpcServerLifecycle'
    java.io.UncheckedIOException: gRPC server failed to bind port 9090
        java.net.BindException: Address already in use
```

Neither branch was wrong alone. The gRPC tests already pass
`streampulse.ingest.grpc.port=0`; the Keycloak tests were written before a gRPC server
existed, so they took the configured 9090.

The shape of it is the interesting part: **it cannot reproduce when you run either class on
its own**, because one context binds 9090 quite happily. It needs two `@SpringBootTest`
contexts in one JVM — so it shows up in a full build and in CI, and never while you are
working on the test that fails. Fixed in `444d84a`.

Worth generalising: anything that binds a fixed port is a shared global between test
contexts, the same way a static field is. `webEnvironment = RANDOM_PORT` handles HTTP; every
*other* listener needs the same treatment explicitly.

### Verified — the merged result

```
$ ./gradlew clean build
BUILD SUCCESSFUL in 46s
```

Test counts, read from the JUnit XML of the three modules in `settings.gradle.kts`
(reproduced here as a table, not as console output):

| Module | tests | failures | errors |
|---|---|---|---|
| `libs/contracts` | 28 | 0 | 0 |
| `libs/platform` | 39 | 0 | 0 |
| `services/ingest-gateway` | 82 | 0 | 0 |
| **TOTAL** | **149** | **0** | **0** |

That includes Testcontainers integration tests against moto (Kinesis), Redis and Keycloak.

### Still outstanding — carried into later phases

- **REQ-GR-06, the gRPC benchmark.** Not run, so **every efficiency claim in ADR-0010 is an
  expectation, not a measurement**. That is the whole reason the protocol was added here.
  Phase 9.
- **`buf lint` / `buf breaking` are not in CI.** Nothing stops someone renumbering a protobuf
  field, which is the one change that silently corrupts every existing client.
- **REQ-GR-05, W3C trace context over gRPC.** Auth interceptor done, tracing interceptor not.
  Phase 8, once the rest of the system has a tracing story to join.
- **REQ-GR-07, L7 load balancing.** Documented in ADR-0010; nothing configured. Phase 11.
- **Resilience4j `Bulkhead`** (REQ-RS-05). The in-flight `Semaphore` is already a semaphore
  bulkhead in effect; replacing it is a Phase 9 decision once there are measurements.
- **Authorization, as distinct from authentication.** The token proves who the caller is and
  which API it was minted for. Nothing restricts what it may *do* — scopes and roles untouched.
- **No test sends a real SIGTERM.** The drain test calls `stop()` directly, proving the
  readiness flip and the liveness non-flip, not the full `SmartLifecycle` ordering under an
  actual signal. `RollingRestartChaosIT` is Phase 11.
- **Every number in Phase 2 is explainable, not measured** — breaker thresholds, retry
  backoff, `stream-flush-size`, permit counts. Phase 9 replaces them with data.
- **REST/gRPC divergence on a missing tenant claim** (500 vs `UNAUTHENTICATED`). gRPC is
  right; changing REST is a contract change and belongs with the OpenAPI document.


---

## Phase 3 · Day 1 — Kafka becomes the backbone · 2026-10-12 · ✅

What changed: ADR-0012 (Kafka + Kafka Streams primary, supersedes ADR-0008); `streampulse.backbone`
property replaces the profile; Kafka publisher stamps the record with `occurredAt`; a shared
`AvroBinaryCodec`; an `integrationTest` Gradle task; compose and Makefile fixes.

### Real Kafka, default configuration (no backbone property)

```
KafkaIngestIT > the record timestamp is the event's occurredAt, so Kafka Streams gets event time for free PASSED
KafkaIngestIT > a batch lands on the default Kafka backbone keyed by sessionId, as decodable Avro PASSED
KafkaIngestIT > all events of one session land on one partition, in submission order PASSED
KafkaIngestIT > the producer is configured for idempotence and acks=all, the producer half of no loss / no duplicate PASSED
KafkaIngestIT > a client retry of the same batch publishes each event once PASSED
BUILD SUCCESSFUL in 16s
```

### Full suite

```
$ ./gradlew spotlessApply test integrationTest
BUILD SUCCESSFUL in 2m 17s
```

| Module | task | tests | failures | errors |
|---|---|---|---|---|
| `libs/platform` | test | 39 | 0 | 0 |
| `libs/contracts` | test | 32 | 0 | 0 |
| `services/ingest-gateway` | test | 58 | 0 | 0 |
| `services/ingest-gateway` | integrationTest | 29 | 0 | 0 |
| **TOTAL** | | **158** | **0** | **0** |

Was 149. The 9 new: 4 `AvroBinaryCodecTest`, 5 `KafkaIngestIT`. `ProtocolParityTest` was
renamed `ProtocolParityIT` so it runs under `integrationTest`; the 82 ingest tests split into
58 + 24 and the 5 new ones make 29.

### Things that went wrong, and what they were

1. **`apache/kafka:3.9.0` would not start under Testcontainers 1.21.2.** The container exited
   with code 1: `advertised.listeners cannot use the nonroutable meta-address 0.0.0.0`.
   Testcontainers configures a `0.0.0.0` listener; this image's start-up validates it before
   Testcontainers' script exports the advertised one. **3.9.1 starts.** I did not establish
   the root cause beyond that; I changed the tag and the test passed. Compose is pinned to
   3.9.1 as well, so the IT and `make up-core` run the same broker.
2. **Avro strings decode as `Utf8`.** Two of the first five assertions failed with
   `expected: "…" (String) but was: "…" (Utf8)`. The generated classes use Avro's default
   string type. This is a real hazard for Day 2: a `Utf8` never equals a `String`, so a
   grouping key built from one silently splits groups. Documented on `AvroBinaryCodec`; keys
   are built with `toString()`. The codegen was deliberately not changed.
3. **Gradle 9 rejects a `Test` task with no discovered tests**, which broke
   `:libs:contracts:integrationTest` (no `*IT` classes there). Fixed with
   `failOnNoDiscoveredTests = false`.
4. **`jacocoTestReport` read `integrationTest.exec` without declaring the dependency**, which
   Gradle refuses. Fixed with `mustRunAfter(integrationTest)`: an ordering, not a dependency,
   so the report still does not force the slow task to run.

### Honest gaps

- The record-timestamp assertion proves the broker **stored** `occurredAt`. It does not prove
  the broker's `log.message.timestamp.*` limits accept arbitrarily old events; the test event
  is from January and passed, which is evidence for this broker version only.
- The single broker cannot lose a replica. `acks=all` is asserted as configuration, not as
  behaviour under failure.
- `moto` is now pinned to `5.2.2` (was `latest`, which was a `5.2.3.dev` build). The Kinesis
  ITs passed against it; I did not compare behaviour with the old `latest`.


---

## Phase 3 · Days 2-5 — stream processor, sink, query API, load run · 2026-10-12 · ✅

Day 2 I built myself; Days 3 and 4 and the load generator were built by three agents working in
parallel in this tree (one module each); the docs by a fourth. **I re-ran every test myself from
scratch. The sink-writer and query-api mutation checks below are the agents' reports, not mine;
the Day 2 mutation checks I ran.**

### Everything, from scratch

```
$ ./gradlew spotlessApply test integrationTest --rerun-tasks
BUILD SUCCESSFUL in 2m 45s
```

```
module                      task               tests  skip  fail  err
libs/platform               test                  39     0     0    0
libs/contracts              test                  32     0     0    0
services/ingest-gateway     test                  58     0     0    0
services/ingest-gateway     integrationTest       29     0     0    0
services/stream-processor   test                  39     0     0    0
services/stream-processor   integrationTest        1     0     0    0
services/sink-writer        test                 148     0     0    0
services/sink-writer        integrationTest        8     0     0    0
services/query-api          test                  36     0     0    0
services/query-api          integrationTest       10     0     0    0
tools/loadgen               test                  60     0     0    0
TOTAL                                            460     0     0    0
```

Was 158 at the end of Day 1.

### Day 2 · services/stream-processor

`TopologyTestDriver` (in memory, no broker) proves the logic. `StreamProcessorIT` runs the real
app on a real broker with `processing.guarantee=exactly_once_v2`: one final record per metric, a
retried duplicate counted once, garbage bytes on the DLT with `dlt-reason=UNDECODABLE`.

Mutation checks, run by me, restored after each:

| Mutation | Result |
|---|---|
| remove `suppress(untilWindowCloses)` | 8 `TelemetryTopologyTest` tests failed |
| grace period 5 s to 0 | 2 failed (`outOfOrderWithinGraceIsCounted`, `noOutputBeforeWindowCloses`) |
| deduplicator never remembers an id | 1 failed (`duplicateEventIdCountedOnce`) |

Things that went wrong:
- **Bean name clash.** Spring Boot's `SystemMetricsAutoConfiguration` already defines a bean named
  `processorMetrics` (CPU metrics). Mine failed the context at startup. Renamed `pipelineMetrics`.
- **`@TempDir` and RocksDB.** JUnit deletes a `@TempDir` when the class ends; Spring closes the
  context later, so the RocksDB flush logged errors against a deleted directory. The test passed
  anyway, which is why it was easy to miss. State dir moved under `build/`.
- Event ids must be UUIDs (the platform `EventId` rule, reused by the validator). My first test
  fixtures used `e-1` and were rightly rejected.

### Days 3-4 · services/sink-writer, services/query-api (agent reports; tests re-run by me)

- **sink-writer:** `BatchWriter` (J5); Kafka to Postgres to Redis to ack; `NoLossNoDoubleCountIT`
  (a fault injected after the durable write and before the ack, three times: 60 rows with
  unchanged values, committed offsets sum 60, empty DLT).
  **Finding worth keeping:** with the lock removed, the randomised `@RepeatedTest(100)` race test
  stayed **green** (16 and 48 threads). Only a deterministic test that stalls `offer` went red.
  A randomised concurrency test can give false confidence. And `NoLossNoDoubleCountIT` passed on
  its first run, so the agent made the upsert non-idempotent to prove it can fail: it did.
- **query-api:** J6 `TitleOverviewService` (parallel fan-out inside one 300 ms deadline on a
  bounded, rejecting executor); SSE with replay-then-live, heartbeat and coalescing. Agent's
  mutation: removing `.orTimeout` turned `slowCallIsDegradedAndTheBudgetHolds` red.
- Gotchas the agents hit: `@KafkaListener(id=...)` silently becomes the consumer group id (it
  would have orphaned committed offsets); under Boot 3.5 the Netty threads are named
  `webflux-http-nio-N`, not `reactor-http-nio-N`, so a "not on the event loop" assertion using the
  old name passes vacuously; the flusher blocked in `poll` and nothing woke it on `close()`.

### Day 5 · the whole pipeline, running

Not unit tests: `make up-core`, the four services on the host, `tools/loadgen` sending real HTTP
with a real Keycloak token. Single Kafka broker, one laptop. Nothing here is production.

```
loadgen: target 1500 events/s for 90 s, batch 100, 5000 sessions, late fraction 0.05 -> http://localhost:8080
wall time: 90.06 s
batches sent: 1351   offered events/s: 1500.1   accepted events/s: 1500.1
responses: 202=1351 429=0 503=0 other=0 io-errors=0
ingest latency (202 only): p50=50.0 ms p95=57.0 ms p99=68.7 ms
max send lag behind schedule: 28 ms
freshness p99: 5.71 s (estimated from histogram buckets; observations made during this run)
 windows | min_s | p50_s | max_s 
---------+-------+-------+-------
       9 |   5.2 |   5.3 |   5.4
(1 row)

rows=3600 windows=9 viewers_sample_count_sum=125400
sink-writer lag (commit markers): 6
raw DLT records: 0
```

Per-window cross-check, straight from Postgres (`computed_at` minus `window_end`):

```
 windows | min_s | p50_s | max_s
---------+-------+-------+-------
       9 |   5.2 |   5.3 |   5.4
```

So steady-state freshness is the 5 s grace period plus about 0.2 to 0.4 s of pipeline. The
loadgen's histogram estimate (5.71 s) agrees. True event-to-visible is that plus up to 10 s of
window.

By hand on the running stack: `GET /v1/titles/title-11/overview` returned 200 in 0.17 s with
metadata and latest values; a request with no `tenantId` returned 400; the SSE endpoint with
`Last-Event-ID: 0-0` replayed entries from the Redis stream.

**Found only by running it:**
1. **Query-api's live feed failed every idle poll** (`Redis command timed out`, forever). The
   stream receiver's blocking read defaults to 2 s, and the Redis command timeout was also 2 s.
   Every unit and integration test finished faster than the timeout, so none could see it. Fixed
   by deriving the block time as half the command timeout; `LiveFeedIdleIT` is the regression
   test (it failed for the right reason first: non-zero `readFailures()`).
2. **My first freshness number was wrong.** p99 came out at 39.5 s. Postgres showed 10 windows for
   a 90 s run (expected 9): one was left unclosed by my earlier smoke run and was finally emitted
   36 s after its end when the next run advanced stream time. Re-measured after a 20 s warm-up that
   flushes leftovers; the output above is the second run. This is the quiet-stream limit in
   ADR-0012 appearing for real: the last window of a run never closes until later events arrive.
3. **Consumer lag of 1 on every partition at rest.** Consistent with the transaction commit
   marker that exactly-once writes. I did not dump the log to confirm.

### Honest gaps

- **1,500 events/s is the rate I offered, not a ceiling.** Ingest's per-tenant quota refills at
  2,000 events/s, so higher rates mostly measure 429s. The saturation point, the 5xx ratio and
  the 429 rate at saturation were not measured.
- **Not measured:** batch flush latency, peak lag and drain time, overview p99 under load,
  concurrent SSE streams per instance, the lz4 ratio, any MVC-vs-WebFlux comparison.
- **No end-to-end event accounting.** The closed windows hold 125,400 viewer samples against
  135,100 events sent, with the last 1 to 2 windows still open. The pieces are asserted
  separately (dedup in the topology tests, `NoLossNoDoubleCountIT` in the sink), not as one
  end-to-end invariant over a run.
- Not tested: a processor crash mid-window; the Redis-down retry loop in query-api (observed by
  hand only); the order of a real SIGTERM shutdown.
- `api/openapi/ingest-v1.yaml` still describes saturation as 429; ingest returns 503 (429 is the
  per-tenant quota only). Not changed.
- Query-api has no authentication. Single broker, replication factor 1. No outbox, no schema
  registry, no Kubernetes.

---

## Phase 10 — AWS simulation & Terraform (archive compactor + IaC) · 2026-10-19 · ✅ core complete

Code and specs (`specs/10-aws-terraform/`, ADR-0019, ADR-0020) were already in the tree from an
earlier session; this entry is the close-out that was skipped — rerunning every proof and
pasting real output, per the process in `CLAUDE.md`.

**Archive compactor** — `./gradlew :functions:archive-compactor:test :functions:archive-compactor:integrationTest`,
all green, 0 failures / 0 skipped:

```
CompactorFunctionWiringTest        tests=2  failures=0  skipped=0
CompactorArchitectureRulesTest     tests=4  failures=0  skipped=0
CompactArchiveFunctionTest         tests=4  failures=0  skipped=0
S3ArchiveStoreTest                 tests=3  failures=0  skipped=0
NdjsonMergerTest                   tests=8  failures=0  skipped=0
ArchiveKeyTest                     tests=13 failures=0  skipped=0
CompactionPlannerTest              tests=12 failures=0  skipped=0
CompactorTest                      tests=16 failures=0  skipped=0
ArchiveCompactionIT (integration)  tests=5  failures=0  skipped=0
S3ArchiveStoreIT (integration)     tests=6  failures=0  skipped=0
```

**Terraform** — `make tf-validate`: `terraform fmt -check -recursive` clean, `validate` succeeds
for every module (`kinesis`, `dynamodb`, `s3`, `sqs`, `iam`, `msk`, `vpc`, `eks`) and both envs.

Against a freshly started moto (`docker compose --profile core up -d moto`, host port 5001),
`envs/local`:

```
$ terraform plan
Plan: 13 to add, 0 to change, 0 to destroy.

$ terraform apply tfplan.local
Apply complete! Resources: 13 added, 0 changed, 0 destroyed.
```

Read back with the AWS CLI against moto: Kinesis stream `telemetry-raw-v1` ACTIVE with 2 shards;
SQS queues `alerts` and `alerts-dlq`, with `alerts`' `RedrivePolicy` confirmed as
`{"deadLetterTargetArn": "...alerts-dlq", "maxReceiveCount": 5}`; S3 bucket
`streampulse-raw-archive-local`; DynamoDB table `streampulse-aggregate-history`; 6 IAM policies
(`streampulse-ingest-gateway`, `-alerts-producer`, `-alerts-consumer`, `-archive-compactor`,
`-history-writer`, `-history-reader`).

Second `plan` (no `-out`, no reapply) reproduces the documented emulator drift exactly:

```
Plan: 0 to add, 3 to change, 0 to destroy.
```
— tags on the queue and a whitespace-only `redrive_policy` re-serialization on `alerts`; both are
moto re-encoding what it already stored, not real drift.

`terraform destroy`: `Destroy complete! Resources: 13 destroyed.` AWS CLI confirms every
Kinesis/SQS/S3/DynamoDB/IAM listing back to empty.

### Honest gaps (unchanged from `specs/10-aws-terraform/tasks.md`)

- `msk`, `vpc`, `eks` are validate-only: never planned or applied against anything, moto or real.
- DynamoDB Local (vs moto) still cannot be used with provider 6.68.0 — `apply` never returns;
  cause unproven. The table above was created against moto, not DynamoDB Local.
- Point-in-time recovery on the DynamoDB table is untested; the block is off locally.
- No service actually writes to the raw archive bucket or the aggregate-history table — both
  modules describe a target, not a wired producer.
- The compactor is not deployed as a real serverless function; it runs in-process, in tests only.
- Memory-boundedness of the compactor (streaming in, streaming out) is by construction, not
  measured under load.
- `make tf-validate` / `tf-local-plan` / `tf-local-apply` / `tf-local-destroy` /
  `compactor-test` targets exist in the Makefile already (contrary to the earlier note in
  `tasks.md` saying none were added — that note is now stale).

---

## Phase 6 — Control plane, Debezium CDC, alert engine · 2026-10-19 · ✅ core complete

Same situation as Phase 10: code, tests and specs (`specs/06-control-plane/`,
`specs/06-alert-engine/`, ADR-0015, ADR-0016, ADR-0017) were already in the tree; this is the
close-out — rerunning the proofs and pasting real output.

**control-plane** — `./gradlew :services:control-plane:test :services:control-plane:integrationTest`,
23 test classes, 102 tests, 0 failures / 0 skipped across the board (domain, `TenantHierarchy`
cycle prevention, `DependencyGraph` Kahn sort, `AvroConfigChangeEncoder`, application services,
controllers, `FlywayMigrationIT`, `OutboxAtomicityIT` incl. the two mutation checks (T6-11, T6-13),
`TenantTreeQueryCountIT` / `AlertRuleQueryCountIT` (N+1 fixed to <=3 / <=1 queries),
`TransactionBehaviourIT`, `ControlPlaneApiIT`, `ControlPlaneWiringTest`).

**alert-engine** — `./gradlew :services:alert-engine:test :services:alert-engine:integrationTest`,
30 test classes, 191 tests, 0 failures / 0 skipped (`RuleGraphValidator` cycle detection,
`DependencyGraph` levels, threshold/rate-of-change/absence evaluators, `AlertStateMachine`,
`RuleBook`, `AlertEngine` dedupe/resolve/refire/suppression (BFS across chains and diamonds),
`AlertOutbox`/`AlertProcessor`, `AlertFeed` ordering/replay/overflow, hexagonal architecture rules,
Kafka/SQS adapters, `AlertsApiContractTest`/SSE, `AlertEngineIT` against real Kafka 3.9.1 + moto).

**CDC proven live** (T6-18), redone by hand against a fresh stack:

```
$ make up-cdc                                   # Kafka, Postgres, Kafka Connect/Debezium
$ ./gradlew :services:control-plane:bootRun     # migrates control_plane schema, starts on :8085
$ make register-outbox-connector                # POST outbox-connector.json -> Connect
$ curl localhost:8088/connectors/control-plane-outbox/status
{"connector":{"state":"RUNNING"},"tasks":[{"state":"RUNNING"}]}
```

Through the REST API: `POST /v1/admin/tenants` -> 201, `PUT /v1/admin/tenants/{id}` -> 200,
`DELETE /v1/admin/tenants/{id}` -> 204. Consuming `config.changes.v1` from the start of the topic:

```
id:...,operation:CREATED,entityKind:TENANT   key=tenant/cdc-verify-tenant
  value: <Avro envelope> {"entityKind":"TENANT","tenantId":"cdc-verify-tenant",
         "displayName":"CDC Verify","monthlyEventQuota":1000,"parentTenantId":null,"version":0}
id:...,operation:UPDATED,entityKind:TENANT   key=tenant/cdc-verify-tenant
  value: <Avro envelope> {... "displayName":"CDC Verify Updated","monthlyEventQuota":2000,"version":1}
id:...,operation:DELETED,entityKind:TENANT   key=tenant/cdc-verify-tenant
  value: null   # tombstone
```

REST write -> Postgres outbox row -> Debezium -> `EventRouter` SMT -> Avro-wrapped JSON on
`config.changes.v1`, with a tombstone on delete, confirmed end to end. (The topic also still held
events from an earlier, unrelated session — `streamflix`, `late` — left untouched.)

### Honest gaps (unchanged from the two `tasks.md` files)

- Both modules' domain/service/adapter code was written alongside its tests in the same pass, so
  the red-first step was not observed run by run; what *was* run and is in the test suite is the
  mutation checks (control-plane: outbox-on-its-own-connection, tree-reader-without-fetch;
  alert-engine: BFS suppression removed, idempotence guard removed — all verified red, then green).
- Alert engine does not restore open alerts from `alerts.v1` on startup (T6B-18, cut; see
  ADR-0017) — a restart currently starts from empty state.
- `OutboxCdcIT` (Testcontainers + Debezium, as opposed to the compose-based proof above) is
  marked "if it can be made to run" in `tasks.md`; not rerun here.
- No authentication on either service's REST API. Single Postgres instance, no replication.

---

## Phase 7 — React dashboard · 2026-10-19 · ✅ core complete

Same gap as phases 6/10/12/13: code and `specs/07-web/` (21/21 tasks ticked) were already in the
tree, all in `eb230a3`, with no `MILESTONES.md` entry. Rerun fresh in this session:

```
$ npm run typecheck      # tsc --noEmit
(clean, no output)

$ npm run test           # vitest run
Test Files  25 passed (25)
     Tests  258 passed (258)

$ npm run e2e            # build + Playwright, against mocked backends only
17 passed (12.2s)
```

The e2e run needed `npx playwright install chromium` first — the browser binary was not present
on this machine; once installed, all 17 specs passed, including the axe accessibility scan
(`lists the tenant rules ... passes the accessibility scan`) and a keyboard-only navigation test.

Covers: SSE client with reconnect/backoff and `Last-Event-ID` resume, live metrics chart, alert
feed, rule-admin CRUD against control-plane/alert-engine contracts (with optimistic-concurrency
and dependency-cycle 422 handling), OIDC/PKCE login flow, tenant switching.

### Honest gaps (from `web/README.md`, unchanged)

- Every test — unit and e2e — stubs `fetch`/the network. Nothing here has been run against the
  real backends (query-api, control-plane, alert-engine) in this session or, per `web/README.md`,
  any prior one.
- The dev proxy to the real backends was checked once by hand, not retested here.
- Keycloak login has never been exercised live; sign-in is off by default.

---

## Phase 12 — Activity feed · 2026-10-19 · ✅ core complete

Same gap again: `specs/12-activity-feed/` and the service were already built in `eb230a3`.
`tasks.md` shows only T12-01 ticked, but every other listed task maps to code and a passing test
that already exists — rerun fresh in this session because the on-disk test results for one class
(`WatchFeedIT`) were missing/ambiguous-by-timestamp:

```
$ ./gradlew :services:activity-feed:test :services:activity-feed:integrationTest
BUILD SUCCESSFUL
```

23 test classes, **165 tests, 0 failures, 0 skipped** (domain: `UserId`, `FeedEntry`,
`FanOutPolicy`, `Ranker`, `FeedMerger`, `FeedPager`, `CursorCodec`; architecture rules; application
services (`FanOutService`, `FeedService`, `FollowService`, `WatchService`); Kafka adapters
(`WatchEventDecoder`/`Listener`); three web controllers; `JdbcFollowGraphIT` — concurrent-follow
and self-follow-rejection cases pass under real concurrency; `RedisTimelineStoreIT` — trim,
dedup-by-event-id, snapshot-bounded reads; `FeedWiringTest`; `WatchFeedIT`).

### Honest gaps (from `tasks.md`'s "Not built", unchanged)

- No engagement counters, no auth, no rate limits, no inactive-user skipping.
- No real producer of the watch signal — `WatchEvent` is synthetic in tests, nothing upstream
  emits it yet.

---

## Phase 13 — MCP server / AI tooling · 2026-10-19 · ✅ core complete

Same gap again: `specs/13-mcp/` (17/19 tasks ticked; the two open are this write-up and an
explicitly-cut stretch goal) and the service were already built in `eb230a3`. Gradle reports the
existing results as current (`UP-TO-DATE`, not rerun):

```
$ ./gradlew :services:mcp-server:test :services:mcp-server:integrationTest
UP-TO-DATE
```

14 test classes, **87 tests, 0 failures, 0 skipped**, including `PromptInjectionTest`,
`McpArchitectureRulesTest`, `McpProtocolIT`, `StdioProtocolIT`. This is the same server already
registered as this session's `streampulse` MCP tools (`list_active_alerts`, `explain_alert`,
`get_live_metrics`) — demonstrably live, not just compiled.

### Honest gaps (from `tasks.md`, unchanged)

- T13-19, an LLM incident summarizer, was cut and never built.

---

## Phase 9 · T9-02 — fault-injection harness (toxiproxy) · 2026-10-19 · 🚧 in progress

Red first (no proxy running):

```
$ ./docker/toxiproxy/chaos-check.sh
== toxiproxy API
FAIL: toxiproxy API not reachable on :8474
```

After adding `toxiproxy` (`chaos` profile), a `CHAOS` Kafka listener and the Makefile targets:

```
$ make up-chaos && make chaos-check
== toxiproxy API
   kafka, postgres, redis defined
== kafka advertises the proxy to clients that bootstrap through it
   advertised: localhost:29092
== kafka through the proxy
   reachable
   unreachable once disabled
   recovered once re-enabled
== postgres through the proxy
   reachable
   unreachable once disabled
   recovered once re-enabled
== redis through the proxy
   reachable
   unreachable once disabled
   recovered once re-enabled
PASS: all three proxies carry traffic and their faults bite
```

**Found by building it:** a plain TCP proxy in front of Kafka only intercepts the *bootstrap*
call. The broker then hands clients its advertised address, and a long-lived client talks there
directly. My first check used a fresh client per assertion, so it could not see that bypass. The
fix is a dedicated `CHAOS` listener advertising `localhost:29092`, plus a check on the advertised
address. Mutation, done through the toxiproxy API with no file edit:

```
$ curl -X POST localhost:8474/proxies/kafka -d '{"upstream":"kafka:19092"}'
$ ./docker/toxiproxy/chaos-check.sh
FAIL: broker advertises 'kafka:19092', not localhost:29092: clients would bypass the proxy
```

Restored to `kafka:29093` → green again. The normal path is unaffected: `kafka` healthy on
`:9092`, `kafka-init` exit 0 with all five topics created over `:19092`.

### Honest gaps

- This proves the harness, not any service's behaviour. No service has been run through the
  proxies yet; that is T9-30 onwards.
- Only `enabled: false` (a hard partition) is exercised. Latency, bandwidth and timeout toxics are
  not yet used by anything.

---

## Phase 9 · T9-10 — saturation search in loadgen · 2026-10-19 · 🚧 in progress

The existing `Runner` obeys `Retry-After`, so at saturation it throttles itself and the offered rate
falls: the Phase 3 run could never have found a ceiling. A ramp therefore needs a no-backoff
mode; otherwise it measures the load generator's politeness.

New: `--ramp-to`, `--ramp-step`, `--step-duration`. `--rate` is the first step. The search stops at
the first step whose error rate exceeds 1% (a target for what counts as saturated), names the
dominant cause (429 quota vs 503 capacity vs other), and prints a warning when the client could
not offer the rate it was asked for, since a breach there describes the load generator.

Red first: `./gradlew :tools:loadgen:test` failed to compile (`RampPlan`, `StepResult`,
`SaturationFinder`, `RampRunner`, `RampReportFormatter`, `ArgParser.parseRamp` and the six-argument
`Runner` constructor did not exist). Then green:

```
$ ./gradlew :tools:loadgen:spotlessApply :tools:loadgen:test
BUILD SUCCESSFUL
tests=88 skipped=0 failures=0 errors=0
  RampPlanTest 5   SaturationFinderTest 10   RampRunnerTest 4   RampReportFormatterTest 3
  RunnerTest 5     ArgParserTest 18
```

Mutations, each restored afterwards:

| Mutation | Test that went red | Evidence |
|---|---|---|
| `Runner.pause` ignores the no-backoff flag | `RunnerTest.keepsOfferingTheRequestedRateWhenToldNotToBackOff` | `Expecting actual: 2L to be greater than: 10L` (about 16 requests expected) |
| breach test `>` changed to `>=` | `SaturationFinderTest.exactlyOnePercentIsNotABreachButJustOverIs` | failed on the boundary |
| stop-at-first-breach removed | `RampRunnerTest.stopsAfterTheFirstBreachInsteadOfPushingAnAlreadyFailingServerHarder` | failed |

### Honest gaps

- **Not run against a real ingest-gateway.** Every test uses a fake step executor or a stub HTTP
  server. No saturation number exists yet; that is T9-11.
- REQ-RL-02 (shed load, do not grow unbounded queues or memory) cannot be shown from the client.
  It needs the ingest JVM's own metrics during the ramp; T9-11 carries that, and the spec's
  failure-mode row now says so instead of naming a test that was never going to prove it.
- The 1% limit and the 90% client-limited cutoff are targets/heuristics, not measured values.
- A ramp skips the freshness scrape that the single run does.

---

## Phase 9 · T9-11 — first saturation measurements of ingest · 2026-10-19 · 🚧 in progress

**Hardware and setup (every number below is from this one machine):** Apple M2 Pro, 12 cores,
32 GiB, macOS 27.0.1, OpenJDK 21.0.12. ingest-gateway run with `bootRun` on the host, Kafka
(single broker, replication factor 1), Redis, Postgres, Keycloak and moto in Docker, and the
loadgen on the same machine, so it competes with ingest for CPU. **Only ingest and its Kafka
producer were under test:** no stream-processor, sink-writer or query-api was running. Batch 100,
1,000 sessions. Each run was preceded by a 20 s warm-up at 500 events/s (the cold run's p95 was
1,587 ms, the warm one's 82 ms).

### Run A — default config (per-tenant quota: refill 2,000 events/s, burst 10,000)

```
$ LOADGEN_CLIENT_SECRET=... ./gradlew :tools:loadgen:run --args="--rate 1000 --ramp-to 4000 --ramp-step 500 --step-duration 20"
target/s  offered/s    202    429    503  other   errors  latency (202 only)
    1000     1002.6    200      0      0      0     0.0%  p99=74.9 ms
    1500     1501.1    301      0      0      0     0.0%  p99=153.5 ms
    2000     2000.4    400      0      0      0     0.0%  p99=63.6 ms
    2500     2499.6    499      1      0      0     0.2%  p99=64.2 ms
    3000     3002.4    400    201      0      0    33.4%  p99=67.8 ms
saturated at 3000 events/s (error rate over 1%), mostly 429 (per-tenant quota working as designed)
last clean rate 2500 events/s
```

This is the quota, not capacity: with a 2,000/s refill, 3,000/s offered should see 1 - 2000/3000 =
33.3% refused, and the loadgen saw 33.4%. The 1 throttled batch at 2,500/s is the 10,000-event
burst allowance running out. The ramp separated the two causes as REQ-RL-01 asks.

### Run B — quota effectively removed, to find capacity

Ingest restarted with `STREAMPULSE_INGEST_QUOTA_REFILL_PER_SECOND=100000000` and
`STREAMPULSE_INGEST_QUOTA_CAPACITY=1000000000` (environment only, no file edit). No 429 appeared
at any step, which is how I know the override bound.

```
$ ... --args="--rate 5000 --ramp-to 50000 --ramp-step 5000 --step-duration 20"
target/s  offered/s    202    429    503  other   errors  latency (202 only)
    5000     4998.3   1000      0      0      0     0.0%  p99=60.2 ms
   10000     9979.2   2000      0      0      0     0.0%  p99=74.8 ms
   15000    14949.9   3001      0      0      0     0.0%  p99=153.2 ms
   20000    19954.9   4000      0      0      0     0.0%  p99=162.3 ms
   25000    24951.0   5000      0      0      0     0.0%  p99=195.3 ms
   30000    29921.9   6001      0      0      0     0.0%  p99=92.2 ms
   35000    34916.1   7001      0      0      0     0.0%  p99=111.0 ms
   40000    39895.2   8000      0      0      0     0.0%  p99=107.8 ms
   45000    44357.2   7943      0   1058      0    11.8%  p99=595.1 ms
saturated at 45000 events/s (error rate over 1%), mostly 503 (the service running out of capacity)
last clean rate 40000 events/s
```

On this setup, ingest sustained 40,000 events/s with no errors and began refusing with 503 at
45,000 events/s. The loadgen offered 44,357/s of the 45,000 asked (98.6%), so it was not the
limit at the breach step. `NFR-IG-02` (target 5,000 events/s locally) is met with a wide margin
for ingest alone; that target is not yet replaced by this number (T9-50).

### REQ-RL-02: what the ingest JVM did while refusing

Sampled every 2 s from `/actuator/prometheus` through the whole of run B (93 samples):

```
heap used     45.5 .. 313.0 MiB   (rises and falls: 131.7, 313.0, 240.9, 200.1, 216.1 during the 503 phase)
threads live  34 .. 35
503'd events  0 -> 105,800        (the loadgen counted 1,058 x 503 x 100 events: the two agree exactly)
```

While 105,800 events were being refused, heap moved up and down and live threads stayed at 35.
That fits shedding by rejection rather than by queueing, but it is 16 s of overload, which is too
short to rule out a slow leak, and I did not read the JVM's configured maximum heap.

### Found by running it

1. **The first measurement would have measured the quota.** Run A is the evidence: a default-config
   ramp always stops at the per-tenant quota (about 2,500 to 3,000 events/s) and says nothing about
   capacity. Capacity needed the quota lifted.
2. **My first sampler used metric names I had guessed.** The real ones are
   `streampulse_ingest_events_rejected_events_total` (503) and
   `streampulse_ingest_events_throttled_events_total` (429). It printed zeros until I read the
   endpoint instead of assuming.
3. **The circuit breaker did not open.** After run B, `resilience4j_circuitbreaker_calls_seconds_count`
   shows 44,046 successful and 0 failed calls for `eventPublisher`. So the 503s did not come from
   the breaker (ADR-0009).

### Honest gaps

- **The mechanism behind the 503s is not proven.** The breaker is ruled out by its metrics. In the
  code, `IngestService` returns 503 when no in-flight permit (64, `permitWait` 250 ms) frees up in
  time, and `IngestExceptionHandler` has three other 503 paths. By elimination the permit bound is
  the likely cause, but I did not see the response body or a log line. A rough estimate from the
  mean publish time (about 16 ms) would put only a handful of permits in use at 450 batches/s,
  which does not obviously explain it; the p99 of 595 ms at the breach step may. Not resolved.
- **Ingest only.** Nothing downstream ran, so this says nothing about stream-processor, sink-writer
  or query-api limits, and the broker was never under consumer load. That is the full-pipeline
  half of REQ-RL-01/03/04 and is still open (T9-12, T9-13).
- Loadgen and ingest shared one machine's 12 cores, so the real ceiling is probably higher than
  45,000 events/s with the load generator elsewhere. The direction of that error is a reasonable
  guess, not a measurement.
- One run per configuration, 20 s per step, no repeats, so there is no variance estimate. The gap
  between "clean at 40,000" and "breach at 45,000" is one 5,000 step wide.
- The raw samples were kept in a scratch directory, not committed; the numbers above are the record.

---

## Phase 9 · T9-12 — end-to-end event accounting over whole runs · 2026-10-19 · 🚧 in progress

`scripts/accounting-check.py` was already committed but its output was never recorded, and
`RESUME-HERE.md` still listed accounting as "asserted in pieces, never over a whole run". It sends
events with known ids, times and sessions into one aggregate key (a unique region per run), re-sends
every 5th batch byte-for-byte as a retrying client would, advances stream time with a closing event,
then compares **every closed window** in Postgres with what was sent: event count, distinct
sessions, and error ratio. Run against the full stack (`make up-core`, `make run-all`): ingest ->
Kafka -> stream-processor -> Kafka -> sink-writer -> Postgres.

I used this script rather than writing `EndToEndAccountingIT`. A Testcontainers version would need
Kafka, Postgres, Redis and four services in one test and would duplicate what this already checks.

```
$ LOADGEN_CLIENT_SECRET=... python3 -I scripts/accounting-check.py
run 0a349477: 2000 events in 20 batches, region=acct-0a349477
sent 2000 unique events (+400 re-sent as retries, +0 connection retries); closing event at window 05:57:20
window                         sent   stored    viewers   err_rate  verdict
2026-10-19T05:56:50.000Z        300      300      97/97     0.1000       ok
2026-10-19T05:57:00.000Z        900      900    100/100     0.1000       ok
2026-10-19T05:57:10.000Z        800      800    100/100     0.1000       ok
closed windows checked: 3   events sent: 2000   events stored: 2000
RESULT: PASS

$ ... --events 30000 --interval 0.2
sent 30000 unique events (+6000 re-sent as retries, +0 connection retries)
closed windows checked: 10   events sent: 30000   events stored: 30000
RESULT: PASS    (10 of 10 windows ok, 0 FAIL)
```

Every unique event was stored exactly once, and the 400 and 6,000 re-sent duplicates were not
double-counted (ingest's idempotency key plus the processor's dedup).

**Can the check fail?** I made the script under-count one event in its own bookkeeping, then ran it:

```
window                         sent   stored    viewers   err_rate  verdict
2026-10-19T05:59:30.000Z        799      800    100/100     0.1000     FAIL
...
closed windows checked: 3   events sent: 1999   events stored: 2000
RESULT: FAIL (1 window(s))
```

Red on exactly the affected window, the others still `ok`. Restored; `git diff scripts/` is empty.

### Honest gaps

- **This closes the happy-path accounting gap only.** No service was killed, restarted or slowed
  during these runs. Accounting under a crash is T9-34.
- Closed windows only. The window after the last event is closed by one synthetic event; the
  script cannot account for events that are still in an open window, by design.
- Single region key, so one aggregate key and one partition per run: this does not exercise events
  spread across partitions or tenants.
- 30,000 events at about 500 events/s, one batch every 0.2 s. It is a correctness check, not a load
  level; the Phase 3 gap involved 135,100 events at 1,500 events/s.
- Late events (the loadgen's `--late-fraction`) are not covered; the script sends none.
- The mutation tests the verdict logic, not loss detection end to end: a real lost event would show
  up as `stored` below `sent`, which is the comparison that went red.

---

## Phase 9 · T9-34 — stream-processor killed mid-window · 2026-10-19 · ❌ FAILS (events lost)

REQ-RS-05 and ADR-0012 claim that after a processor crash nothing is lost and nothing is counted
twice. I ran the accounting script with `kill -9` on the processor JVM partway through the send
phase and restarted it a few batches later (the script's `--at BATCH:CMD` hook; `exactly_once_v2`,
state in `/tmp/streampulse/stream-processor`):

```
$ python3 -I scripts/accounting-check.py --events 4000 \
    --at "12:pkill -9 -f StreamProcessorApplication" \
    --at "20:cd <repo> && nohup ./gradlew :services:stream-processor:bootRun > build/logs/stream-processor-restart.log 2>&1 &"
window                         sent   stored    viewers   err_rate  verdict
2026-10-19T06:01:00.000Z        200      200      91/91     0.1000       ok
2026-10-19T06:01:10.000Z        900      900    100/100     0.1000       ok
2026-10-19T06:01:20.000Z        900      155     18/100     0.0774     FAIL
2026-10-19T06:01:30.000Z        900      163     18/100     0.1350     FAIL
2026-10-19T06:01:40.000Z       1000     1000    100/100     0.1000       ok
2026-10-19T06:01:50.000Z        100      100      61/61     0.1000       ok
closed windows checked: 6   events sent: 4000   events stored: 2518
RESULT: FAIL (2 window(s))
```

**Events were lost, silently.** Nothing was counted twice (no window stored more than was sent).
The windows spanning the outage hold only a fraction of their events, and the loss is clustered,
not random: 155 events picked at random from 100 sessions would touch about 79 of them (100 x
(1 - 0.99^155)), but only 18 sessions appear. Whole groups of events went missing together.

### Where the events went (measured, not inferred)

- The sink did not overwrite a good value with a bad one: `telemetry.agg.v1` holds exactly 12
  records for the run's region, one per window per metric.
- The processor dropped them. `KStreamWindowAggregate` logged one
  `WARN Skipping record for expired window` per lost event: **1,482 warnings for 1,482 missing
  events** (4,000 - 2,518), and the task `1_3` counter `kafka_stream_task_dropped_records_total`
  reads 1,482.
- One of the warnings, in full:
  `partition=[3] offset=[1637] timestamp=[1792389680520] window=[1792389680000,1792389690000) expiration=[1792389705957] streamTime=[1792389710957]`.
  The record belongs to the 06:01:20 window, but the aggregation task's stream time had already
  moved to 06:01:50.957, past that window's close (window end + 5 s grace).

### Is my test causing it? No (second run)

The first run's closing event raced the recovery, so I could not tell the tick from the backlog. I
re-ran with a longer send phase so the tick arrived long after processing resumed:

```
$ ... --events 8000 --at "12:pkill -9 ..." --at "16:<restart>"
closed windows checked: 10   events sent: 8000   events stored: 5446
RESULT: FAIL (4 window(s))   (windows 06:04:50, 06:05:00, 06:05:10, 06:05:20)
expired-window warnings: 2,554    missing events: 2,554
```

The stream time at those drops (1792389915445 = 06:05:15.4 and later) is **before** the tick was
sent (06:06:20): the backlog itself advanced it.

### Likely mechanism (consistent with the evidence, not proven)

Raw events are keyed by session, so one aggregate's events are spread over all 12 raw partitions.
After a restart the processor works off 12 backlogged partitions, and what it writes to the single
repartition partition for that aggregate is not in event-time order: a record from a later part of
the outage can be processed before earlier records from another partition. Stream time then jumps
past windows that still have unprocessed on-time events, and those are dropped as expired. The
5 s grace period is sized for out-of-order arrival under steady state, not for catching up a
backlog after an outage of 40 to 50 s. Not tested: a variant with a shorter outage, a graceful
`SIGTERM` restart, or a larger grace.

Timing, for context: the restarted JVM reported `Started in 1.2 seconds`, but partitions were not
assigned for roughly another 30 s (the dead member's session had to expire; I did not check the
configured session timeout). Processing resumed about 40 to 50 s after the kill in both runs.

### What this means

- **REQ-RS-05 does not hold for outages longer than the grace period.** Exactly-once held at the
  Kafka level (no duplicates, nothing lost in a topic); the aggregate is nonetheless wrong.
- **It is invisible to the service's own metrics.** `streampulse_processor_*` has counters for
  duplicates and rejected events but none for late drops; only the Kafka Streams built-in counter
  and a WARN log show it. Nothing alerts on it.
- ADR-0012 states the quiet-stream limit (a window waits for stream time to advance). It does not
  describe this one: on-time events lost after a restart.

### Honest gaps

- One crash type only (`kill -9` of the single processor instance, single broker). Not tried:
  graceful shutdown, a second instance taking over, a crash with a short outage.
- The accounting script only sees closed windows for one aggregate key. Other keys in the same
  topic were not checked, and I did not examine whether the 18/100 session pattern is exactly the
  partition split I describe.
- No fix attempted. Candidates and their costs are for you and an ADR: a larger grace (freshness
  floor rises by the same amount), emitting revised windows (breaks the one-final-answer contract),
  or draining the backlog before stream time can move. Choosing one changes a documented guarantee.

---

## Phase 9 · T9-13 — latency and freshness at 1,000 and 2,000 events/s · 2026-10-19 · 🚧 in progress

**Setup (one machine, same as T9-11):** Apple M2 Pro, 12 cores, 32 GiB, macOS 27.0.1, OpenJDK 21.
Full pipeline this time: ingest-gateway, stream-processor, sink-writer and query-api on the host
via `make run-all`; Kafka (single broker), Redis, Postgres, Keycloak in Docker; loadgen and a
reader on the same machine. Default config, so the per-tenant quota (2,000 events/s) is not
lifted; both rates stay under it. Batch 100, 60 s per run, after a 20 s warm-up at 500 events/s.
Reads: 4 threads, 50 ms pause between requests, `GET /v1/titles/title-N/overview?tenantId=streamflix`
over 20 titles, about 52 requests/s in total, during the whole write run.

| Run | Ingest p50 / p95 / p99 | Overview read p50 / p95 / p99 | Freshness per closed window |
|---|---|---|---|
| 1,000 events/s | 52.9 / 59.4 / 76.5 ms | 24.7 / 27.3 / 29.6 ms | 5.3 to 5.4 s (5 windows) |
| 2,000 events/s, run a | 45.5 / 54.6 / 70.9 ms | 23.8 / 26.7 / 28.7 ms | 5.2 to 5.4 s (5 windows) |
| 2,000 events/s, run b | 45.2 / 52.4 / 59.0 ms | 23.9 / 26.7 / 27.4 ms | 5.2 to 5.4 s (6 windows) |
| (idle reads only, 15 s) | n/a | 27.1 / 32.0 / 37.2 ms | n/a |

All 3,105 to 3,145 reads per run returned 200. Phase 3's single run, at 1,500 events/s, gave ingest
p99 68.7 ms and freshness near 5.3 s, so these agree with it.

**Freshness** is `computed_at - window_end` per row in `metric_window`, grouped by window, over the
windows this run produced. Each window has 200 rows and every row of a window landed within 0.1 s
of the others (min, p50 and max differ by at most 0.1 s), so p50, p95 and p99 are all the same
figure to within 0.1 s: **about 5.3 s, the 5 s grace period plus roughly 0.3 s of pipeline**. True
event-to-visible time adds up to a window (10 s) on top.

### Found only by running it

1. **The loadgen's own freshness p99 is wrong whenever a run follows another one.** It reported
   39.33 s, 112.93 s and 39.63 s for these runs, against 5.3 s from Postgres. A run's last window
   stays open until later events arrive (the quiet-stream limit in ADR-0012), so the next run's
   first events close it, and it is recorded then, long after its end, inside the next run's
   histogram. The per-window table showed it directly for the 1,000 events/s run: the warm-up's two
   leftover windows at 34 s and 24 s late, every window of the run itself at 5.3 s. The 112.93 s
   figure for the next run is consistent with a leftover closed about 100 s late, but I did not
   print that run's leftover window, so that part is inferred. `RESUME-HERE.md` warns about this; a warm-up does not
   fix it, because the warm-up leaves its own last window open. **Do not quote the loadgen's
   freshness figure for back-to-back runs.** I have not changed the tool.
2. My first Postgres cross-check had the same flaw (it counted windows by commit time, not by when
   they started), which is why the final query keys on `window_start`.

### Honest gaps

- Read load is light and unsaturated: 52 requests/s from 4 threads, with a fresh connection per
  request from a Python client. This is read latency alongside the write load, not a read capacity
  test. Reads under load were not slower than idle (p99 27 to 30 ms against 37 ms idle); I would not
  read that as "faster", since it is within the noise of a 15 s idle sample.
- I did not check which store served the reads (Redis hot path or Postgres), so this does not say
  which one would limit reads at higher rates.
- Only two rates, below the quota, one or two runs each. The 2,000 events/s pair differs by 12 ms
  at ingest p99 (70.9 against 59.0 ms), so that is the order of the run-to-run noise.
- Both rates are far below the 40,000 events/s ingest handled alone in T9-11; the downstream
  stages' own limits are not found. Higher rates need the quota lifted, and I did not run them.
- Loadgen, the reader and every service shared one machine.
