# Phase 8 — Observability, SLOs, error budgets

**Status: partly built, and never run.** Prometheus recording and alert rules, a rules test file, four
Grafana dashboards and an OpenTelemetry collector config exist. **Nothing has been started and checked
against live data**, `promtool` has never been run on the tests, no service attaches the OTel agent, and
`make up-obs` does not load the rules. `specs/08-observability/` is an empty directory and `MILESTONES.md`
has no Phase 8 entry. Custom Micrometer metrics do exist in the services, and Phase 9 used some of them.

**Say this first, because it is the strongest honest framing:** "I wrote the SLO rules and dashboards,
but I have not verified them against a running stack, so I treat them as drafts. What I did verify is the
metrics the services emit, because I used them to measure saturation."

## Code map

| Thing | Where |
|---|---|
| SLI recording rules | `docker/observability/rules/streampulse-sli.rules.yml` |
| Burn-rate and cause alerts | `docker/observability/rules/streampulse-alerts.rules.yml` |
| Rule tests (`promtool`, never run) | `docker/observability/rules/tests/streampulse-alerts.test.yml` |
| Dashboards | `docker/observability/grafana/dashboards/streampulse-slo.json`, `streampulse-red.json`, `streampulse-jvm.json`, `streampulse.json` |
| Collector, Tempo, Loki | `docker/observability/otel/collector.yaml`, `tempo.yaml`, `loki.yaml` |
| Override file that mounts the rules | `docker/observability/compose.obs.yml` (not used by any Makefile target) |
| Custom metrics, ingest | `services/ingest-gateway/src/main/java/com/streampulse/ingest/config/MicrometerIngestMetrics.java` |
| Custom metrics, alert engine | `services/alert-engine/src/main/java/com/streampulse/alert/config/MicrometerAlertMetrics.java` |

---

## Beginner

### B1. What are an SLI, an SLO and an error budget?

**Answer:** An SLI is a service level indicator: a measured ratio of good events to all events, for example the share of requests that did not fail. An SLO is the target I set for that indicator over a window, for example 99.9 percent over 30 days. The error budget is what the target allows to go wrong, which is one minus the SLO, so 0.1 percent here. It matters because it turns "how reliable should we be" into a quantity that can be spent, and a team that has spent it has a concrete reason to slow releases. In this repo every SLI is written as an error ratio, bad over valid, so that the alerts can compare it directly with a multiple of the budget. The 99.9 percent figure is a target I chose, not something I measured.

**Example:** `docker/observability/rules/streampulse-sli.rules.yml:4-5`
```yaml
  # Naming: sp:<sli>:<operation><window>. Every SLI is an ERROR ratio (bad / valid), because the
  # burn-rate alerts compare it with a multiple of the error budget.
```
Point at the choice to store the bad ratio instead of the good one: it makes the burn-rate comparison a single greater-than.

**Code:** `docker/observability/rules/streampulse-sli.rules.yml:1-8` (the file header and the naming), `docker/observability/rules/streampulse-alerts.rules.yml:13` (the budget arithmetic for the ingest SLO)

### B2. Why alert on p99 and not the mean?

**Answer:** A mean hides the tail. A few very slow requests barely move it, yet those are the requests a user actually feels. To get a percentile you need a histogram, because a plain gauge or a mean cannot be turned back into a quantile, so the ingest service publishes a percentile histogram for HTTP server requests, and without it only the mean and the maximum exist. A percentile also cannot be averaged across instances: you add up the histogram buckets across instances first and then compute the quantile, which is what the recording rule does with `sum by (le)`. The cost is that a histogram makes many time series and its accuracy is limited by the bucket boundaries.

**Example:** `docker/observability/rules/streampulse-sli.rules.yml:38-41`
```yaml
      - record: sp:ingest_latency_p99:rate5m
        expr: |
          histogram_quantile(0.99,
            sum by (le) (rate(http_server_requests_seconds_bucket{job="ingest-gateway",uri="/v1/events:batch"}[5m])))
```
Point at `sum by (le)` inside `histogram_quantile`: the buckets are aggregated first, and only then is the quantile taken.

