# Phase 7 — React dashboard

**Status:** core complete, **tests only**. 258 unit and component tests and 17 Playwright end-to-end
tests pass, and every one runs against stubs. The dashboard has **never been run against the real
query-api, control-plane, alert-engine or Keycloak.** Sign-in is off by default.

**Why a Java-backend candidate should know it:** it is the client side of the SSE design (reconnect,
resume, backoff), and it shows how a front end is tested honestly when the backend is not there.

**One line to say first:** "A React 19 and TypeScript dashboard that reads the live streams with a
fetch-based event-stream client I wrote, because the browser's `EventSource` cannot carry auth headers or
keep its resume position across reconnects I trigger."

## Code map

| Thing | Where |
|---|---|
| Incremental SSE line parser | `web/src/lib/sse/parser.ts` |
| One HTTP attempt: headers, status, idle watchdog | `web/src/lib/sse/transport.ts` |
| Reconnect loop and resume id (no React) | `web/src/lib/sse/connection.ts` |
| Backoff schedule | `web/src/lib/sse/backoff.ts` |
| React wrapper | `web/src/lib/sse/useEventSource.ts` |
| Coalescing updates into one render per interval | `web/src/lib/batcher.ts` |
| Chart geometry (pure) | `web/src/features/metrics/chartGeometry.ts` |
| Rule admin: submit, list, form | `web/src/features/rules/submitRule.ts`, `rulesApi.ts`, `RulesAdmin.tsx` |
| Problem+json handling | `web/src/api/problem.ts`, `web/src/api/result.ts` |
| OIDC sign-in | `web/src/auth/oidcAuth.ts` |
| End-to-end tests with mocked backends | `web/e2e/dashboard.spec.ts` |
| Known limits | `web/README.md` |
| Decision | `docs/adr/0018-react-dashboard-fetch-sse-and-dev-proxy.md` |

## Flow

```
SSE bytes ─▶ parser (WHATWG field rules) ─▶ connection (reconnect, last id) ─▶ validate payload (parseMetricEvent)
          ─▶ batcher (one flush per 250 ms) ─▶ reducer (pure) ─▶ one React render ─▶ SVG chart + data table
tenant switch ─▶ NEW connection bound to the new URL, so a resume id can never cross tenants
```

---

## Beginner

### B1. Why read the event stream with `fetch` and not `EventSource`?

**A strong answer contains**
- `EventSource` cannot set request headers, so no `Authorization`. It resends `Last-Event-ID` only on its
  *own* internal reconnects; a new object created after a failure starts with no id and silently loses the
  resume position.
- With `fetch` and a stream reader, every reconnect sets the id and a fresh token header, and backoff and
  give-up rules are ours.
- Cost: about 150 lines (a parser, a reader, a loop), each layer testable alone.

**Open** `docs/adr/0018-react-dashboard-fetch-sse-and-dev-proxy.md`

### B2. Why is there a Vite dev proxy?

**A strong answer contains**
- None of the three backends sets CORS headers. In development the browser calls one origin and the proxy
  forwards `/api/query`, `/api/control`, `/api/alerts` to the right ports with the prefix stripped.
- It is a **development convenience**; a deployment needs a reverse proxy with the same mapping.

**Open** `web/vite.config.ts`

### B3. Why no state library?

**A strong answer contains**
- State is local to a feature and flows one way: stream, then reducer, then render. Only two things are
  shared (the services bundle and the selected tenant) and neither changes often.
- Reducers are plain functions tested without React. Revisit if a third cross-cutting piece appears.

**Open** `web/src/features/metrics/seriesReducer.ts`

### B4. What does PKCE add to the authorisation-code flow?

**A strong answer contains**
- A single-page app cannot keep a client secret, so the code is bound to a one-time verifier the app
  created; an intercepted code is useless without it.
- Here it runs through `oidc-client-ts` against Keycloak, off by default, and **never exercised live**.

**Open** `web/src/auth/oidcAuth.ts`

### B5. Why are the generated API types committed?

**A strong answer contains**
- A contract change then shows up in review as a diff, the build does not need the `api/` folder, and a
  check command fails when the committed files are stale.
- Stream payloads are not in the contracts as typed schemas, so they are validated at runtime instead of
  trusted.

**Open** `web/README.md`

---

## Mid

### M1. Describe the reconnect policy and why each part exists.

**A strong answer contains**
- Exponential backoff from 1 s to a 30 s cap with "equal jitter": half the ceiling guaranteed so a
  reconnect storm cannot collapse to nothing, half random so clients that failed together do not return
  together. The schedule restarts after a successful open.
- A 4xx other than 408 or 429 stops retrying, because the same request would fail again.
- A connection silent for 45 s (three 15 s heartbeats) is treated as dead.
- All of these numbers are targets.

**Open** `web/src/lib/sse/backoff.ts`

### M2. How does a tenant switch avoid sending the wrong resume id?

**A strong answer contains**
- The resume id lives in the connection object, which is bound to one URL. A new tenant means a new URL and
  therefore a new connection, so an id can never cross tenants. A test asserts a switch opens a stream with
  no resume id.

