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

**A strong answer contains**
- SLI: a measured ratio of good events to all events (for example, requests that did not fail).
- SLO: the target for the SLI over a window (for example 99.9% over 30 days).
- Error budget: the allowed failure, `1 - SLO`. It turns "how reliable" into a spendable quantity that can
  justify slowing releases.

**Open** `docker/observability/rules/streampulse-sli.rules.yml`

### B2. Why alert on p99 and not the mean?

**A strong answer contains**
- A mean hides the tail: a few very slow requests barely move it, but those are the ones users feel.
- Percentiles need a histogram; the ingest service publishes a percentile histogram for HTTP requests.
- Percentiles cannot be averaged across instances; aggregate the histogram buckets, then compute the
  quantile.

**Open** `services/ingest-gateway/src/main/resources/application.yml`

### B3. What is Prometheus doing, and what does Micrometer do?

**A strong answer contains**
- Micrometer is the metrics facade inside the JVM; the Prometheus registry exposes `/actuator/prometheus`.
  Prometheus *pulls* (scrapes) it on an interval and stores time series.
- Counters only go up; rates are computed at query time.

**Open** `build-logic/src/main/kotlin/streampulse.spring-service.gradle.kts`

### B4. What is a recording rule?

**A strong answer contains**
- A query evaluated on a schedule whose result is stored as a new series. It makes dashboards and alerts
  use the same definition and makes heavy queries cheap.
- The naming scheme here is `sp:<sli>:<operation><window>`.

**Open** `docker/observability/rules/streampulse-sli.rules.yml`

### B5. Why are metric tags kept low-cardinality?

**A strong answer contains**
- Every distinct tag combination is a separate time series; an unbounded tag (a user id, an event id)
  multiplies storage and can take Prometheus down.
- That is why the rejection reason is an enum and the ingest counters carry no tenant tag.

**Open** `services/ingest-gateway/src/main/java/com/streampulse/ingest/config/MicrometerIngestMetrics.java`

---

## Mid

### M1. Explain multiwindow, multi-burn-rate alerting.

**A strong answer contains**
- Burn rate is how fast the error budget is spent relative to plan; 1x spends exactly the budget over the
  window. The rules use 14.4x over 1 h and 5 min (page), 6x over 6 h and 30 min (page), and 1x over 3 days
  and 6 h (ticket), following the SRE Workbook, which the file cites.
- Why two windows: the long one proves the problem is real and large enough to matter; the short one
  proves it is *still happening*, so the alert clears quickly after recovery.
- My arithmetic, not the repo's: at a 30-day SLO, 14.4x for one hour spends 2% of the budget, 6x for six
  hours spends 5%, and 1x for three days spends 10%.

**Open** `docker/observability/rules/streampulse-alerts.rules.yml`

### M2. Symptom alerts versus cause alerts: what are the cause alerts here?

**A strong answer contains**
- Page on user-visible symptoms (the burn rates); use cause alerts as diagnostics: target down, consumer lag
  growing, sink writes failing, sink queue near full (1,600 of 2,000), dead letters, processor reject
  ratio, idempotency store unavailable, Redis projection failing.
- Cause alerts let on-call jump to the likely component once a symptom fires.

**Open** `docker/observability/rules/streampulse-alerts.rules.yml`

### M3. Why does a `TargetDown` alert exist when you already alert on error ratios?

**A strong answer contains**
- A ratio of nothing over nothing never fires. If a service is down, no requests fail, so an error-ratio
  alert stays silent.
- The recording rules also use `or vector(0)` so an empty series yields zero rather than no data.

**Open** `docker/observability/rules/streampulse-sli.rules.yml`

### M4. Why does the freshness SLI use an odd bucket boundary?

**A strong answer contains**
- The SLO says "within 30 s", but the histogram has no 30 s boundary, so the rule uses the nearest boundary
  below it (about 28.6 s). The SLI is therefore slightly stricter than the SLO, and it must be re-checked
  after any metrics-library upgrade.

**Open** `docker/observability/rules/streampulse-sli.rules.yml`

### M5. How would you test alert rules?

**A strong answer contains**
- `promtool test rules` with synthetic series and expected firing: "5% 5xx fires every burn-rate alert",
  "0.05% fires nothing". That test file exists.
- **It has never been run and is not in CI.** The right next step is to run it, then wire it in.

**Open** `docker/observability/rules/tests/streampulse-alerts.test.yml`

---

## Senior

### S1. How would you set the SLO targets rather than assume them?

**A strong answer contains**
- The 99.9% availability and "99% of aggregates durable within 30 s of window end" are **targets, not
  measurements**. Measure the real distribution under realistic load first.
- Measured so far: freshness lands 5.2 to 5.4 s after window end (Phase 9). That supports a 30 s freshness
  objective with large headroom, but says nothing about behaviour during an outage (the crash test showed
  data dropped silently).
- Pick objectives from user need and the cost of each extra nine, then revisit quarterly.

**Open** `docs/interview/06-numbers.md`

### S2. What would your first on-call page look like, and what is missing?

**A strong answer contains**
- Symptom fires, an annotation links a runbook. **The runbook does not exist**: `MISSING:docs/runbook.md`
  and `MISSING:docs/system-health.md` are linked from the alert annotations but are absent from the repo
  (the checker asserts they stay absent until someone writes them).
- Missing signals: no metric or alert for late-dropped events (found in the Phase 9 crash test); no
  in-flight-permit gauge on ingest; control-plane exposes no custom metrics.

**Open** `docker/observability/rules/streampulse-alerts.rules.yml`

### S3. A metric lied to me once. Tell me about it.

**A strong answer contains**
- The load generator's freshness percentile read 39 s and 113 s while per-window rows in Postgres said 5.3 s.
  A previous run's still-open window was closed by the next run's first events, so it entered the histogram
  very late.
- Lesson: validate a percentile against raw rows before believing it; know what can poison a histogram.

**Open** `docs/interview/05-what-broke.md` story 10.

### S4. The OpenTelemetry story: what exists and what does not?

**A strong answer contains**
- Exists: a collector config, Tempo and Loki configs, a script that downloads the Java agent with checksum
  verification, and structured JSON logging that carries trace ids when the agent is attached.
- Does not exist: **no service attaches the agent**, so no trace has ever been produced. The compose override
  that wires the stack is not used by any Makefile target, and the docs say it was not run.
- Also: only four of the services are scraped by Prometheus; alert-engine, control-plane, activity-feed and
  the MCP server are not.

**Open** `docker/observability/compose.obs.yml`

### S5. What would you do to make Phase 8 true rather than drafted?

**A strong answer contains**
- Run `promtool test rules`; start the observability profile with the rules mounted; drive load (the load
  generator exists); confirm a burn-rate alert fires and clears, and screenshot or record it.
- Add the missing docs, wire the rules into `make up-obs`, add the missing scrape targets and metrics, then
  write the Phase 8 `MILESTONES.md` entry with real output.

**Open** `specs/08-observability/`

---

## Say this honestly

- Written, not wired, not verified. No Phase 8 entry exists in `MILESTONES.md`.
- The README table still shows Phase 8 as not done, which is the accurate status.
- All SLO targets and burn-rate thresholds are drafts.

## Stories and numbers

[What broke](../05-what-broke.md): story 10. [Numbers](../06-numbers.md): freshness rows.
