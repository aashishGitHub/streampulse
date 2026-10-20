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

**Answer:** The browser's `EventSource` cannot set request headers at all, so it cannot send an `Authorization` header. It does resend `Last-Event-ID`, but only on its own internal reconnects. If my code builds a new `EventSource` after a failure, the new object starts with no id and silently loses the resume position. Both servers document header-based resume, so I read the stream with `fetch` and a stream reader instead. Then every attempt sets the id and a fresh token header, and the backoff and give-up rules are mine. The cost is roughly 150 lines: a parser, a reader and a reconnect loop, each testable on its own.

**Example:** `web/src/lib/sse/transport.ts:70-73`
```ts
const headers: Record<string, string> = { Accept: 'text/event-stream', ...options.headers };
if (options.lastEventId) headers['Last-Event-ID'] = options.lastEventId;

const response = await options.fetch(url, { headers, signal: request.signal, cache: 'no-store' });
```
Point at the second line: the resume id is a header I set on every attempt, which is exactly what `EventSource` will not let me do.

**Code:** `web/src/lib/sse/transport.ts:47-50` (the comment that states the reason), `docs/adr/0018-react-dashboard-fetch-sse-and-dev-proxy.md:10-31` (the option table)

### B2. Why is there a Vite dev proxy?

**Answer:** None of the three backends sets CORS headers, so a browser page on one origin cannot call them on three other ports. In development the browser talks to one origin, and the Vite dev server forwards `/api/query`, `/api/control` and `/api/alerts` to ports 8082, 8085 and 8086 with the prefix stripped. I chose that over adding CORS configuration to three services, because it changes none of them. It is a development convenience only. A real deployment needs a reverse proxy with the same mapping, and I have not built or tested one.

**Example:** `web/vite.config.ts:7-19`
```ts
const proxy = (prefix: string, target: string) => ({
  [prefix]: { target, changeOrigin: true, rewrite: stripPrefix(prefix) },
});
...
    proxy: {
      ...proxy('/api/query', 'http://localhost:8082'),
      ...proxy('/api/control', 'http://localhost:8085'),
      ...proxy('/api/alerts', 'http://localhost:8086'),
```
The `rewrite` is what strips the prefix, so the services see their own paths.

**Code:** `web/vite.config.ts:6-20` (the proxy), `web/README.md:38-40` (the same caveat in writing)

### B3. Why no state library?

**Answer:** State here is local to a feature and flows one way: the stream feeds a reducer, and the reducer feeds the render. Only two things are shared across features, the services bundle and the selected tenant, and neither changes often. A store library would add a concept without removing any code. The reducers are plain functions, so I test them without React. I would revisit the decision if a third cross-cutting piece of state appeared.

**Example:** `web/src/features/metrics/useMetricSeries.ts:17-21`
```ts
// Data flow: stream message -> parse -> batcher (one flush per interval) -> reducer -> render.
// Callers remount this with `key={tenantId}`, which is what resets the series and the stream.
export function useMetricSeries(tenantId: string) {
  const { fetch, config, auth } = useServices();
  const [state, dispatch] = useReducer(seriesReducer, config.chart.maxPoints, createSeriesState);
```
A local `useReducer` over a pure function is the whole state story for the chart.

**Code:** `web/src/features/metrics/seriesReducer.ts:25-48` (the pure reducer), `docs/adr/0018-react-dashboard-fetch-sse-and-dev-proxy.md:54-59` (the decision)

### B4. What does PKCE add to the authorisation-code flow?

**Answer:** A single-page app cannot keep a client secret, because everything it ships is readable by the user. PKCE (Proof Key for Code Exchange) binds the authorisation code to a one-time verifier that the app created, so an intercepted code is useless without that verifier. In this dashboard the flow runs through `oidc-client-ts` against Keycloak. The code comment says the library adds the S256 challenge unless `disablePKCE` is set, and I did not set it. Sign-in is off by default, and I have never exercised this flow against a live Keycloak, so I describe it as built and unit-tested with a fake, not as proven.

