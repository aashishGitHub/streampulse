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

**Answer:** Running as a non-root user is least privilege: if the process is compromised, it cannot act as root inside the container, and it cannot write the application files. In the Dockerfile I create a system group and user with id 10001 and switch to it with `USER 10001:10001`. The id is numeric on purpose, because the kubelet can only verify a `runAsNonRoot` setting against a numeric user id, not against a name. The application files are copied in as root and left read-only to that user, so the code a process runs is not writable by that process. I have not built this image in a recorded run, and there are no Kubernetes manifests that set `runAsNonRoot`, so the kubelet check is the reasoning behind the choice, not something I observed.

**Example:** `deploy/docker/Dockerfile:16-32`
```dockerfile
RUN groupadd --system --gid 10001 app \
 && useradd --system --uid 10001 --gid 10001 --no-create-home --shell /usr/sbin/nologin app
WORKDIR /app
...
USER 10001:10001
```
Point at the numeric `10001` in both the `useradd` and the `USER` line, and at the `nologin` shell.

**Code:** `deploy/docker/Dockerfile:16-18` (the user), `deploy/docker/Dockerfile:20-24` (root-owned application files), `deploy/docker/Dockerfile:32` (the `USER` line)

### B2. What is a layered jar and why extract it?

**Answer:** Spring Boot can split its fat jar into layers by how often they change: dependencies, the loader, snapshot dependencies and the application code. If the layers are copied into the image in that order, a code-only change rebuilds only the small top layer, so builds and image pulls are faster. The Dockerfile uses the jar's own tools mode, `-Djarmode=tools ... extract --layers`, in a first stage, and the final stage copies the four directories in order from least to most frequently changing. The Dockerfile comment estimates the dependency layer at roughly 40 to 120 MB; that is an estimate in a comment, and I have not measured it because I have no recorded build. I also did not check how layering is configured on the Gradle side.

**Example:** `deploy/docker/Dockerfile:13-24`
```dockerfile
RUN java -Djarmode=tools -jar app.jar extract --layers --destination extracted --application-filename app.jar
...
COPY --from=extract /build/extracted/dependencies/ ./
COPY --from=extract /build/extracted/spring-boot-loader/ ./
COPY --from=extract /build/extracted/snapshot-dependencies/ ./
COPY --from=extract /build/extracted/application/ ./
```
The `application/` copy is last, so it is the only layer that changes on an ordinary code edit.

**Code:** `deploy/docker/Dockerfile:11-13` (the extract step and its comment), `deploy/docker/Dockerfile:21-24` (the layer order in the runtime stage)

### B3. Why a multi-stage build?

**Answer:** A multi-stage build keeps the extraction step out of the final image, so the runtime stage holds only a JRE and the extracted layers. The first stage copies the jar in and runs the layer extraction, and the second stage starts again from the same pinned JRE image and copies only the result. One honest detail is that the jar itself is built outside the image with Gradle, not inside it, so the first stage is not a compile stage and both stages use a JRE image, not a JDK. Because the build context is the repository root, the ignore file is an allowlist: it excludes everything, then re-admits only the service jars and excludes the `-plain.jar` files. That keeps the context small and stops source and secrets from entering it.

**Example:** `deploy/docker/Dockerfile:5-15`
```dockerfile
ARG JRE_IMAGE=eclipse-temurin:21.0.10_7-jre-noble
FROM ${JRE_IMAGE} AS extract
ARG MODULE
WORKDIR /build
COPY services/${MODULE}/build/libs/${MODULE}.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --destination extracted --application-filename app.jar
FROM ${JRE_IMAGE}
```
The second `FROM` starts a fresh image, so nothing from the `extract` stage survives except what is explicitly copied.

**Code:** `deploy/docker/Dockerfile:5-15` (the two stages), `deploy/docker/Dockerfile.dockerignore:1-3` (the allowlist)

### B4. What is a liveness probe and what is a readiness probe?

**Answer:** A liveness probe asks whether the process is broken beyond recovery and should be restarted, and a readiness probe asks whether it should receive traffic right now. In the ingest gateway the two groups are written out in the configuration, even though Spring Boot would derive them, because their membership is the decision. The liveness group contains only `livenessState`, never a dependency, because a failed liveness probe causes a restart and putting Redis in it would restart every pod during a Redis blip. The ingest path fails open on Redis anyway. The readiness group contains only `readinessState`, which the drain lifecycle flips to out of service during shutdown. No Kubernetes manifest here wires these probes to a pod, so this is the application side only.

