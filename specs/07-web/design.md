# Phase 7 — Web dashboard: Design

## Flow of control and data

```
browser ──same origin──▶ dev proxy  /api/query → :8082   /api/control → :8085   /api/alerts → :8086
   │                      (prefix stripped; Last-Event-ID and Authorization pass through)
   ▼
App ── hash route ── tenant (derived: selected ∩ tenant list, else first) ── AuthStatus
   │
   ├─ MetricsView key=tenant ──▶ useMetricSeries
   │      useEventSource(url) ─▶ openSseConnection ─▶ streamSse(fetch) ─▶ GET /v1/stream/metrics
   │            │ message                                     ▲ Last-Event-ID, Authorization (fresh per attempt)
   │            ▼
   │      parseMetricEvent ─ bad? drop ─▶ batcher (1 flush / 250 ms) ─▶ seriesReducer (bound 120/series) ─▶ LiveChart
   │
   ├─ AlertFeed key=tenant ──▶ useAlerts
   │      mount and every RE-open:  GET /v1/alerts  ─▶ snapshotStarted … snapshotLoaded
   │      stream message ─ parseAlert ─▶ alertsReducer (live alerts buffered while a snapshot is in flight)
   │
   └─ RulesAdmin key=tenant ──▶ useRules ─▶ GET /v1/admin/rules?tenantId=   (table: version shown)
          ├─ View / Edit ─▶ useRule ─▶ GET /v1/admin/rules/{id} ─▶ RuleForm(edit) ─▶ PUT … ─▶ ok → reload list | Problem
          ├─ New rule ─▶ RuleForm(create) ─▶ validate ─▶ POST /v1/admin/rules ─▶ ok → reload list | Problem
          └─ Delete ─▶ confirmation group (focus: Keep rule; Esc cancels) ─ confirmed ─▶ DELETE … ─▶ reload list | Problem
   (all through the generated client; every outcome is an ApiResult: value | Problem)

Reconnect loop (connection.ts), one instance per URL:
   open ─ end/err ─▶ retrying ─ sleep(backoff(attempt)) ─▶ fetch(Last-Event-ID = last id) ─ 200 ─▶ open (attempt = 0)
                         └─ 4xx (not 408/429) or wrong content-type ─▶ closed (no retry)
   close() (unmount / URL change) aborts the fetch and the sleep.
```

## Keys

| Thing | Key | Why |
|---|---|---|
| Series | `[metric, titleId, region]` as JSON | ids may contain `:`; a joined string could collide |
| Point within a series | `windowStart` (epoch ms) | a re-reported window replaces its point |
| Alert | `alertId` | stable for the whole incident (alerts-v1.yaml) |
| Resume position | last non-empty SSE `id` of this connection | one connection = one URL = one tenant |
| View remount | `key={tenantId}` | resets series, alerts, and the stream without reset code |

## Why these shapes

- **`fetch` reader, not `EventSource`**: ADR-0018. A new `EventSource` after a failure has no
  `Last-Event-ID`; a header-resumed stream is a REQ-WEB-03 requirement.
- **Last event id is a getter, not state**: a render per event would defeat the batching.
- **Snapshot plus buffer for alerts**: the stream can deliver a change while the list request is in
  flight; the buffer is replayed over the snapshot so the older snapshot never wins. The list holds
  active alerts only, so RESOLVED ones already seen on the stream are kept through a reload.
- **Re-read the list after every reconnect**: alert-stream ids are per instance and replay is a
  bounded buffer; the contract says to re-read when a gap cannot be ruled out.
- **Live views stay mounted** (`hidden`), so a chart's window survives a visit to another view.
- **Unbounded: the number of series.** Points per series are bounded; the number of series is
  whatever the tenant's titles x regions are. A guard is not built.

## Failure modes