**Example:** `web/src/auth/oidcAuth.ts:85-97`
```ts
// Authorization-code flow; oidc-client-ts adds the S256 PKCE challenge unless disablePKCE is set.
// Tokens live in sessionStorage so they die with the tab.
export function createBrowserAuth(config: AppConfig['auth']): AuthService {
  if (!config.enabled) return createDisabledAuth();
  const manager = new UserManager({
    authority: config.authority,
    client_id: config.clientId,
    redirect_uri: config.redirectUri,
    post_logout_redirect_uri: config.postLogoutRedirectUri,
    response_type: 'code',
    scope: 'openid profile',
    userStore: new WebStorageStateStore({ store: window.sessionStorage }),
  });
```
`response_type: 'code'` is the authorisation-code flow, and there is no client secret anywhere in the options.

**Code:** `web/src/auth/oidcAuth.ts:85-102` (the browser wiring), `web/README.md:52-53` (the realm client)

### B5. Why are the generated API types committed?

**Answer:** The types are generated from the OpenAPI files, and I commit the generated files so that a contract change shows up in code review as a diff. The build then does not need access to the `api/` folder, and an `api:check` command fails when the committed files are stale. The limit is that stream payloads are not described in the contracts as typed schemas, so the types do not cover them. I validate those at runtime instead of trusting them, and a bad event is dropped rather than ending the stream.

**Example:** `web/package.json:17-21`
```json
"api:generate": "node scripts/generate-api.mjs",
...
"api:check": "node scripts/generate-api.mjs --check"
```
The `--check` flag is what turns "committed types" into something CI can fail on.

**Code:** `web/README.md:71-76` (the policy), `web/src/features/metrics/parseMetricEvent.ts:5-23` (runtime validation of stream payloads)

---

## Mid

### M1. Describe the reconnect policy and why each part exists.

**Answer:** The wait grows exponentially from 1 second to a 30 second cap, with equal jitter: half of the ceiling is guaranteed, so a reconnect storm cannot collapse to nearly nothing, and the other half is random, so clients that failed together do not come back together. The schedule restarts after a successful open. A 4xx other than 408 or 429 stops retrying, because the same request would be refused again. A connection that is silent for 45 seconds, which is three 15 second heartbeats, is treated as dead. All of these numbers are targets that I chose to be explainable, and I have not measured any of them against a real outage.

**Example:** `web/src/lib/sse/backoff.ts:8-13`
```ts
// "Equal jitter": half the ceiling is guaranteed so a reconnect storm cannot collapse to ~0 ms,
// the other half is random so clients that failed together do not return together.
export function backoffDelay(attempt: number, config: BackoffConfig, random: () => number): number {
  const ceiling = Math.min(config.capMs, config.baseMs * 2 ** attempt);
  return ceiling / 2 + random() * (ceiling / 2);
}
```
The last line is equal jitter, and the `random` parameter is what lets the tests pin it.

**Code:** `web/src/lib/sse/backoff.ts:6` (`DEFAULT_BACKOFF`: 1,000 and 30,000 ms), `web/src/lib/sse/transport.ts:27-34` (which errors are fatal), `web/src/lib/sse/connection.ts:23-24` (the 45 s idle default)

**If they push:** "Why not full jitter?" Full jitter can draw a delay near zero, so a crowd of clients could hit the server almost immediately; equal jitter keeps a floor. I chose equal jitter for that floor and have not compared the two under load.

### M2. How does a tenant switch avoid sending the wrong resume id?

**Answer:** The resume id lives inside the connection object, and a connection is bound to one URL for its whole life. A different tenant means a different URL, therefore a new connection that starts with no id, so an id can never cross tenants. The React side reinforces this: the feed component is mounted with the tenant id as its `key`, so switching tenant remounts it and resets both the series and the stream. A hook test checks that the second request has no `Last-Event-ID` header, and an end-to-end test checks the same thing against the stubbed stream.

