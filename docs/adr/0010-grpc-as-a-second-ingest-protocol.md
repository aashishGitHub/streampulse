# ADR-0010 — gRPC as a second ingest protocol, over the same use case

**Status:** accepted · **Date:** 2026-09-29 · **Phase:** 2 · **Requirements:** REQ-GR-01…07, REQ-IG-08

## Context

A (fictional) smart-TV OEM holds one connection open per device and emits a heartbeat every
ten seconds. Over the REST endpoint each batch costs a TLS handshake or a pooled connection,
a set of HTTP headers, and a JSON body — for a payload that is a few dozen bytes of
telemetry. On a TV's CPU budget the framing dominates the data.

They asked for a binary streaming protocol. The interesting question is not "can we add
gRPC" but **what a second front door does to a system whose correctness lives upstream of
the broker**. Ingest decides idempotency, tenancy, quota and backpressure. A second protocol
that reimplements any of those is not a feature; it is a second, subtly different product.

## Decision

Add gRPC as a **second inbound adapter over the same `IngestUseCase` port**. No business
logic in the adapter, no second copy of any rule.

```
POST /v1/events:batch  ──▶ IngestController  ──┐
                                               ├──▶ IngestUseCase ──▶ EventPublisher ──▶ stream
TelemetryIngest/SendBatch ──▶ TelemetryIngest  ─┤     quota · dedup · backpressure
TelemetryIngest/StreamEvents ──▶ GrpcService  ──┘     tenant · partition key
```

Three supporting decisions, each with its own reasoning below:

1. **`protoc` is driven by a custom Gradle task**, not by the `com.google.protobuf` plugin.
2. **The server is plain `io.grpc` behind a `SmartLifecycle`**, not a community Boot starter.
3. **Authentication is a `ServerInterceptor` reusing Spring Security's `JwtDecoder`**, and
   the tenant comes from the token — never from the message.

## REST vs gRPC, honestly

Neither replaces the other here; they answer different questions. Both are kept, and
`SendBatch` exists precisely so the comparison is like-for-like.

| | REST + JSON | gRPC |
|---|---|---|
| Framing cost per event | headers + JSON keys on every batch | one HTTP/2 connection, binary, header compression (HPACK) |
| Connection model | request/response, pooled | one long-lived connection, multiplexed streams |
| Server → client signalling | none; the client polls or infers | first-class: periodic ACKs carry the last durable sequence |
| Schema | OpenAPI, enforced by convention and a test | `.proto`, enforced by the compiler on both sides |
| Backpressure | a 503 the client must interpret | flow control: the server withholds credit and the client stalls |
| Debuggability | `curl`, any browser, any proxy log | needs `grpcurl` and a descriptor; binary frames in a capture |
| Browser support | native | needs grpc-web and a proxy |
| Load balancing in K8s | L4 is fine | **L7 required** — see below |
| Failure vocabulary | HTTP status + `Retry-After` | `Status` code + `google.rpc.RetryInfo` trailer |

The honest summary: gRPC wins decisively for a chatty, long-lived, machine-to-machine client
with a schema, and loses for anything a human debugs at 3am or a browser calls directly.
"Use gRPC internally, REST at the edge" is the usual conclusion, and this service is a case
where the edge has both kinds of client.

**The efficiency claims above are not measured.** REQ-GR-06 asks for a `ghz` benchmark of CPU,
bytes and p99 against the REST batch path. It has not been run, so no number in this ADR is
mine. Phase 9.

## Deadlines, and why they are not timeouts

The distinction is the single most useful thing gRPC teaches about distributed calls.

```
TIMEOUT    a duration each hop invents for itself. Three hops at 5s each means the caller
           may wait 15s — and hop 3 cheerfully starts work for a caller who left 10s ago.

DEADLINE   an absolute instant, set once by the caller and propagated on the wire as the
           grpc-timeout header. Every hop inherits the SAME point in time, so a hop with no
           budget left can decline instead of doing work nobody will read.
```