**Code:** `services/ingest-gateway/src/main/resources/application.yml:155-157` (the percentile histogram switch for `http.server.requests`), `docker/observability/rules/streampulse-sli.rules.yml:38-41` (the p99 recording rule)

### B3. What is Prometheus doing, and what does Micrometer do?

**Answer:** Micrometer is the metrics facade inside the JVM: application code records counters and timers against it, and a registry for a specific backend decides how they are exposed. With the Prometheus registry on the classpath, Spring Boot publishes them at `/actuator/prometheus`. Prometheus pulls: it scrapes that endpoint on an interval, which is 5 seconds in this repo's config, and stores the result as time series. A counter only ever goes up, so a rate is computed at query time with `rate()`, not stored. Every service gets the actuator and the Prometheus registry from one shared Gradle convention plugin, so the endpoint is consistent. The scrape config lists only four services, which I cover again in the OpenTelemetry question.

**Example:** `build-logic/src/main/kotlin/streampulse.spring-service.gradle.kts:39-40`
```kotlin
    add("implementation", lib("spring-boot-starter-actuator")) // health, metrics, probes
    add("implementation", lib("micrometer-prometheus")) // /actuator/prometheus
```
Point at the fact that this is one place in the build logic, so a new service gets the endpoint without copying anything.

**Code:** `build-logic/src/main/kotlin/streampulse.spring-service.gradle.kts:39-40` (the dependencies), `docker/observability/prometheus.yml:1-3` (the 5 s scrape interval), `docker/observability/prometheus.yml:23-26` (one scrape job)

### B4. What is a recording rule?

**Answer:** A recording rule is a query that Prometheus evaluates on a schedule and stores as a new time series. It does two things for me. A dashboard panel and an alert read the same stored series, so they cannot disagree about what "error ratio" means, and a heavy query over a long window is computed once instead of on every panel refresh. The naming scheme here is `sp:<sli>:<operation><window>`, so `sp:ingest_error_ratio:rate5m` is the ingest error ratio as a 5 minute rate. One detail worth knowing is the `or vector(0)` on the numerator, which keeps the ratio at zero when no 5xx series exists yet, instead of the whole expression returning no data. These rules have never been loaded by a running Prometheus.

**Example:** `docker/observability/rules/streampulse-sli.rules.yml:17-20`
```yaml
      - record: sp:ingest_error_ratio:rate5m
        expr: |
          (sum(rate(http_server_requests_seconds_count{job="ingest-gateway",uri="/v1/events:batch",status=~"5.."}[5m])) or vector(0))
          / sum(rate(http_server_requests_seconds_count{job="ingest-gateway",uri="/v1/events:batch",status!~"4.."}[5m]))
```
Point at the numerator wrapped in `or vector(0)`, and at the denominator, which counts everything except 4xx.

**Code:** `docker/observability/rules/streampulse-sli.rules.yml:17-36` (the same ratio at five windows), `docker/observability/rules/streampulse-sli.rules.yml:94-97` (a pipeline SLI named the same way)

### B5. Why are metric tags kept low-cardinality?

**Answer:** Every distinct combination of tag values is a separate time series. A tag with an unbounded value, such as an event id or a session id, would create millions of series and can take Prometheus down, so I treat that as the most common observability mistake and design against it up front. The ingest counters carry no dynamic tags at all, and the class comment says a tenant tag would be acceptable because there are only three tenants, but an id never is. Where a tag is useful, it is a small enum: the stream processor tags rejected events with a reason, and registers a counter for every reason at start-up so that zero rejections can be graphed as a zero instead of as an absent series.

**Example:** `services/stream-processor/src/main/java/com/streampulse/processor/config/MicrometerProcessorMetrics.java:21-28`
```java
        for (RejectionReason reason : RejectionReason.values()) {
            rejected.put(
                    reason,
                    Counter.builder("streampulse.processor.events.rejected")
                            .description("Input records sent to the dead-letter topic, by reason")
                            .tag("reason", reason.name())
                            .baseUnit("events")
                            .register(registry));
```
Point at `reason.name()`: the tag value comes from a closed enum, so the number of series is fixed at compile time.

