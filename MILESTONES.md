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