**Example:** `web/src/lib/sse/connection.ts:40-45`
```ts
// Owns the reconnect loop and the resume position. One instance is one stream URL for its whole
// life: the last event id never leaks to a different URL because a new URL is a new connection.
export function openSseConnection(options: SseConnectionOptions): SseConnection {
  const { backoff = DEFAULT_BACKOFF, idleTimeoutMs = DEFAULT_IDLE_TIMEOUT_MS, random = Math.random } = options;
  const lifetime = new AbortController();
  let lastEventId: string | null = null;
```
`lastEventId` is a local of the function, so it cannot outlive the URL it was created for.

**Code:** `web/src/lib/sse/connection.ts:40-45` (the owner of the id), `web/src/features/metrics/MetricsView.tsx:35` (the `key={tenantId}` remount), `web/src/lib/sse/useEventSource.test.tsx:106-121` (the hook test), `web/e2e/dashboard.spec.ts:42-51` (the end-to-end test)

**If they push:** "What stops a stale response from an old tenant?" Remounting cancels the old connection and timers on unmount; the hook tests assert that unmount aborts an open stream and leaves no timers. I did not test a response that arrives after the switch beyond that.

### M3. Why batch updates before rendering?

**Answer:** Metric events can arrive in bursts, and rendering a chart on every event would waste work. So events go into a batcher that flushes at most once per interval, with a target of 250 ms, and one flush is one reducer dispatch. That means a burst of fifty events causes one render, not fifty. When nothing is arriving there is no timer at all. I checked the test can fail: when I made the batcher emit per event, its test turned red. The 250 ms is a target and I have not measured render cost.

**Example:** `web/src/lib/batcher.ts:7-22`
```ts
export function createBatcher<T>(intervalMs: number, onFlush: (items: T[]) => void): Batcher<T> {
  let pending: T[] = [];
  let timer: ReturnType<typeof setTimeout> | undefined;

  const flush = () => {
    timer = undefined;
    const items = pending;
    pending = [];
    onFlush(items);
  };

  return {
    push(item) {
      pending.push(item);
      timer ??= setTimeout(flush, intervalMs);
    },
```
`timer ??=` starts the interval on the first item only, so later items in the burst join the same flush.

**Code:** `web/src/lib/batcher.ts:7-29` (the batcher), `web/src/lib/batcher.test.ts:17-26` (a burst of 100 items gives one flush), `web/src/features/metrics/useMetricSeries.ts:25-32` (where it is wired to the reducer)

### M4. How are chart updates made accessible?

**Answer:** The chart is hand-rolled SVG. The `svg` has `role="img"` with a `title` and a `desc` that state the latest value, the lowest, the highest and the point count in text, and a collapsible data table with real table headers gives the same numbers to keyboard and screen-reader users. An axe accessibility scan runs in the end-to-end tests on the list, panel, form and confirmation, and one test navigates every view by keyboard alone. The trade-off is that there is no zoom, no tooltip and no axes beyond a baseline. A charting library would be the largest dependency and would still need wrapping for accessibility.

**Example:** `web/src/features/metrics/LiveChart.tsx:34-36`
```tsx
<svg viewBox={`0 0 ${BOX.width} ${BOX.height}`} role="img" aria-labelledby={`${id}-title`} aria-describedby={`${id}-desc`}>
  <title id={`${id}-title`}>{`${label}, ${where}`}</title>
  <desc id={`${id}-desc`}>{description}</desc>
```
The text equivalent is built from the same points that are drawn, so the two cannot disagree.

**Code:** `web/src/features/metrics/LiveChart.tsx:34-60` (the chart and its table), `web/src/features/metrics/chartGeometry.ts:20-37` (pure geometry), `web/e2e/dashboard.spec.ts:102-112` (the axe scan on the rule list), `web/e2e/dashboard.spec.ts:265-273` (the keyboard test)

**If they push:** "Is the axe scan proof of accessibility?" No. It catches rule-based violations only; I did not test with a real screen reader.

### M5. How do errors from the server reach the user?