**Code:** `services/ingest-gateway/src/main/java/com/streampulse/ingest/config/MicrometerIngestMetrics.java:12-26` (the cardinality discipline in the class comment), `services/stream-processor/src/main/java/com/streampulse/processor/config/MicrometerProcessorMetrics.java:21-28` (the enum-tagged counters)

---

## Mid

### M1. Explain multiwindow, multi-burn-rate alerting.

**Answer:** Burn rate is how fast the error budget is being spent relative to plan; a burn rate of 1 would spend exactly the whole budget over the SLO window. The rules use 14.4 times over 1 hour and 5 minutes as a page, 6 times over 6 hours and 30 minutes as a page, and 1 times over 3 days and 6 hours as a ticket, following the SRE Workbook, which the file cites. Each alert needs both windows to exceed the threshold: the long window proves the problem is real and big enough to matter, and the short window proves it is still happening, so the alert clears soon after recovery. By my own arithmetic, not the repo's, at a 30-day SLO 14.4 times for one hour spends 2 percent of the budget, 6 times for six hours spends 5 percent, and 1 times for three days spends 10 percent. All of this is drafted and has never fired against live data.

**Example:** `docker/observability/rules/streampulse-alerts.rules.yml:16-20`
```yaml
      - alert: IngestAvailabilityFastBurn
        expr: |
          sp:ingest_error_ratio:rate1h > (14.4 * 0.001)
          and
          sp:ingest_error_ratio:rate5m > (14.4 * 0.001)
```
Point at the `and` between a long window and a short one, and at `14.4 * 0.001`, which is the burn rate times the 0.1 percent budget.

**Code:** `docker/observability/rules/streampulse-alerts.rules.yml:11-53` (the three ingest alerts), `docker/observability/rules/tests/streampulse-alerts.test.yml:60-76` (0.8 percent errors fire the slow burn but not the fast one)

**If they push:** "Why 14.4?" I did not derive the Workbook's choices myself; what I can show is the arithmetic that the alert summaries state, that 14.4 for an hour is 2 percent of a 30-day budget. Why 2 percent is the right page threshold is a judgement I took from the Workbook.

### M2. Symptom alerts versus cause alerts: what are the cause alerts here?

**Answer:** The symptom alerts are the burn-rate ones, because they describe what a user experiences: ingest failing or aggregates arriving late. The cause alerts are in their own group and exist so that a page arrives with a first diagnostic step. They are target down, consumer lag growing, sink writes failing, sink batch queue near full, dead letters on the sink, processor reject ratio, idempotency store unavailable, and the Redis projection failing. I should be honest that the split is not perfectly clean: target down and sink writes failing are severity page, while the others are tickets. The thresholds, such as 1,600 of a 2,000 record queue, are targets I chose and have not measured against real behaviour.

**Example:** `docker/observability/rules/streampulse-alerts.rules.yml:134-137`
```yaml
      - alert: SinkBatchQueueNearFull
        # 1600 = 80% of streampulse.sink.batch.queue-capacity (2000 in the sink-writer's
        # application.yml). Change both together.
        expr: max(streampulse_sink_batch_queue_depth_records) > 1600
```
Point at the comment: the threshold is coupled to a config value in another file, which is a maintenance risk I flagged in writing.

**Code:** `docker/observability/rules/streampulse-alerts.rules.yml:97-179` (the cause group), `docker/observability/rules/streampulse-alerts.rules.yml:1-8` (the two-kinds comment)

**If they push:** "Why is a cause alert paged at all?" A dead sink writer or a dead scrape target is something the user-facing burn rate could be slow to show, so I accepted paging on those two. I have no incident data to say whether that is right.

### M3. Why does a `TargetDown` alert exist when you already alert on error ratios?

**Answer:** A ratio of nothing over nothing never fires. If a service is down it receives no requests, so no requests fail, the denominator is empty, and the burn-rate alerts stay silent for a completely dead process. The `up` metric is the one signal that Prometheus produces itself when a scrape fails, so an alert on `up == 0` with a two minute `for` catches a dead process. A related detail is the `or vector(0)` in the recording rules: it only covers the case where traffic exists but no 5xx series has appeared yet, so the ratio reads zero instead of no data. It does not rescue the all-quiet case, which is exactly why the target-down alert is needed. The rule covers four jobs, and I have not run it.