gRPC carries it in `Context`, which is attached to the thread for every callback of the call.
That is what makes `Context.current().isCancelled()` a truthful question inside the use case,
and it is what `GrpcDeadlineCancellationTest` asserts: the client's expired deadline reaches
the server-side work, not just the client's future.

**What it does not do, stated plainly:** cancellation here is *observable*, not pre-emptive.
Nothing interrupts a publish already in flight. What the deadline bounds is how much **new**
work the server starts. Cutting an in-flight publish short is the time limiter's job
(ADR-0009), and keeping the two separate is deliberate — one is the client's budget, the
other is ours.

## Status mapping

| Domain failure | gRPC status | HTTP equivalent | Client should |
|---|---|---|---|
| `IllegalArgumentException` (bad UUID, bad tenant) | `INVALID_ARGUMENT` | 400 | fix the input |
| `QuotaExceededException` | `RESOURCE_EXHAUSTED` + `RetryInfo` | 429 | slow down; another instance will not help |
| `BackpressureException` | `UNAVAILABLE` + `RetryInfo` | 503 | retry, possibly elsewhere |
| `PublishUnavailableException` | `UNAVAILABLE` + `RetryInfo` | 503 | keep the data, retry after the delay |
| bad/absent/expired token | `UNAUTHENTICATED` | 401 | get a new token |
| anything else | `INTERNAL` (no detail) | 500 | do not retry; someone is paged |

Two of these are load-bearing.

**`UNAVAILABLE` rather than `INTERNAL` for a publish failure.** gRPC clients, generated
service configs and service meshes all treat `UNAVAILABLE` as retryable and `INTERNAL` as
fatal. Mapping a temporarily unreachable broker to `INTERNAL` tells an entire fleet to give up
on data that would have succeeded a second later.

**`RESOURCE_EXHAUSTED` vs `UNAVAILABLE`** is the same distinction 429 and 503 make on the REST
side, and for the same reason: one says *your* quota is spent (global, so retrying elsewhere
is pointless), the other says *we* are saturated (another instance may cope).

`google.rpc.RetryInfo` is the standard error detail, obtained through
`ProtoUtils.keyForProto(RetryInfo.getDefaultInstance())` so the trailer name and its `-bin`
suffix are derived rather than hand-written. A Go or Kotlin client reads it with no
StreamPulse-specific code — it is the gRPC analogue of `Retry-After`. The value is the domain
exception's own `retryAfter()`, which for a publish failure is the breaker's remaining open
window, so the two cannot drift.

### One place the two protocols deliberately differ

A **valid, signed, unexpired token with no `tenant` claim**:

* REST → 500, because it reaches `IngestExceptionHandler`'s generic `IllegalStateException`
  branch.
* gRPC → `UNAUTHENTICATED`, because the interceptor treats an unattributable caller as
  unauthenticated.

gRPC is right and REST is arguably wrong: a token we cannot attribute to a tenant is an
authentication problem, not a server fault. It is recorded here rather than fixed, because
changing a REST status code is a contract change and belongs in its own commit with the
OpenAPI document. **Open item.**

## Backpressure parity, expressed in each protocol's own vocabulary

REQ-GR-04 asks for manual inbound flow control tied to the same in-flight semaphore as REST.

```
request(N) ─▶ N events arrive ─▶ ingest() ─▶ broker ack ─▶ Ack ─▶ request(N)
                                     │
                                     └─ no permit available? the use case throws, and no
                                        credit is ever granted — the client's HTTP/2 window
                                        stalls, which is the same bound REST states as a 503
```

The default (auto-request) would have the server ask for one more message every time a
handler returns, so a fast client streams as fast as the network allows and the server buffers
what it cannot process. That is exactly the unbounded-queue failure the REST path already
refuses: buffering does not prevent overload, it relocates it to the heap.

`disableAutoRequest()` moves the credit decision to us, and credit is granted only *after* the
previous window is durable. One semaphore, two protocols.

The ACK interval and the flow-control window are **the same number**
(`stream-flush-size`, default 50) on purpose — two dials that must agree are better modelled
as one. Smaller frees the client's buffer sooner at the cost of more round trips; larger
batches better and makes a client crash cost a bigger resend. It is explainable, not measured.

