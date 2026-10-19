# Phase 11 — Containers and Kubernetes

**Status: partly built. Only the container image recipe exists.** There is a layered, non-root
Dockerfile. There are **no Kubernetes manifests, no Helm chart, no probes wiring, no autoscaling**, and no
recorded `docker build`. `specs/11-containers-k8s/` and the Helm directories are empty, and the Dockerfile
cites an ADR-0023 that does not exist.

**How to use this phase in an interview:** talk about the image decisions as things you built, and talk
about Kubernetes as *design you have thought through but not built*, saying so first. Do not imply you
deployed it.

## Code map

| Thing | Where |
|---|---|
| The image recipe | `deploy/docker/Dockerfile` |
| What may enter the build context | `deploy/docker/Dockerfile.dockerignore` |
| Liveness and readiness groups the services already expose | `services/ingest-gateway/src/main/resources/application.yml` |
| Shutdown settings the pod will depend on | `services/ingest-gateway/src/main/java/com/streampulse/ingest/lifecycle/IngestDrainLifecycle.java` |
| Sibling study notes on Kubernetes for stream apps | `../Java/interview-qa/13-kubernetes-for-stream-apps.md` (outside this repo) |

---

## Beginner

### B1. Why run the container as a numeric non-root user?

**A strong answer contains**
- Least privilege: if the process is compromised it cannot write the application files or act as root.
- The kubelet can only verify `runAsNonRoot` against a **numeric** user id, not a name. The image uses
  10001.

**Open** `deploy/docker/Dockerfile`

### B2. What is a layered jar and why extract it?

**A strong answer contains**
- Spring Boot can split a fat jar into layers (dependencies, loader, snapshot dependencies, application). A
  code change then rebuilds only the small top layer, so builds and pulls are faster.
- The recipe uses the jar's own tools mode to extract layers in that order.

**Open** `deploy/docker/Dockerfile`

### B3. Why a multi-stage build?

**A strong answer contains**
- Build and extraction tooling stay out of the final image; the runtime stage holds only a JRE and the layers.
- The jar itself is built outside the image, and the ignore file allows only the service jars in.

**Open** `deploy/docker/Dockerfile.dockerignore`

### B4. What is a liveness probe and what is a readiness probe?

**A strong answer contains**
- Liveness: should the process be restarted? Readiness: should it receive traffic right now?
- In these services, liveness contains **only** the process's own state, because putting a dependency in it
  turns a database blip into a restart storm (a test reproduced exactly that by mutation).

**Open** `services/ingest-gateway/src/main/resources/application.yml`

---

## Mid

### M1. How do you size JVM memory in a container?

**A strong answer contains**
- A percentage of the container limit (`MaxRAMPercentage=75`) rather than a fixed heap, leaving room for
  metaspace, thread stacks, direct buffers and native stores such as RocksDB. Services that use RocksDB
  should lower it.
- `ExitOnOutOfMemoryError` makes the process exit so the orchestrator restarts it instead of limping.
- The options are set through an environment variable so a deployment can override them.

**Open** `deploy/docker/Dockerfile`

### M2. Why are image tags pinned?

**A strong answer contains**
- A floating `latest` makes a build non-reproducible and breaks CI for unrelated reasons. The base image is
  pinned to an exact version. (Some test containers use floating minor tags, which is weaker; say so.)

**Open** `CLAUDE.md`

### M3. What has to be true for a rolling deploy to lose nothing?

**A strong answer contains**
- Readiness flips first so the load balancer stops sending, in-flight work drains within a bounded time, and
  the pod's grace period exceeds the sum of the shutdown waits.
- This repo's drain exists, but **no test sends a real SIGTERM** and the sum of the configured waits (25 + 10
  + 20 s) exceeds the planned 45 s grace period. Quote that as arithmetic from config, not a measurement.

**Open** `services/ingest-gateway/src/main/java/com/streampulse/ingest/lifecycle/IngestDrainLifecycle.java`

---

## Senior

### S1. How would you autoscale the stream processor and the sink?

**A strong answer contains**
- Not on CPU: consumers are bound by partition count and lag. Scale on consumer lag (for example with KEDA)
  up to the number of partitions; beyond that extra replicas sit idle.
- The processor is stateful (RocksDB state, changelog restore), so scale-in and restarts have a restore cost,
  and a restart after a long outage hits the dropped-events defect from the crash test.
- **Not built here.** Answer from design and say so.

**Open** `docs/interview/phases/phase-03-stream-processing.md`

### S2. gRPC behind Kubernetes: what is the load-balancing trap?

**A strong answer contains**
- gRPC uses long-lived HTTP/2 connections, so a layer-4 service pins a client to one pod. You need layer-7
  balancing, client-side round-robin, or a maximum connection age.
- ADR-0010 lists the options; **none is configured**.

**Open** `docs/adr/0010-grpc-as-a-second-ingest-protocol.md`

### S3. What would a pod disruption budget and a preStop hook buy you?

**A strong answer contains**
- A disruption budget bounds how many replicas a voluntary eviction may remove at once; a short pre-stop sleep
  gives endpoints time to propagate before SIGTERM so the load balancer stops sending first.
- Neither exists here. This is design, not evidence.

**Open** `../Java/interview-qa/13-kubernetes-for-stream-apps.md`

### S4. What would you build first to make Phase 11 real?

**A strong answer contains**
- Build the image and record the output; add the missing ADR; add a chart with probes, resources and a
  grace period; run it on a local cluster; run a rolling restart under load and check zero loss with the
  accounting script.
- Add the image build and Terraform checks to CI.

**Open** `specs/11-containers-k8s/`

---

## Say this honestly

- Only a Dockerfile exists; it has never been built in a recorded run and is not in CI or compose.
- ADR-0023 is cited but missing. The Helm and spec directories are empty.
- Everything about Kubernetes in this pack is design thinking, not experience from this repo.

## Stories and numbers

None yet; nothing here was measured.