**Example:** `docker/observability/rules/streampulse-alerts.rules.yml:99-103`
```yaml
      # A burn-rate alert cannot fire for a service that exports nothing, because a ratio of
      # nothing is nothing. This is the one alert that catches a dead process.
      - alert: StreamPulseTargetDown
        expr: up{job=~"ingest-gateway|stream-processor|sink-writer|query-api"} == 0
        for: 2m
```
Point at the job list: alert-engine, control-plane, activity-feed and the MCP server are not in it, because they are not scraped.

**Code:** `docker/observability/rules/streampulse-alerts.rules.yml:99-108` (the alert), `docker/observability/rules/tests/streampulse-alerts.test.yml:136-147` (a gateway with no 5xx has ratio zero, not no data)

**If they push:** "What does your test say about `or vector(0)`?" It asserts that a gateway that has only returned 202s has `sp:ingest_error_ratio:rate5m` equal to 0, but that test has never been run.

### M4. Why does the freshness SLI use an odd bucket boundary?

**Answer:** The freshness SLO says an aggregate should be durable within 30 seconds of its window end, but a histogram can only answer questions at its bucket boundaries. For this timer, which runs from 1 second to 10 minutes, the comment in the rule file says there is no 30.0 boundary, only 28.633115306 and 34.359738367. The rule uses the lower one, so the SLI is slightly stricter than the SLO: a write that lands at 29 seconds counts as bad. The risk is that the boundary is a property of the metrics library, so after any Micrometer upgrade it has to be checked again, and if it moves the rule goes quiet. I have not checked these two boundaries against a live endpoint myself; I am relying on the comment in the file.

**Example:** `docker/observability/rules/streampulse-sli.rules.yml:45-52`
```yaml
      # "Within 30 s" cannot be read off the histogram exactly: Micrometer's buckets for this timer
      # (1 s to 10 min) have no 30.0 boundary, only 28.633115306 and 34.359738367. The lower one is
      # used, so the SLI is slightly STRICTER than the SLO: a write at 29 s counts as bad.
      # VERIFY the boundary again after any Micrometer upgrade; if it moves, this rule goes quiet.
      - record: sp:freshness_error_ratio:rate5m
        expr: |
          1 - (
            sum(rate(streampulse_sink_freshness_seconds_bucket{job="sink-writer",le="28.633115306"}[5m]))
```
Point at `le="28.633115306"`: a magic number copied from the library's bucket layout, with a VERIFY note on it.

**Code:** `docker/observability/rules/streampulse-sli.rules.yml:43-82` (the freshness rules), `services/sink-writer/src/main/java/com/streampulse/sink/config/MicrometerSinkMetrics.java:27-32` (the timer and its 1 s to 10 min range)

**If they push:** "How would you stop that going stale silently?" A rule test that feeds the exact bucket label would not catch a library change, since the test input is hand-written. A check against a real scrape in CI is the only thing I can think of, and it does not exist.

### M5. How would you test alert rules?

**Answer:** With `promtool test rules`, which takes synthetic time series and the rule files and asserts which alerts fire at which evaluation time. The test file in this repo has scenarios such as 5 percent 5xx firing every burn-rate alert, 0.05 percent firing nothing, 0.8 percent firing the slow burn but not the fast one, a recovered incident clearing the page because the short window empties, and a flood of 429s not diluting real 5xx errors. It also covers consumer lag that is draining or comes from a restore consumer. The important honest point is that it has never been run and is not wired into CI, so I cannot say the expected text and values are right. One thing I would check first is that the test uses a 1 minute evaluation interval while the Prometheus config evaluates every 15 seconds.