## Why protobuf codegen is a custom Gradle task

Phase 1 hit this exact problem with the Avro plugin and solved it with `AvroCodegenTask`. The
same reasoning applies: the `com.google.protobuf` plugin's Gradle 9 support is unverified, and
a learning repo should not be blocked on a third party's release cadence.

What the plugin mostly does is *find a protoc binary*, and that turns out to be a solved
problem — Google publish `protoc` to Maven Central as a platform-classified executable, and
gRPC publish their codegen plugin the same way. An ordinary Gradle configuration resolves
both; `ProtoCodegenTask` runs them.

One thing had to be discovered rather than assumed: **the standalone protoc binary does not
embed the well-known types.**

```
$ protoc -I src --java_out=out src/telemetry.proto
google/protobuf/timestamp.proto: File not found.
```

Those `.proto` files ship *inside the `protobuf-java` jar*. Unpacking them onto the include
path is what the official plugin does under the hood, and taking them from the same artifact
we compile against is what stops the definitions and the runtime drifting apart.

The protobuf version is pinned to **3.25.9** because that is what `grpc-protobuf-1.84.0`
depends on — read out of its POM, not remembered. protoc emits code against a runtime of the
same line, and 3.x codegen on a 4.x runtime fails at class-load time, long after the build
went green.

## Why a hand-managed server and not a Spring Boot gRPC starter

The community starters do this competently. Two things decided against one here:

1. **Shutdown ordering.** BS-01 exists to prove an ordered drain, and that claim only holds if
   the gRPC server is a *participant* in the phase ladder rather than something that stops
   whenever its own auto-configuration happens to stop.

   ```
   phase 2147482623  in-flight HTTP requests finish   (WebServerGracefulShutdownLifecycle
                                                       .SMART_LIFECYCLE_PHASE — read from the jar)
   phase 2147481599  the servlet connector stops      (WebServerStartStopLifecycle)
   phase 200         GrpcServerLifecycle: stop accepting, drain, then cancel
   phase 100         IngestDrainLifecycle: readiness → REFUSING_TRAFFIC, wait for in-flight
   ```

   200 before 100 is the part that matters: no new gRPC call may arrive while the drain counts
   in-flight batches down to zero. Reverse them and the drain watches a number that keeps going
   back up, times out, and logs a data-loss warning caused entirely by its own position.

2. **It is about fifty lines.** Taking a dependency whose maintenance status has to be
   researched, in exchange for fifty lines that teach what `SmartLifecycle` is for, is a bad
   trade in a repo whose purpose is understanding the mechanism.

A detail worth keeping: **no `io.grpc.Server` bean is exposed.** Spring infers a destroy
method for beans with `close()` or `shutdown()`, and `Server` has `shutdown()` — so a `Server`
bean would be shut down by the container at context-close time, racing the lifecycle. Keeping
the server *inside* the lifecycle leaves exactly one thing in charge of stopping it.

## REQ-GR-07 — gRPC needs L7 load balancing in Kubernetes

This is the operational trap, and it is not obvious from the code.

```
   L4 (kube-proxy / iptables / a classic LB): balances CONNECTIONS
   L7 (Envoy, Linkerd, Istio, an ingress that speaks HTTP/2): balances REQUESTS

   REST   client ──connect──▶ pod A   (short-lived; the next request re-balances)
          client ──connect──▶ pod B
          → spreads by itself, because connections keep ending

   gRPC   client ──connect──▶ pod A   ══════ one connection, thousands of RPCs ══════▶ pod A
          → every RPC lands on pod A for the life of the connection.
            Scale to 10 pods and 9 of them get nothing until clients reconnect.
            An HPA watching CPU sees one hot pod and nine idle ones, and does the wrong thing.
```

The failure is the shape of the load, not an error — nothing is down, nothing logs, the
dashboard shows an inexplicably uneven fleet. It is worse right after a scale-up, which is
exactly when it is least expected.

Three fixes, in the order they are usually reached for:

| Option | How | Cost |
|---|---|---|
| **Service mesh / L7 proxy** | Envoy, Linkerd or Istio terminates HTTP/2 and balances per RPC | a sidecar per pod, a control plane, and a new thing to operate |
| **Client-side load balancing** | headless `Service`, `dns:///` resolver, `round_robin` policy in the channel | the client must be a gRPC client you control; DNS refresh becomes your problem |
| **Bound the connection age** | `ServerBuilder.maxConnectionAge(...)` + `maxConnectionAgeGrace(...)`: the server politely asks clients to reconnect periodically, so they re-resolve and redistribute | reconnection churn; a mitigation, not a fix — load is uneven *between* reconnections |

**None of these is configured yet.** The methods named above were verified to exist on
`io.grpc.ServerBuilder` in 1.84.0, but Phase 11 owns the Kubernetes manifests and this is
where the decision belongs. Recorded now because it is the question an interviewer asks and
the one a team discovers in production.

TLS changes the picture again: an L7 proxy has to terminate TLS to see the individual streams,
which turns "add a load balancer" into a certificate and trust conversation.

## Consequences

**Good**

- A second protocol, and not a single business rule was duplicated —
  `ProtocolParityTest` asserts that rather than claiming it.
- The tenant rule is now one implementation with two callers (`TenantResolver` moved from
  `adapter.in.rest` to `adapter.in`), so it cannot be right on one protocol and wrong on the other.
- Deduplication spans protocols: an id sent over REST is a duplicate over gRPC, proven.
- Backpressure is expressed in each protocol's own vocabulary from one semaphore.
- Deadline propagation is verified to reach the use case, not merely configured.
- `ArchUnit` rules now police this module directly — the rules in `libs:platform` could never
  see it, because ArchUnit reads bytecode from the classpath.

**Costs / open items**

- A second wire contract to version. Protobuf field numbers are forever.
- gRPC is harder to debug: no `curl`, no readable proxy log, `grpcurl` plus a descriptor.
- **REQ-GR-06 not done:** no `ghz` benchmark, so every efficiency claim here is a
  well-founded expectation and not a measurement.
- **REQ-GR-01 partly done:** `buf lint` and `buf breaking` are not wired into CI. Today the
  only gate is that protoc parses the file — nothing stops a field being renumbered.
- **REQ-GR-05 partly done:** JWT authentication is in place; the W3C trace-context interceptor
  is not. Tracing arrives in Phase 8 with Micrometer Tracing, and doing it now would mean
  inventing a propagation story the rest of the system does not yet have.
- **REQ-GR-07 documented, not configured.** No manifests, no mesh, no `maxConnectionAge`.
- The REST/gRPC divergence on a missing tenant claim (500 vs `UNAUTHENTICATED`) is unresolved.
- `stream-flush-size` is a guess, like every other number in Phase 2.

## Alternatives rejected

**Replace REST with gRPC.** Tempting, and wrong. The dashboard, the control plane and every
browser client speak HTTP/JSON; grpc-web plus a proxy is a worse version of what already
works. The OEM's constraint is not everybody's constraint.

**A second service for gRPC.** It would have needed its own copy of quota, deduplication and
tenancy, or a network hop to reach them. The first option is the divergence this ADR exists to
prevent; the second adds a failure mode to the hottest path in the system for no benefit.

**A community Spring Boot gRPC starter.** Covered above: the ordered drain wants a
participant, and fifty lines beats a dependency whose maintenance status has to be researched.
Not a criticism of those projects — for a team shipping ten gRPC services it is probably the
right call.

**The `com.google.protobuf` Gradle plugin.** Its Gradle 9 support is unverified, and Phase 1
already paid for that lesson once with Avro. The custom task is about eighty lines, is
incremental and cacheable, and fails with a real error message.

**Sharing DTO mapping between the adapters.** A common `toDomain` helper over "some event-ish
thing" sounds like DRY and is not: it becomes a lowest common denominator that neither wire
format fits, and it couples the JSON contract to the protobuf one. The duplication that
matters — the *rules* — is already shared; the mapping is each protocol's own business, and
`ProtocolParityTest` is what proves the two mappings agree.