**Answer:** Every call goes through one function that returns a result: a value, or a problem object read from RFC 7807 `problem+json`, including its list of field errors. A network failure is turned into a problem too, so components never see a thrown HTTP failure. When the server rejects a rule with a 422 dependency-cycle message, the screen shows it and keeps what the user typed. Deleting a rule goes through an in-page confirmation instead of `window.confirm`, which makes it accessible and testable. I checked that the tests notice: a mutation that skipped the confirmation turned seven tests red.

**Example:** `web/src/api/result.ts:11-19`
```ts
// One place that turns an openapi-fetch outcome, or a thrown network failure, into a value the UI can show.
export async function toResult<T>(call: () => Promise<FetchOutcome<T>>): Promise<ApiResult<T | undefined>> {
  try {
    const { data, error, response } = await call();
    return response.ok ? { ok: true, value: data } : { ok: false, problem: problemFrom(error, response) };
  } catch (cause) {
    return { ok: false, problem: networkProblem(cause) };
  }
}
```
Both failure paths, a bad status and a thrown network error, end as the same `ok: false` shape.

**Code:** `web/src/features/rules/submitRule.ts:9-12` (a caller), `web/src/api/problem.ts:14-28` (reading problem+json defensively), `web/src/features/rules/RulesAdmin.tsx:74-86` (the in-page confirmation), `web/src/features/rules/RulesAdmin.test.tsx:132-140` (nothing is sent until confirmed)

### M6. How is the alert stream different from the metric stream?

**Answer:** The alert stream ends after a bounded lifetime, and its ids are per server instance, so a resume id from one instance proves nothing about a gap on another. For that reason the alerts hook reads the list of active alerts again every time the stream reopens after the first open, and merges it into the same reducer. That list shows only active alerts, so RESOLVED alerts come only from the stream. The consequence is that a RESOLVED event missed during a gap is not recovered from the list. These stream facts come from the contracts and the ADR, and I did not check them against a running alert engine.

**Example:** `web/src/features/alerts/useAlerts.ts:56-63`
```ts
  const onStateChange = useCallback(
    (next: string) => {
      if (next !== 'open') return;
      opens.current += 1;
      if (opens.current > 1) void reload();
    },
    [reload],
  );
```
The first open does not reload, because the initial snapshot is already in flight; every later open does.

**Code:** `web/src/features/alerts/useAlerts.ts:9-11` (the comment on why), `docs/adr/0018-react-dashboard-fetch-sse-and-dev-proxy.md:41-43` (the server facts relied on)

---

## Senior

### S1. Every test stubs the network. What can that not tell you, and how would you close the gap?

**Answer:** Stubs cannot show contract drift between the generated types and a running service, CORS or proxy behaviour, real SSE buffering by an intermediary, token expiry in a real session, or the real Keycloak login. The dev proxy was checked once by hand against a throwaway stub server, and that check is not automated. To close the gap I would add a contract test of the generated types against a running service, one smoke run per release against the real stack, and a real-login test against the imported realm. I say this plainly: the dashboard is a verified component with an unverified integration.

**Example:** `web/e2e/mockBackends.ts:49-53`
```ts
// Every /api call is answered here; anything not listed is aborted, so no test can reach a backend by accident.
export async function mockBackends(page: Page, overrides: Overrides = {}): Promise<Seen> {
  const seen: Seen = { metricStreamLastEventIds: [], metricStreamUrls: [], ruleBodies: [], ruleCalls: [], authorization: [] };

  await page.route('**/api/**', (route) => route.abort('connectionrefused'));
```
This is the honest part: the end-to-end suite cannot reach a backend even by accident, so it proves the client against stubs only.

**Code:** `web/README.md:6-19` (what was and was not run), `web/e2e/mockBackends.ts:49-53` (every request is stubbed or aborted)

**If they push:** "Did the end-to-end suite actually run?" Yes: 17 of 17 Playwright tests passed in this session after installing the bundled browser. The original session had used the installed Chrome. All of them are against stubs.

### S2. Two people edit the same alert rule. What happens?