**Example:** `services/ingest-gateway/src/main/resources/application.yml:124-145`
```yaml
        liveness:
          include: livenessState
        readiness:
          include: readinessState
```
The comments in the file, between these lines, explain why each group has exactly one member.

**Code:** `services/ingest-gateway/src/main/resources/application.yml:117-145` (both groups and their comments), `services/ingest-gateway/src/test/java/com/streampulse/ingest/adapter/HealthProbesIT.java:98-115` (the test that pins the liveness group)

### B5. How does one Dockerfile serve every service?

**Answer:** The recipe takes a build argument called `MODULE` and copies `services/<module>/build/libs/<module>.jar`, so the same file builds any of the Spring Boot services by passing a different module name. The header comment gives the command shape, `docker build --build-arg MODULE=<service>`, and says the jar is built first with Gradle's `bootJar` task for that module. The build context is the repository root, which is why the ignore file matters. The benefit is one recipe to review and one set of security decisions, such as the numeric user and the memory flags, applied to every service. The cost is that every service shares the same memory setting, and the comment itself says a service with native stores, such as RocksDB, should lower it. I have not built any of these images.

**Example:** `deploy/docker/Dockerfile:8-10`
```dockerfile
ARG MODULE
WORKDIR /build
COPY services/${MODULE}/build/libs/${MODULE}.jar app.jar
```
Point at `${MODULE}` appearing twice in the path, which assumes each `bootJar` produces `<module>.jar`.

**Code:** `deploy/docker/Dockerfile:8-10` (the module argument), `deploy/docker/Dockerfile:1-3` (the usage comment)

---

## Mid

### M1. How do you size JVM memory in a container?

**Answer:** I set the heap as a percentage of the container's memory limit with `-XX:MaxRAMPercentage=75.0`, rather than a fixed `-Xmx` that could exceed the limit when someone changes it. The remaining 25 percent is for metaspace, thread stacks, direct buffers and native memory, and a service with a native store such as RocksDB should lower the percentage, because that memory is outside the heap. The Dockerfile also sets `-XX:+ExitOnOutOfMemoryError`, so the process exits on an out-of-memory error and the orchestrator restarts it, instead of limping on in an undefined state. The 75 is a starting choice written in a comment, not a tuned figure, and I have not run these images under a memory limit. Kubernetes resource limits are also not set anywhere, since there is no chart.

**Example:** `deploy/docker/Dockerfile:26-30`
```dockerfile
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"
```
The comment above this line in the file gives the reasons; the line itself is the whole memory policy.