| Failure | Behaviour | Test that proves it |
|---|---|---|
| Stream drops repeatedly | waits 1 s, 2 s, 4 s … | `connection.test.ts` waits 1 s, 2 s, then 4 s after consecutive failures |
| Many failures | wait never exceeds the cap | `backoff.test.ts` never exceeds the cap, however many failures; `connection.test.ts` never waits longer than the cap |
| Clients fail together | waits are randomised within half to all of the ceiling | `backoff.test.ts` keeps every delay between half and all of the ceiling; `connection.test.ts` adds jitter |
| Server healthy again | schedule restarts | `connection.test.ts` starts the schedule over after a connection opens |
| Reconnect we trigger | carries the last event id | `connection.test.ts` carries the last event id into a reconnect…; keeps the id through failed attempts in between; Playwright `reconnects after the finite stream ends and sends Last-Event-ID itself` |
| Token renewed | fresh header per attempt | `connection.test.ts` builds fresh headers for every attempt |
| Viewer leaves | connection and timers closed | `useEventSource.test.tsx` closes the connection and cancels timers on unmount; aborts an open stream on unmount; `MetricsView.test.tsx` closes the stream when the view goes away |
| Tenant switch | fresh stream, no old id, empty charts | `useEventSource.test.tsx` starts fresh, without the old resume id…; `MetricsView.test.tsx` switching tenant clears the charts…; Playwright `switching tenant opens a new stream…` |
| 400/401/403/404 | stops, error shown | `transport.test.ts` treats %i as final; `connection.test.ts` stops for good on a client error; `MetricsView.test.tsx` shows why the stream stopped… |
| 408/429/5xx | retried | `transport.test.ts` treats %i as worth retrying; `connection.test.ts` retries a server error |
| Proxy returns the HTML page with 200 | stops, not parsed as events | `transport.test.ts` rejects a 200 that is not an event stream… |
| Connection stalls | dead after 45 s of silence; heartbeat counts as activity | `transport.test.ts` gives up on a connection that goes quiet…; counts a heartbeat as activity…; `connection.test.ts` treats a stalled connection as dead and retries |
| Multi-byte character split across chunks | decoded intact | `transport.test.ts` decodes a multi-byte character that is split… |
| Burst of events | one render per interval, none lost | `batcher.test.ts` collapses a burst into one flush…; `MetricsView.test.tsx` does not render per event…; keeps every update of a burst |
| Memory growth | at most 120 points per series | `seriesReducer.test.ts` keeps only the newest maxPoints per series…; bounds each series separately |
| Window re-reported / late | replaced / placed in order | `seriesReducer.test.ts` replaces the point when a window is re-reported…; places a late window in order… |
| Malformed or foreign event | ignored, stream continues | `parseMetricEvent.test.ts` rejects %s without throwing; `parseAlert.test.ts` rejects %s; `MetricsView.test.tsx` ignores a malformed event…; `AlertFeed.test.tsx` ignores a malformed alert event |
| Chart not perceivable | text equivalent and table | `LiveChart.test.tsx` gives the graphic a text equivalent…; offers a keyboard-operable table…; Playwright axe scans |
| Tenant list unavailable | configured tenants, said so | `useTenants.test.tsx` falls back to the configured tenants when the request fails; falls back when the server cannot be reached; falls back when the list is empty; Playwright `falls back to the configured tenant…` |
| Alert list fails | error and retry | `AlertFeed.test.tsx` shows the error and a retry button…; shows the error when the server cannot be reached; Playwright `shows the error when the list endpoint fails, and recovers on retry` |
| Live alert during list load | kept | `alertsReducer.test.ts` keeps a live change received while the snapshot was loading…; `AlertFeed.test.tsx` keeps a live change that lands while the list is still loading |
| Events missed during a drop | list re-read on reconnect | `AlertFeed.test.tsx` reloads the list after the stream reconnects… |
| Resolved alerts not in the list | kept through a reload | `alertsReducer.test.ts` keeps resolved alerts seen on the stream…; drops a firing alert the reload no longer lists… |
| States told apart | text and symbol | `AlertFeed.test.tsx` labels every state in words and with a symbol…; Playwright `shows firing, suppressed and resolved alerts…` |
| Suppressed root cause | shown | `AlertFeed.test.tsx` names the root cause of a suppressed alert…; falls back to the raw suppressor id…; says so when a suppressed alert reports no cause |
| Rule list fails | problem shown, retry | `RulesAdmin.test.tsx` shows the problem and offers a retry when the list fails, and recovers; Playwright `shows the problem and a retry when the rule list fails` |
| Rule opened but gone | server's 404 shown | `RulesAdmin.test.tsx` shows the problem when the rule it was asked to open no longer exists |
| Edit rejected (cycle 422, conflict 409) | server's message shown, edit kept, stored rule untouched | `RuleForm.test.tsx` shows the server's cycle rejection and keeps the edit; shows a conflict from a concurrent change…; `RulesAdmin.test.tsx` shows the server's cycle rejection while editing…; `rulesApi.test.ts` returns the server's cycle message untouched on a 422; Playwright `shows the server's 422 dependency-cycle message when saving an edit…` |
| Edit changes id or tenant | impossible: id read-only, PUT body has neither | `RuleForm.test.tsx` does not let the id be changed…; `rulesApi.test.ts` updates with a PUT that carries the editable fields and not the id or tenant |
| Delete without confirmation | cannot happen; nothing sent until confirmed | `RulesAdmin.test.tsx` asks for confirmation and sends nothing until it is given; does nothing when the confirmation is declined; can be declined with Escape; Playwright `asks for confirmation before deleting…`. Mutation check: Delete wired straight to the call turns 7 of these red |
| Delete refused / already gone | problem shown, list refreshed | `RulesAdmin.test.tsx` shows the server's refusal and keeps the rule listed; shows the problem and refreshes when the rule was already gone; Playwright `shows the server refusal when a delete fails and keeps the rule` |
| Contract constraint broken client-side | named, focus moved, nothing sent | `RuleForm.test.tsx` requires a category when the rule depends on others…; checks the rule id against the pattern the contract defines |
| Server lists per-field errors | each shown | `problem.test.ts` keeps the per-field messages the server lists in errors; `RuleForm.test.tsx` lists the per-field messages the server returns with a 400 |
| Rule rejected (problem+json) | title, detail, trace id shown; input kept | `submitRule.test.ts` returns the problem body of a rejected rule…; `problem.test.ts`; `RuleForm.test.tsx` shows a problem+json rejection in full…; Playwright `shows a problem+json validation error and keeps the input` |
| Control plane unreachable | says so | `submitRule.test.ts` turns a network failure into a problem…; `RuleForm.test.tsx` says the server could not be reached… |
| Required field empty | named, focus moved, nothing sent | `RuleForm.test.tsx` asks for the missing required fields by name…; rejects a threshold that is not a number |
| Double submit | blocked while in flight | `RuleForm.test.tsx` cannot be submitted twice while a request is in flight |
| No token / sign-in off | no header, app works | `oidcAuth.test.ts` has no token before anyone signs in…; `submitRule.test.ts` sends no Authorization header when there is no token; `AuthStatus.test.tsx` renders nothing when sign-in is switched off |
| Token present | attached to requests and reconnects | `submitRule.test.ts` attaches the access token…; `MetricsView.test.tsx` attaches the access token to the stream request; `AlertFeed.test.tsx` sends the access token with the list request |
| Expired token | not sent | `oidcAuth.test.ts` does not hand out an expired token |
| Sign-in callback fails | shown, address cleaned | `oidcAuth.test.ts` records a failed callback…; `AuthStatus.test.tsx` reports a failed sign-in… |
| Code/state left in the address bar | removed | `oidcAuth.test.ts` completes the redirect… then cleans the address bar |
| Dev proxy path mapping | prefix stripped, Last-Event-ID forwarded, unbuffered | NOT automated. Checked once by hand against a stub on :8082 (see README); the e2e suite mocks at the browser and bypasses the proxy |
| Realm client | imports; existing OIDC test unaffected | `KeycloakOidcIT` (does not exercise the `streampulse-web` login itself) |

## Packages

`lib/sse` (parser, transport, connection, hook) · `lib` (batcher) · `api` (generated types, client,
problem) · `auth` · `features/{metrics,alerts,rules,tenants}` · `components` · `services.ts` (the
one context) · `config.ts` · `route.ts`.

## Decisions

- ADR-0018: fetch-based stream reader, dev proxy, no state library, SVG charts, committed generated types.
- ADR-0011 (Keycloak for local OIDC) stands; the realm gains one public client.