**Answer:** The contract's update type has no version field, so the client cannot send the version it read. The only protection against a concurrent edit is the server's own 409 answer, and that is a documented gap, not a guarantee I verified. The UI shows the rule's version when it lists or opens a rule and in the confirmation after a save, but it does not send it back. The better design is a version in the body or an `If-Match` header, so the server can reject a stale write explicitly. That needs a contract change and I did not make it.

**Example:** `web/src/features/rules/rulesApi.ts:21-25`
```ts
export async function updateRule(client: ControlClient, ruleId: string, body: AlertRuleUpdate): Promise<ApiResult<AlertRule>> {
  const result = await toResult(() => client.PUT('/v1/admin/rules/{ruleId}', { params: { path: { ruleId } }, body }));
  if (!result.ok) return result;
  return result.value ? { ok: true, value: result.value } : emptyBody('rule');
}
```
The `PUT` body is `AlertRuleUpdate`, which carries no version, so a stale write is indistinguishable from a fresh one on the client side.

**Code:** `web/src/features/rules/rulesApi.ts:21-25` (the update call), `web/README.md:80` (the documented gap)

### S3. Where would you store the token, and what is the XSS exposure?

**Answer:** In this code the token store is `sessionStorage`: `createBrowserAuth` builds the `UserManager` with `userStore: new WebStorageStateStore({ store: window.sessionStorage })`, and the comment says tokens live there so they die with the tab. The exposure is that any script running on the page, so any cross-site-scripting bug or third-party script, can read `sessionStorage`. The upside over `localStorage` is that it is per tab and cleared when the tab closes. A memory-only token would be safer but is lost on reload. An HTTP-only cookie keeps the token away from scripts, but it needs a backend serving the front end. I would mitigate with a strict content security policy, no third-party scripts and short-lived tokens. Honest status: sign-in is off by default and has never run against the real Keycloak, and no service validates tokens on these endpoints yet. `getAccessToken` returns null when the user is missing or expired, and nothing in that file configures refresh or silent renew explicitly; I have not verified what the library does by default. There is also no re-login prompt after expiry.

**Example:** `web/src/auth/oidcAuth.ts:76-97`
```ts
    async getAccessToken() {
      const user = await manager.getUser();
      return user && !user.expired ? user.access_token : null;
    },
...
// Authorization-code flow; oidc-client-ts adds the S256 PKCE challenge unless disablePKCE is set.
// Tokens live in sessionStorage so they die with the tab.
export function createBrowserAuth(config: AppConfig['auth']): AuthService {
  if (!config.enabled) return createDisabledAuth();
  const manager = new UserManager({
    authority: config.authority,
    client_id: config.clientId,
    redirect_uri: config.redirectUri,
    post_logout_redirect_uri: config.postLogoutRedirectUri,
    response_type: 'code',
    scope: 'openid profile',
    userStore: new WebStorageStateStore({ store: window.sessionStorage }),
  });
```
Two things to point at: the `userStore` line is the storage decision, and `getAccessToken` returns null for an expired user, so an expired token is never sent.

**Code:** `web/src/auth/oidcAuth.ts:87-97` (the `UserManager` options), `web/src/auth/oidcAuth.ts:76-79` (`getAccessToken`), `web/src/services.ts:21-25` (how the token becomes a header), `web/README.md:19` (no service validates tokens yet), `web/README.md:84` (no re-login prompt)

**If they push:** "Does the stream pick up a renewed token?" Headers are rebuilt on every attempt, and a connection test is named "builds fresh headers for every attempt, so a renewed token is used"; but nothing in this repo renews a token live, so that is tested with a fake only.

### S4. Custom SSE client versus a library or `EventSource`: when would you not do this?

**Answer:** I would own the client when I need request headers, a resume id I control on every attempt, and my own give-up rules, which is the case here. I would not do it when a plain `EventSource` meets the need: it is smaller, there is nothing of mine to maintain, and the browser handles reconnect. The ADR compares three options: native `EventSource`, native plus the id in the query string, and `fetch` with my own parser. The middle one needs a server change that the contracts do not have. The cost I accepted is maintaining a small parser that follows the WHATWG field rules but does not implement the `retry:` field.