**Code:** `deploy/docker/Dockerfile:26-30` (the flags and their rationale), `services/stream-processor/src/main/resources/application.yml:16` (the processor's local state directory, the service most likely to need a lower percentage)

**If they push:** "Why not just set `-Xmx`?" A fixed `-Xmx` silently stops tracking the container limit when the limit changes, so it can exceed it and get the process killed by the kernel; a percentage follows the limit.

### M2. Why are image tags pinned?

**Answer:** A floating tag such as `latest` makes a build non-reproducible, and it can break CI for reasons unrelated to the change being built. The project rule in `CLAUDE.md` is to pin Docker image tags and never use `latest`, and the Dockerfile follows it with an exact JRE version, `eclipse-temurin:21.0.10_7-jre-noble`. The compose file has a concrete example in its comment: moto's `latest` was a development build, so it is pinned to 5.2.2. I should also say that the pinning is uneven. Some compose images, such as Postgres and Redis, use a major or minor tag like `postgres:16-alpine` and `redis:7-alpine`, which still float within that line. And since CI never builds the image, the Dockerfile's pin is not exercised by any automated check.

**Example:** `deploy/docker/Dockerfile:5-5`
```dockerfile
ARG JRE_IMAGE=eclipse-temurin:21.0.10_7-jre-noble
```
An exact version and build number, with the base image passed as an argument so it can be changed in one place.

**Code:** `CLAUDE.md:41` (the pinning rule), `docker/compose.yml:33` (moto pinned, with the reason), `docker/compose.yml:122` and `docker/compose.yml:149` (the weaker Postgres and Redis tags)

### M3. What has to be true for a rolling deploy to lose nothing?

**Answer:** Readiness has to flip first so the load balancer stops sending new work, in-flight work has to drain within a bounded time, and the pod's grace period has to be longer than the sum of the shutdown waits. In the ingest gateway, `IngestDrainLifecycle` publishes `REFUSING_TRAFFIC` and then waits until every in-flight permit is back, up to a drain timeout. The configuration has a 25 second per-phase shutdown limit, a 20 second drain timeout and a 10 second gRPC shutdown timeout, and the comments in the file say the Kubernetes grace period must exceed their total and refer to 45 seconds. Worked from the configured limits, 25 plus 10 plus 20 is 55, which is more than 45. That is arithmetic from configuration and a worst case, not a measurement. No test sends a real SIGTERM, and there is no pod spec to set the grace period.

**Example:** `services/ingest-gateway/src/main/java/com/streampulse/ingest/lifecycle/IngestDrainLifecycle.java:98-112`
```java
    public void stop(Runnable callback) {
        running = false;
        try {
            AvailabilityChangeEvent.publish(context, ReadinessState.REFUSING_TRAFFIC);

            drain();
        } finally {
            callback.run();
        }
    }
```
The order is the point: flip readiness, then drain, and always call the callback so the container does not wait out the whole phase timeout.

**Code:** `services/ingest-gateway/src/main/java/com/streampulse/ingest/lifecycle/IngestDrainLifecycle.java:120-154` (the drain loop and its timeout log), `services/ingest-gateway/src/main/resources/application.yml:20-23` (the per-phase limit), `services/ingest-gateway/src/main/resources/application.yml:193-195` (the drain timeout), `MILESTONES.md:1094-1097` (what the drain test does and does not prove)

**If they push:** "What happens if the drain times out?" The code logs an error saying how many batches were still in flight and that those clients will not get a 202 and must retry, and it relies on idempotency to make that safe.

### M4. Why are the JVM options set through `JAVA_TOOL_OPTIONS` rather than in the entrypoint?

**Answer:** The entrypoint is in exec form, `["java", "-jar", "/app/app.jar"]`, so there is no shell to expand a variable or append arguments. The JVM itself reads the `JAVA_TOOL_OPTIONS` environment variable at startup, so setting the flags through an `ENV` line means whoever deploys the image can override them by setting that variable, with no shell script in the image. The Dockerfile comment gives exactly this reason: the chart can override it without a shell in the entrypoint. The trade-off is that the options are applied to every JVM tool launched in that container, not only the server, and the variable shows up in the process environment. There is no chart yet, so the override path is designed but not exercised.

**Example:** `deploy/docker/Dockerfile:30-33`
```dockerfile
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"
USER 10001:10001
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
```
The exec-form array on the last line has no flags in it, so all JVM tuning comes from the environment.

**Code:** `deploy/docker/Dockerfile:26-33` (the options, the user and the entrypoint)

### M5. How do you know the probe groups are tested, and not just configured?

**Answer:** `HealthProbesIT` asserts the shape of both groups, and one assertion is about an omission: the liveness response must contain `livenessState` and must not contain Redis, `readinessState` or circuit breakers. To check that this assertion is not vacuous, I ran a mutation that added Redis to the liveness group with no Redis running. MILESTONES records that three of the four tests failed, with the liveness endpoint answering 503, and that this is the restart-storm failure. The mutation ran locally with the test class against a running application context, not in a cluster, so it shows the endpoint would fail, not what a kubelet would do. It also shows the unit under test is the configuration, since the group membership is written out in the YAML.

**Example:** `services/ingest-gateway/src/test/java/com/streampulse/ingest/adapter/HealthProbesIT.java:99-115`
```java
    void livenessGroupHasNoDependencies() {
        String body = probe("liveness").getBody();

        assertThat(body).contains("livenessState");
        assertThat(body)
                .as("liveness must never depend on anything outside this JVM")
                .doesNotContain("redis")
                .doesNotContain("readinessState")
                .doesNotContain("circuitBreakers");
    }
```
The three `doesNotContain` calls are the assertion about what must be absent.

**Code:** `services/ingest-gateway/src/test/java/com/streampulse/ingest/adapter/HealthProbesIT.java:98-115` (the test), `MILESTONES.md:889-906` (the mutation run and its output)

**If they push:** "Does that prove readiness is right?" No. The readiness group has only `readinessState` for now, and the config comment says a dependency belongs there only if a request cannot be served without it, to be revisited with measurements.

### M6. Does CI build the image?

**Answer:** No. The GitHub Actions workflow runs `./gradlew spotlessCheck` and `./gradlew build --no-daemon`, plus report uploads, and nothing builds a Docker image. It does not validate Terraform either. The Dockerfile has a comment saying its extract flags were checked against the jar tools help text, but I found no recorded `docker build` in MILESTONES, so I cannot claim the image builds, only that the recipe is written. I also have not checked whether the jar name the recipe copies matches what `bootJar` produces for every service; for ingest-gateway there is a local `ingest-gateway.jar` in the build output, which is only one of them. If I were making this real, a CI step that builds one image would be the first guard.

**Example:** `.github/workflows/ci.yml:45-49`
```yaml
      - name: Verify formatting
        run: ./gradlew spotlessCheck

      - name: Build and test
        run: ./gradlew build --no-daemon
```
These are the only two verification steps; neither touches `deploy/docker`.

**Code:** `.github/workflows/ci.yml:45-49` (the verification steps), `deploy/docker/Dockerfile:11-13` (the claim that the flags were checked)

---

## Senior

### S1. How would you autoscale the stream processor and the sink?

**Answer:** Autoscaling is design thinking here, not something I built, because there are no Kubernetes manifests. I would not scale these on CPU, since a consumer is bound by partition count and consumer lag. I would scale on lag, for example with KEDA, up to the number of input partitions, because replicas beyond that sit idle. In compose the raw topic has 12 partitions, which is the ceiling for the processor, and the aggregate topic the sink reads has 6. The processor is stateful, with Kafka Streams state and changelog restore, so a scale-in or restart has a restore cost, and its state directory defaults to `/tmp`. The crash test also found that a restart after a long outage dropped on-time events, a defect that is still open, so scaling it carelessly could make that worse.

**Example:** `services/stream-processor/src/main/resources/application.yml:14-16`
```yaml
    streams:
      application-id: stream-processor
      state-dir: ${STATE_DIR:/tmp/streampulse/stream-processor}
```
The state directory is configurable; where it lives in a pod, and whether it survives a restart, would be a design decision I have not made.

**Code:** `services/stream-processor/src/main/resources/application.yml:14-16` (the state directory), `docker/compose.yml:110-111` (partition counts for the raw and aggregate topics), `docs/interview/phases/phase-03-stream-processing.md` (the crash test and the dropped events)

**If they push:** "Why does a restart lose events at all?" It is not Kafka losing data; stream time jumped past windows that still had on-time events while catching up, and the 5 second grace dropped them, which is the open defect in `MILESTONES.md` for Phase 9.

### S2. gRPC behind Kubernetes: what is the load-balancing trap?

**Answer:** gRPC uses long-lived HTTP/2 connections, so a layer-4 balancer, which balances connections, pins a client to one pod. Many calls travel over that one connection, so scaling to ten pods can leave nine of them idle until clients reconnect, and nothing errors or logs, because the symptom is only an uneven fleet. A CPU-based autoscaler would see one hot pod and react wrongly. ADR-0010 lists three options: a layer-7 proxy or mesh that balances per call, client-side round-robin over a headless service, or bounding the connection age on the server so clients reconnect. None of them is configured; the ADR says so, and there are no manifests. The ingest configuration does put gRPC on its own port, with a comment that it would need its own Kubernetes service for this reason.

**Example:** `docs/adr/0010-grpc-as-a-second-ingest-protocol.md:237-239`
```markdown
| **Service mesh / L7 proxy** | Envoy, Linkerd or Istio terminates HTTP/2 and balances per RPC | a sidecar per pod, a control plane, and a new thing to operate |
| **Client-side load balancing** | headless `Service`, `dns:///` resolver, `round_robin` policy in the channel | the client must be a gRPC client you control; DNS refresh becomes your problem |
| **Bound the connection age** | `ServerBuilder.maxConnectionAge(...)` + `maxConnectionAgeGrace(...)`: the server politely asks clients to reconnect periodically, so they re-resolve and redistribute | reconnection churn; a mitigation, not a fix — load is uneven *between* reconnections |
```
Each option has a cost column, and the third is described as a mitigation, not a fix.

**Code:** `docs/adr/0010-grpc-as-a-second-ingest-protocol.md:211-247` (the trap and the options), `services/ingest-gateway/src/main/resources/application.yml:197-204` (gRPC on its own port)

### S3. What would a pod disruption budget and a preStop hook buy you?

**Answer:** Neither exists in this repo; this is design reasoning, not evidence. A pod disruption budget bounds how many replicas a voluntary eviction, such as a node drain, may remove at once, so a cluster upgrade cannot take every ingest pod down together. A short preStop sleep delays the SIGTERM so the endpoint removal has time to propagate and the load balancer stops sending before the app begins to shut down. The code already knows about the gap: a comment in `IngestDrainLifecycle` says that flipping readiness does not stop traffic instantly, that this is why the HTTP connector stops after readiness, and that in Kubernetes a preStop sleep covers the same gap and Phase 11 adds it. Phase 11 has not added it. The sleep would also count against the grace period.

**Example:** `services/ingest-gateway/src/main/java/com/streampulse/ingest/lifecycle/IngestDrainLifecycle.java:47-51`
```java
 * Flipping readiness does not instantly stop traffic: the endpoint removal has to propagate
 * through the load balancer, which takes a moment. During that window requests still arrive
 * and must still be served — which is exactly why the HTTP connector stops <em>after</em>
 * readiness, not at the same time. In Kubernetes a {@code preStop} sleep covers the same
 * gap; Phase 11 adds it.
```
The last sentence is a plan written in code; I treat it as an intention, not a fact about a deployed pod.

**Code:** `services/ingest-gateway/src/main/java/com/streampulse/ingest/lifecycle/IngestDrainLifecycle.java:34-51` (the phase order and the race), `services/ingest-gateway/src/main/resources/application.yml:193-195` (the drain timeout that must fit in the grace period)

**If they push:** "How long would the preStop sleep be?" I have not chosen a number and I would not invent one; it would have to be measured against how long endpoint propagation takes in the cluster, and it adds to the sum that must fit inside the grace period.

### S4. What would you build first to make Phase 11 real?

**Answer:** I would start by proving the image exists: build it for one service, record the real output in MILESTONES, and add the ADR that the Dockerfile already cites, because ADR-0023 does not exist. Then I would add a chart with probes, resource limits and a grace period set above the sum of the shutdown waits, and run it on a local cluster. The proof I want is a rolling restart under load with the accounting script showing zero loss, which is what the drain design is for. Last, I would put the image build in CI, since the workflow runs only the format check and the Gradle build, and add the Terraform checks that also are not in CI. The Helm directories and `specs/11-containers-k8s` are empty now, so the spec and design come before the chart, as the project process requires.

**Code:** `deploy/docker/Dockerfile:1-3` (the recipe and its missing ADR reference), `scripts/accounting-check.py:1` (the script that would check for zero loss), `.github/workflows/ci.yml:45-49` (where a build step would go)

**If they push:** "What is the first thing that could go wrong?" The recipe copies `<module>.jar` for every service, and I have only seen that name for one of them; I would build each service's image before trusting the shared recipe.

### S5. Why is Redis not part of the readiness group, and when would a dependency belong there?

**Answer:** A dependency belongs in readiness only if a request genuinely cannot be served without it. The ingest gateway claims event ids in Redis for deduplication, but it fails open: when Redis is unreachable it publishes anyway and counts it, accepting duplicates over losing telemetry, as a deliberate trade recorded in ADR-0007. So a Redis outage is not a reason to remove pods from the load balancer. The configuration comment says Kinesis would qualify for readiness and Redis does not, and that Phase 9 would revisit membership with measurements, which I have not done. The cost of this choice is that readiness says nothing about the broker today; the gateway relies on its circuit breaker and backpressure for that instead. Putting a fail-open dependency in readiness would take every pod out of rotation together.

**Example:** `services/ingest-gateway/src/main/resources/application.yml:186-187`
```yaml
    idempotency-ttl: 24h              # dedup keys expire, or the set grows forever
    fail-open-on-idempotency-error: true
```
The flag is why Redis cannot be a readiness dependency: ingest keeps serving with it down.

**Code:** `services/ingest-gateway/src/main/resources/application.yml:134-145` (the readiness group and its comment), `services/ingest-gateway/src/main/resources/application.yml:186-187` (the fail-open flag)

---

## Say this honestly

- Only a Dockerfile exists; it has never been built in a recorded run and is not in CI or compose.
- ADR-0023 is cited but missing. The Helm and spec directories are empty.
- Everything about Kubernetes in this pack is design thinking, not experience from this repo.

## Stories and numbers

None yet; nothing here was measured.
