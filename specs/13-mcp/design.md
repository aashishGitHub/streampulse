# Phase 13 — Agent tooling: Design

## Flow of control and data

```
 Claude Code ── spawns child process, JSON-RPC over stdin/stdout (stderr = logs) ──▶ mcp-server (this module)
      │   initialize, tools/list, tools/call
      ▼
 adapter.in.mcp  McpTools (3 annotated methods)            ◀── the only place that knows the protocol
      │  1 validate args (ToolInputs: TenantId rule, bounded ids) ── invalid ─▶ error result, NO backend call
      │  2 call a use case                    ┌── BackendFailure ──▶ error result "<backend> <category>"
      ▼                                       │
 application  LiveMetricsService · ActiveAlertsService · ExplainAlertService      (Clock injected)
      │  3 tenant filter: drop any item whose tenant != the asked tenant
      │  4 bound lists (limits + omitted count)
      ▼
 application.port  MetricsPort · AlertsPort        (read-only: only get/list methods; ArchUnit)
      ▼
 adapter.out.http  QueryApiClient · AlertEngineClient        GET only, per-call timeout, body limit
      │                                         (GET /v1/titles/{id}/overview, /v1/metrics/{tenant}/{metric}, /v1/alerts)
      ▼
 query-api :8082          alert-engine :8086        (not started by this module's tests: in-JVM fakes)

 back out:  use-case result ─▶ adapter.in.mcp.Views ─▶ { summary (trusted, generated),
                                                          trusted fields (enums, numbers, instants, strict ids),
                                                          "untrusted": { free text, sanitised, capped } }
```

Control flow of one `explain_alert(tenant, alertId)`:

```
validate ─▶ alerts = AlertsPort.activeAlerts(tenant) ─▶ filter(tenant)
   not found ─▶ found=false + note (resolved alerts are not retained by the backend)
   found ─▶ root = state==SUPPRESSED ? lookup(suppressedBy) in the SAME filtered list : none
        ─▶ series = parse "titleId:region:metric"
              ok ─▶ MetricsPort.metricWindows(tenant, metric, title?, region?, now-lookback, now)
                       failure ─▶ recentMetrics = Unavailable(query-api, category)
              not parseable ─▶ recentMetrics = Unavailable("series not in titleId:region:metric form")
        ─▶ ruleParameters = "not exposed by the alert API" (constant)
```

## What the backends do and do not expose (read from the contracts, not assumed)

| Need | Source | Exposed | Not exposed |
|---|---|---|---|
| Active alerts | `GET /v1/alerts?tenantId=` | alertId, tenantId, ruleId, series, state, severity, summary, firedAt, resolvedAt, suppressedBy | **threshold, comparison, window, observed value, rule name, rule node** (they exist inside the engine, not on the wire) |
| One alert by id | none | n/a | there is no get-by-id: `explain_alert` lists and filters |
| Resolved alerts | none | n/a | `GET /v1/alerts` omits RESOLVED; so does explain |
| Latest value of one title | `GET /v1/titles/{titleId}/overview?tenantId=` | latest[] with windowStart, windowEnd, value, sampleCount, computedAt; `degraded` | |
| Latest value across titles | `GET /v1/metrics/{tenantId}/{metric}?from&to` | windows with windowStart, value, sampleCount, titleId, region | **windowEnd** (age is then measured from windowStart and says so) |
| Authentication | both | none enforced | tenantId is asserted by the caller on both |

Because there is no "latest for all titles" call, `get_live_metrics` without a title queries the last
`lookback` (15 min, target) of each of the two metrics and keeps the newest window per title and region.

## Failure modes