**Code:** `docs/adr/0018-react-dashboard-fetch-sse-and-dev-proxy.md:18-31` (the option table), `docs/adr/0018-react-dashboard-fetch-sse-and-dev-proxy.md:92-96` (what is and is not implemented), `web/src/lib/sse/parser.ts:18` (the incremental line split)

**If they push:** "Why not an existing library?" I did not survey the libraries, so I cannot say which would have fit. The reason I wrote it was the ADR's three-way comparison, not a library comparison.

### S5. What happens to a long-lived stream behind a proxy or load balancer?

**Answer:** Buffering proxies can hold events back, and idle timeouts at a load balancer can cut a quiet stream. That is why the server sends comment heartbeats every 15 seconds and the client has an idle watchdog: any bytes, including a heartbeat, re-arm the timer, and 45 seconds of silence aborts the request so the reconnect loop takes over. The client also refuses a 200 response that is not `text/event-stream`, which is what a proxy falling back to an HTML page would produce. The dev proxy was checked by hand that events are not buffered. A production reverse proxy must be configured to do the same, and that has not been tested.

**Example:** `web/src/lib/sse/transport.ts:57-66`
```ts
  // Also bounds the wait for response headers, so a hung connect is not a silent hang.
  let timedOut = false;
  let idleTimer: ReturnType<typeof setTimeout> | undefined;
  const armIdleTimer = () => {
    clearTimeout(idleTimer);
    idleTimer = setTimeout(() => {
      timedOut = true;
      request.abort();
    }, idleTimeoutMs);
  };
```
The timer is armed before the request is sent and again after every chunk, so it covers both a hung connect and a stalled stream.

**Code:** `web/src/lib/sse/transport.ts:57-66` (the watchdog), `web/src/lib/sse/transport.ts:75-76` (content-type check), `web/src/lib/sse/transport.test.ts:97-123` (silence is cut, a heartbeat keeps it alive)

### S6. How did you test the reconnect logic without time or a network?

**Answer:** The pure pieces, which are backoff, parser, batcher, reducers and geometry, are tested in a Node environment, which proves they need no DOM. The reconnect loop takes its randomness, timers and fetch as inputs, so a test uses a scripted fake `fetch`, fake timers and a `random` that always returns the top of the range. With those it asserts the exact waits of 1, 2 and 4 seconds, down to 999 versus 1,000 ms. I also ran mutation checks: breaking the backoff cap, breaking the jitter, and making the batcher emit per event each turned a named test red, and I restored each. The limit is that all of this proves the logic against a fake network, not a real one.

**Example:** `web/src/lib/sse/connection.test.ts:35-47`
```ts
  it('waits 1 s, 2 s, then 4 s after consecutive failures', async () => {
    const fake = createFakeSseFetch();
    for (let i = 0; i < 4; i++) fake.enqueueNetworkError();
    connect(fake);

    await flush();
    expect(fake.requests).toHaveLength(1);

    await vi.advanceTimersByTimeAsync(999);
    expect(fake.requests).toHaveLength(1);
    await vi.advanceTimersByTimeAsync(1);
    expect(fake.requests).toHaveLength(2);
```
Advancing the clock by 999 ms and then 1 ms pins the boundary, with no real waiting.

**Code:** `web/src/lib/sse/connection.test.ts:9-10` (the small backoff and the fixed `random`), `web/src/lib/sse/backoff.test.ts:10-13` (the doubling test), `web/src/lib/sse/connection.ts:43` (`random` and timers injectable)

---

## Say this honestly

- Never run against real backends; Keycloak login never run live; dev proxy not automated.
- The Playwright run in the original session used installed Chrome, not Playwright's bundled browser; I
  installed and ran the bundled one here and all 17 passed.
- No chart history backfill; series count is not bounded (points per series are, at 120).
- No re-login prompt after token expiry. All the timings above are targets.

## Stories and numbers

[Numbers](../06-numbers.md): dashboard test counts.