**Example:** `docker/observability/rules/tests/streampulse-alerts.test.yml:15-30`
```yaml
  - name: ingest outage at 5 percent 5xx fires every burn-rate alert
    interval: 1m
    input_series:
      - series: 'http_server_requests_seconds_count{job="ingest-gateway",uri="/v1/events:batch",status="202"}'
        values: '0+570x600'
      - series: 'http_server_requests_seconds_count{job="ingest-gateway",uri="/v1/events:batch",status="503"}'
        values: '0+30x600'
    alert_rule_test:
      - eval_time: 90m
        alertname: IngestAvailabilityFastBurn
        exp_alerts:
          - exp_labels: {severity: page, slo: ingest-availability}
            exp_annotations:
              summary: "Ingest is spending 2% of its 30-day error budget per hour"
              description: "5xx ratio on POST /v1/events:batch over 1h is 5% (target budget 0.1%)."
```
Point at the series: 570 good and 30 bad requests per minute is 30 of 600, which is 5 percent, so the expected description says 5 percent.

**Code:** `docker/observability/rules/tests/streampulse-alerts.test.yml:15-47` (the 5 percent scenario), `docker/observability/rules/tests/streampulse-alerts.test.yml:97-114` (a recovered incident stops the page)

**If they push:** "What is the next step?" Run it, read every failure, then add it to the build so the rules cannot drift from their tests.

### M6. Why are 4xx responses left out of the ingest error ratio?

**Answer:** A 4xx is the client's mistake or the client's quota: the gateway answered correctly. Including them would let a noisy tenant that hits its rate limit burn my availability budget, so the valid events in the denominator are every request except 4xx, and the bad events are the 5xx responses. A 503 is deliberately counted as bad, because that is the gateway running out of capacity, which is my failure and not the client's. A test captures the property directly: with 99 accepted, 900 throttled and 1 failure per minute, the 429s are neither bad nor valid, so a flood of them cannot dilute the real failures. As with the rest of this phase, that test exists but has not been run.

**Example:** `docker/observability/rules/streampulse-sli.rules.yml:13-16`
```yaml
      # valid = every request except 4xx. A 4xx is the client's mistake or its quota (429); the
      # gateway answered correctly. 5xx includes 503 (our capacity), which IS our failure.
      # `or vector(0)` keeps the ratio at 0 when no 5xx series exists yet instead of
      # disappearing, which is what a healthy gateway looks like.
```
Point at the second line: 429 is excluded and 503 is included, and that choice is the whole definition of the SLI.

**Code:** `docker/observability/rules/streampulse-sli.rules.yml:13-20` (the definition), `docker/observability/rules/tests/streampulse-alerts.test.yml:116-134` (a flood of 429 cannot dilute 5xx)

**If they push:** "Is that fair to the client?" A client that is throttled is not served, so a separate signal for throttling is reasonable; the ingest counters already count throttled events separately, though I have not built an alert on them.

### M7. How does the consumer lag alert avoid paging on a normal recovery?

**Answer:** The alert needs two things at once: the lag has been above the floor for the whole last 10 minutes, using `min_over_time`, and it is still rising, using `deriv`, and then it must hold for 5 more minutes. A big backlog that is shrinking after a restart is recovery, not an incident, so the derivative condition keeps it quiet. The recording rule also excludes the Kafka Streams restore consumer by client id, because that consumer replays changelogs after a restart and is not falling behind. The 5,000 record floor is a target I chose and the comment says so. There is a test for each of the three cases, firing, draining and restoring, but those tests have never been run either.

**Example:** `docker/observability/rules/streampulse-alerts.rules.yml:114-118`
```yaml
        expr: |
          min_over_time(sp:consumer_lag_records:max[10m]) > 5000
          and
          deriv(sp:consumer_lag_records:max[10m]) > 0
        for: 5m
```
Point at the two conditions joined by `and`, then at the `for: 5m` on top.

**Code:** `docker/observability/rules/streampulse-alerts.rules.yml:110-123` (the alert), `docker/observability/rules/streampulse-sli.rules.yml:90-92` (the lag series, restore consumers excluded)

**If they push:** "What does the lag metric actually measure?" The comment in the rules file says it is measured against the fetch position, not the committed offset, so records fetched but not yet acknowledged do not count as lag.

---

## Senior

### S1. How would you set the SLO targets rather than assume them?