| Failure | Behaviour | Test that proves it |
|---|---|---|
| Tenant id malformed / too long / hostile | error result naming `tenantId`; zero backend requests | `ToolInputsTest`, `McpToolsTest.invalidTenantNeverReachesBackend` |
| titleId / alertId empty, too long, bad characters | error result naming the argument; zero requests | `ToolInputsTest` |
| Backend refuses connection | error result "alert-engine unreachable", no data | `HttpAlertEngineClientTest.unreachable`, `McpToolsTest.backendDownSaysUnreachable` |
| Backend slower than the time limit | error result "timed out" | `HttpAlertEngineClientTest.timeout` |
| Backend 4xx problem+json | error result "rejected the request (status)", body not echoed | `HttpAlertEngineClientTest.clientError` |
| Backend 5xx | error result "failed (status)" | `HttpAlertEngineClientTest.serverError` |
| Malformed JSON / wrong shape | error result "malformed response" | `HttpAlertEngineClientTest.malformedJson`, `...wrongShape` |
| Body over the limit | error result "response too large", read stops early | `HttpAlertEngineClientTest.oversizedBody` |
| Backend returns another tenant's items | dropped before counting or rendering | `ActiveAlertsServiceTest.dropsOtherTenants`, `ExplainAlertServiceTest.otherTenantsAlertIsNotFound`, `McpToolsTest.mixedTenantBackend` |
| Alert summary contains an instruction | confined to `untrusted.summary`, capped, sanitised; absent from `summary` | `UntrustedTextTest`, `PromptInjectionTest` |
| Series label / ruleId not identifier-shaped | demoted into the untrusted field | `SeriesTest`, `PromptInjectionTest.hostileSeriesLabel` |
| More alerts / rows than the limit | cut, `truncated: true`, `omittedCount` | `ActiveAlertsServiceTest.truncates`, `McpToolsTest.resultSizeIsBounded` |
| Alert found, metrics backend down | alert returned, `recentMetrics` Unavailable with reason | `ExplainAlertServiceTest.metricsDown` |
| Alert not active | `found=false` + note | `ExplainAlertServiceTest.notFound` |
| Root cause not in the active list | `rootCause` carries only the validated id and a note | `ExplainAlertServiceTest.rootCauseMissing` |
| A tool tries a non-GET call | build fails | `McpArchitectureRulesTest.httpAdapterIsGetOnly`, `HttpQueryApiClientTest.onlyGetRequestsAreSent` |
| Domain imports a framework | build fails | `McpArchitectureRulesTest.domainIsFrameworkFree` |
| Something writes to stdout | client cannot parse the first message | `StdioProtocolIT.stdoutCarriesOnlyProtocolMessages` |
| Wrong tools exposed or not read-only | list differs | `McpProtocolIT.toolsListIsExactlyThreeReadOnly` |
| Context does not start | no Docker needed | `McpServerWiringTest` |

## Not mitigated (stated, not hidden)

- A client that copies an `untrusted` field into its own prompt as an instruction is exploitable
  regardless of what this server does. Labelling and capping reduce the surface; they do not remove it.
- Neither backend authenticates, and neither does this server: whoever can run the process can ask about
  any tenant. `tenantId` is a filter the caller asserts.
- The metrics query returns no tenant field, so isolation on that path rests on the backend honouring
  the path parameter. Only the overview and the alert list carry a tenant that can be re-checked here.
- A tenant can still shape what the agent reads by choosing metric values and rule names that are
  *valid identifiers* but misleading.

## Build, launch, try

```
./gradlew :services:mcp-server:bootJar          # builds services/mcp-server/build/libs/mcp-server.jar
claude mcp get streampulse                      # reads the repo's .mcp.json; first use shows "Pending approval"
claude                                          # start Claude Code in the repo, approve the project server, then /mcp
claude mcp list                                 # expect: streampulse ... Connected
```

`.mcp.json` launches `java -jar ${CLAUDE_PROJECT_DIR:-.}/services/mcp-server/build/libs/mcp-server.jar`
(STDIO) with `STREAMPULSE_MCP_QUERYAPI_BASEURL` / `STREAMPULSE_MCP_ALERTENGINE_BASEURL` (default
`http://localhost:8082` / `:8086`). The env-var names are Spring's relaxed binding of
`streampulse.mcp.query-api.base-url` (dots become underscores, dashes are removed); the protocol IT passes them
exactly this way. Needs the stack up (`make up`) for real answers; the tests do not.

## Decisions

- ADR-0022 (read-only tools, transport, injection model, why no write tools, measuring value).