**Open** `web/src/lib/sse/connection.ts`

### M3. Why batch updates before rendering?

**A strong answer contains**
- Events go to a batcher that flushes once per interval (250 ms target); a flush is one reducer dispatch, so
  fifty events cause one render.
- Mutation check: making the batcher emit per event turned its test red.

**Open** `web/src/lib/batcher.ts`

### M4. How are chart updates made accessible?

**A strong answer contains**
- Hand-rolled SVG with a `<title>` and `<desc>` text equivalent and a keyboard-operable data table. An axe
  scan runs in the end-to-end tests on the list, panel, form and confirmation, and a test navigates every
  view by keyboard alone.
- Trade-off: no zoom, tooltips or axes beyond a baseline; a library would be the largest dependency and
  would still need wrapping for accessibility.

**Open** `web/src/features/metrics/chartGeometry.ts`

### M5. How do errors from the server reach the user?

**A strong answer contains**
- Every call returns a result: a value, or an RFC 7807 problem including its error list, so components never
  see a thrown HTTP failure. A 422 dependency-cycle message is shown and the input is kept.
- Deleting a rule goes through an in-page confirmation, not `window.confirm`, so it is accessible and
  testable. A mutation that skipped the confirmation turned seven tests red.

**Open** `web/src/features/rules/submitRule.ts`

### M6. How is the alert stream different from the metric stream?

**A strong answer contains**
- The alert stream ends after a bounded lifetime and its ids are per server instance, so a reconnect also
  re-reads the list of active alerts.
- That list shows only *active* alerts, so RESOLVED ones come from the stream only.

**Open** `docs/adr/0018-react-dashboard-fetch-sse-and-dev-proxy.md`

---

## Senior

### S1. Every test stubs the network. What can that not tell you, and how would you close the gap?

**A strong answer contains**
- It cannot show contract drift, CORS or proxy behaviour, real SSE buffering by an intermediary, token
  expiry in a real session, or the real Keycloak login. The proxy was checked once by hand and is not
  automated.
- Close it: a contract test of the generated types against a running service, one smoke run per release
  against the real stack, and a real-login test against the imported realm.
- Say it plainly in the interview: this is a verified component with an unverified integration.

**Open** `web/README.md`

### S2. Two people edit the same alert rule. What happens?

**A strong answer contains**
- The contract's update type has no version field, so the client cannot send the version it read. Only the
  server's own 409 protects against concurrent edits. That is a documented gap.
- Better: an `If-Match` or version in the body, so the server can reject a stale write explicitly.

**Open** `web/src/features/rules/rulesApi.ts`

### S3. Where would you store the token, and what is the XSS exposure?

**A strong answer contains**
- **I did not read where `oidc-client-ts` is configured to store it, so check `oidcAuth.ts` before
  answering.** The general trade-off: browser storage is readable by any script on the page (XSS), a
  memory-only token is lost on reload, and an HTTP-only cookie needs a backend for frontend.
- Mitigations to mention: a strict content security policy, no third-party scripts, short-lived tokens.
- Honest status: sign-in is off by default and no service validates tokens on these endpoints yet.

**Open** `web/src/auth/oidcAuth.ts`

### S4. Custom SSE client versus a library or `EventSource`: when would you not do this?

**A strong answer contains**
- Own it when you need headers, per-attempt resume and control of give-up rules (as here).
- Do not when a plain `EventSource` meets the need: it is smaller, and the browser handles reconnect.
- The ADR's option table is the right shape: native, native plus id in the query string (needs a server
  change the contracts do not have), or fetch plus own parser.

**Open** `docs/adr/0018-react-dashboard-fetch-sse-and-dev-proxy.md`

### S5. What happens to a long-lived stream behind a proxy or load balancer?

**A strong answer contains**
- Buffering proxies can hold events back, so the server sends heartbeats and the client has an idle
  watchdog; idle timeouts at a load balancer can cut quiet streams.
- The dev proxy was checked by hand that events are not buffered; a production reverse proxy must be set
  to the same, and that has not been tested.

**Open** `web/src/lib/sse/transport.ts`

### S6. How did you test the reconnect logic without time or a network?

**A strong answer contains**
- Pure pieces (backoff, parser, batcher, reducers, geometry) are tested in a Node environment, which
  proves they need no DOM. The connection takes its randomness and timers as inputs.
- Mutation checks: break the backoff cap, break the jitter, make the batcher emit per event; each named test
  went red and was restored.

**Open** `web/src/lib/sse/backoff.ts`

---

## Say this honestly

- Never run against real backends; Keycloak login never run live; dev proxy not automated.
- The Playwright run in the original session used installed Chrome, not Playwright's bundled browser; I
  installed and ran the bundled one here and all 17 passed.
- No chart history backfill; series count is not bounded (points per series are, at 120).
- No re-login prompt after token expiry. All the timings above are targets.

## Stories and numbers

[Numbers](../06-numbers.md): dashboard test counts.