**Answer:** The 99.9 percent ingest availability and the "99 percent of aggregates durable within 30 seconds of window end" objectives are targets I chose, not measurements, and I say so in the rule files. To set them properly I would measure the real distribution under realistic load first, then pick objectives from what users need and from what each extra nine costs, and revisit them on a schedule. What I have measured so far is freshness, which landed 5.2 to 5.4 seconds after window end at 1,000 and 2,000 events a second, on one laptop with a single Kafka broker and one or two runs. That supports a 30 second freshness objective with a lot of headroom, but it says nothing about behaviour during an outage: the crash test showed on-time events dropped silently, and a freshness SLI over the aggregates that do arrive would not see that.

**Example:** `docker/observability/rules/streampulse-alerts.rules.yml:55-58`
```yaml
  - name: streampulse.slo.freshness
    rules:
      # Target 99% of aggregates durable within 30 s of window end => budget 0.01.
      - alert: FreshnessFastBurn
```
Point at the word Target and the derived budget of 0.01: the objective is the input, and everything else in the alert is arithmetic from it.

**Code:** `docker/observability/rules/streampulse-alerts.rules.yml:55-95` (the freshness SLO alerts), `docs/interview/06-numbers.md:20-27` (the freshness measurements and their conditions)

**If they push:** "So is the SLI wrong?" The SLI measures lateness of what arrives; it does not measure whether the aggregate is complete. That second quantity needs its own signal, and there is no late-drop counter in the processor's own metrics today.

### S2. What would your first on-call page look like, and what is missing?

**Answer:** A symptom alert fires with a summary, a description that states the ratio and the budget, and an annotation that links a runbook section. The runbook does not exist: `MISSING:docs/runbook.md` and `MISSING:docs/system-health.md` are referenced from the alert annotations and from the rule file comments but are absent from the repo, and the interview-pack checker asserts they stay absent until someone writes them. So the page today would send an on-call person to a dead link. Beyond the docs, I know of missing signals: there is no metric or alert for late-dropped events, which I found in the Phase 9 crash test, there is no gauge for in-flight permits on ingest, and the control plane exposes no custom metrics. I would not claim on-call readiness from this.

**Example:** `docker/observability/rules/streampulse-alerts.rules.yml:16-27`
```yaml
      - alert: IngestAvailabilityFastBurn
        expr: |
          sp:ingest_error_ratio:rate1h > (14.4 * 0.001)
          and
          sp:ingest_error_ratio:rate5m > (14.4 * 0.001)
        labels:
          severity: page
          slo: ingest-availability
        annotations:
          summary: "Ingest is spending 2% of its 30-day error budget per hour"
          description: "5xx ratio on POST /v1/events:batch over 1h is {{ $value | humanizePercentage }} (target budget 0.1%)."
          runbook: "docs/runbook.md#ingest-5xx-or-503-spike"
```
Point at the last line: the anchor names a section of a file that has not been written.

**Code:** `docker/observability/rules/streampulse-alerts.rules.yml:16-27` (labels and annotations on one page), `docker/observability/rules/streampulse-alerts.rules.yml:164-171` (a cause alert whose runbook link is equally dead)

**If they push:** "What would you write first?" The sections that the alerts already name, starting with the 5xx or 503 spike, because the fast burn pages a human.

### S3. A metric lied to me once. Tell me about it.

**Answer:** During the Phase 9 latency runs the load generator reported a freshness p99 of 39 seconds and 113 seconds, while the per-window rows in Postgres said about 5.3 seconds. The cause was in the pipeline's design, not in the sink: a run's last window stays open until later events arrive, so the next run's first events close it, and it is recorded very late inside the next run's histogram. A warm-up does not fix that, because the warm-up leaves its own window open. I found it by checking the percentile against raw rows, and the lesson is to know what can poison a histogram before believing it. This happened in local runs on one laptop, not in production. I did not change the tool, and I inferred the 113 second case, since I did not print that run's leftover window.

