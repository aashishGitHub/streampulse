# loadgen

A plain Java 21 load generator (JDK `HttpClient` + Jackson, no framework) that produces one
measured number for the whole pipeline: events/s accepted by ingest, ingest latency, and p99
freshness at the hot store.

## Run

```
make up-core
```

Start ingest-gateway, stream-processor and sink-writer, then:

```
LOADGEN_CLIENT_SECRET=<secret> ./gradlew :tools:loadgen:run --args="--rate 2000 --duration 60"
```

`LOADGEN_CLIENT_SECRET` is the `ingest-gateway` client secret. It is defined in
`docker/keycloak/realms/streampulse-realm.json` (client `ingest-gateway`, field `secret`).
It is read only from the environment and never printed.

| Option | Default | Meaning |
|---|---|---|
| `--rate` | 1000 | target events/s |
| `--duration` | 30 | seconds of load |
| `--batch` | 100 | events per request, 1-500 |
| `--sessions` | 1000 | distinct session ids (events of one session share a Kafka partition) |
| `--late-fraction` | 0 | share of events stamped 1-4 s in the past, to exercise the grace period |
| `--url` | http://localhost:8080 | ingest base URL |
| `--keycloak` | http://localhost:8081 | Keycloak base URL (realm `streampulse`) |
| `--freshness-url` | http://localhost:8084/actuator/prometheus | sink-writer Prometheus endpoint |

Ingest's default tenant quota is 2000 events/s sustained (`quota-refill-per-second` in
`services/ingest-gateway/src/main/resources/application.yml`, counted in events). Above
roughly that rate you measure the quota (429s), not the pipeline. Raise the quota first if you
want to push further.

## What each number means

- **accepted events/s**: events in 202 responses divided by wall time (includes draining the
  last in-flight requests). This is the headline throughput.
- **offered events/s**: events actually sent per second. Below `--rate` means the generator
  was held back (in-flight limit of 256, or a `Retry-After` pause). **max send lag** says how
  far behind schedule a send ever was.
- **429 / 503**: backpressure, counted separately from errors. Both honour `Retry-After`
  (seconds form only; the schedule shifts, it does not burst afterwards). `other` and
  `io-errors` are the real failures.
- **ingest latency p50/p95/p99**: request sent to response received, 202 responses only
  (fast rejections would flatter it). Nearest-rank percentiles over every 202.
- **freshness p99**: the sink's `streampulse_sink_freshness_seconds` histogram is scraped
  before and after the run and the bucket counts are subtracted, so it covers only this run.
  The p99 is an **estimate**: it assumes observations are evenly spread inside the bucket that
  holds the 99th percentile (what PromQL `histogram_quantile` does), so its error is bounded by
  that bucket's width. If the rank lands in the `+Inf` bucket it reports the highest finite
  bound. If the endpoint is unreachable it prints `freshness: unavailable`.

## What it does NOT prove

- One Kafka broker, one laptop, load generator and system under test sharing the same CPU.
- Not production, and not comparable to any cloud deployment.
- Latency is service time from the moment of sending; it is not corrected for coordinated
  omission, so a stalled server shows up as lower offered rate and max send lag, not as
  inflated latency.
- Synthetic traffic: random sessions, titles and a fixed event-type mix. Real session shapes
  (long-lived viewers, bursty joins) will behave differently.
- Token lifetime is 300 s in the realm; the generator refreshes it, but a longer run also
  exercises Keycloak.