**Example:** `services/sink-writer/src/main/java/com/streampulse/sink/config/MicrometerSinkMetrics.java:27-32`
```java
        this.freshness = Timer.builder("streampulse.sink.freshness")
                .description("Window end to durable write")
                .publishPercentileHistogram()
                .minimumExpectedValue(Duration.ofSeconds(1))
                .maximumExpectedValue(Duration.ofMinutes(10))
                .register(registry);
```
Point at the description: the timer measures window end to durable write, so any window closed late by the next run lands in it as a very large sample.

**Code:** `tools/loadgen/src/main/java/com/streampulse/loadgen/FreshnessScraper.java:15-16` (the metric the loadgen reads), `docs/interview/05-what-broke.md:135-143` (story 10)

**If they push:** "How do you avoid it next time?" Check the figure against the raw rows first. I would also reset or restart the stack between runs, but I have not built that.

### S4. The OpenTelemetry story: what exists and what does not?

**Answer:** What exists is a collector config, Tempo and Loki configs, a script that downloads the Java agent and verifies its checksum, and an opt-in structured JSON logging profile that carries trace ids when an agent is attached. The collector has a traces pipeline to Tempo and a logs pipeline to Loki, and deliberately no metrics pipeline, because Micrometer already exposes metrics to Prometheus. What does not exist is any service attaching the agent, so no trace has ever been produced. The compose override that wires the stack is not used by any Makefile target, and the docs say it was not run. Separately, only four of the services are scraped by Prometheus; the alert engine, control plane, activity feed and MCP server are not.

**Example:** `docker/observability/otel/collector.yaml:35-44`
```yaml
service:
  pipelines:
    traces:
      receivers: [otlp]
      processors: [memory_limiter, batch]
      exporters: [otlp_grpc/tempo]
    logs:
      receivers: [otlp]
      processors: [memory_limiter, batch]
      exporters: [otlp_http/loki]
```
Point at the absence of a `metrics` pipeline: that is a decision recorded in the file header, not an omission.

**Code:** `docker/observability/otel/collector.yaml:1-6` (why a collector and no metrics pipeline), `docker/observability/compose.obs.yml:15-30` (the collector service in the unused override), `docker/observability/prometheus.yml:23-41` (the four scraped services)

**If they push:** "Why a collector at all?" Services keep one endpoint and one protocol, and batching, sampling or redaction can change in the collector without redeploying a JVM, as the file comment says. None of that has been exercised.

### S5. What would you do to make Phase 8 true rather than drafted?

**Answer:** First I would run `promtool test rules` against the test file and fix whatever it reports, since today the tests are only text. Then I would start the observability profile with the rules actually mounted, drive load with the load generator, confirm that a burn-rate alert fires and then clears, and record it. After that I would write the missing runbook and health docs, add the missing scrape targets and metrics, and wire the rules into the Makefile target, which currently starts Prometheus without them because it uses only the base compose file. The last step is a Phase 8 entry in `MILESTONES.md` with real terminal output. Until then I describe all of this as drafted, because the documented override command has not been run.

**Example:** `Makefile:88-90`
```makefile
.PHONY: up-obs
up-obs: ## Start Prometheus (host :9091) + Grafana (Tempo/Loki arrive in Phase 8)
	$(COMPOSE) --profile obs up -d
```
Point at the missing second `-f`: the Makefile variable names only the base compose file, so the override that mounts the rules is never loaded.

**Code:** `Makefile:13` (the `COMPOSE` variable), `docker/observability/compose.obs.yml:53-56` (the rule and scrape mounts that would be added), `docker/observability/prometheus.yml:5-8` (the rule glob that matches nothing without the mount)

**If they push:** "What is the single most valuable check?" Firing a real burn-rate alert end to end, because it exercises the rules, the scrape and the mounts together.

---

## Say this honestly

- Written, not wired, not verified. No Phase 8 entry exists in `MILESTONES.md`.
- The README table still shows Phase 8 as not done, which is the accurate status.
- All SLO targets and burn-rate thresholds are drafts.
- `MISSING:docs/runbook.md` and `MISSING:docs/system-health.md` are linked from the rules but do not exist.

## Stories and numbers

[What broke](../05-what-broke.md): story 10. [Numbers](../06-numbers.md): freshness rows.
