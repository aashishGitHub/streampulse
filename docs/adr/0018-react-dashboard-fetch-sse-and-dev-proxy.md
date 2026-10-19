# ADR-0018 — React dashboard: a fetch-based event-stream reader, a dev proxy, and no state library

**Status:** accepted · **Date:** 2026-10-13 · **Phase:** 7 · **Requirements:** REQ-WEB-01…REQ-WEB-42

## Context

The dashboard (`web/`) is the first consumer of two live streams (query API metrics, alert engine
alerts) and of three services that do not configure CORS. Five decisions were not obvious.

## Decision 1 — read the event stream with `fetch`, not `EventSource`

`EventSource` resends `Last-Event-ID` automatically, but **only on its own internal reconnects**. It
cannot set request headers at all (so no `Authorization`), and when application code creates a *new*
`EventSource` after a failure the new object starts with no id. A hook that "reconnects with
backoff" by constructing a new `EventSource` therefore silently loses the resume position, and both
servers document header-based resume (`query-v1.yaml`, `alerts-v1.yaml`).

Options:

| | Native `EventSource` | `EventSource` + id in the query string | `fetch` + our own parser (chosen) |
|---|---|---|---|
| Resume on browser-internal reconnect | yes | yes | n/a, we own every reconnect |
| Resume on reconnect we trigger | **no** | needs server support the contracts do not have | yes: header set per attempt |
| `Authorization` header | no | no | yes, fresh per attempt |
| Backoff control, give up on 4xx, idle watchdog | none (the browser decides; it stops on some errors) | none | full |
| Cost | none | server change | ~150 lines: a parser, a reader, a loop |

Chosen: `fetch` with a `ReadableStream` reader. Layers, each testable alone:
`parser.ts` (pure, incremental line parser per the WHATWG field rules) →
`transport.ts` (one HTTP attempt: headers, status and content-type checks, idle watchdog) →
`connection.ts` (reconnect loop and the resume id; no React) → `useEventSource.ts` (React wrapper).

The resume id lives in the connection object, which is bound to one URL. A new URL (another
tenant) is a new connection, so an id can never cross tenants.

Reconnect policy: exponential backoff from 1 s to a 30 s cap with "equal jitter" (half the ceiling
guaranteed, half random); the schedule restarts after a successful open; 4xx other than 408/429
stops retrying (the same request would fail again); a connection silent for 45 s (3 heartbeats of 15 s)
is treated as dead. Those numbers are targets, not measurements.

Server facts this design relies on, taken from the contracts: the alert stream ends after a bounded
lifetime and its ids are per-instance, so a reconnect also re-reads `GET /v1/alerts`; that endpoint
lists only active alerts, so RESOLVED ones come from the stream only.

## Decision 2 — a Vite dev-server proxy instead of CORS

No backend configures CORS. In development `/api/query`, `/api/control`, `/api/alerts` are proxied
to 8082, 8085, 8086 with the prefix stripped, so the browser sees one origin. Chosen over adding CORS
config to three services because it changes none of them and mirrors what a gateway does in
production. Consequence: **this is a development convenience**; a deployment needs a reverse proxy
doing the same mapping. (`control-plane-v1.yaml` lists 8085, matching the proxy.) Keycloak is the exception: the
browser calls it directly, which is why the realm client sets `webOrigins`.

## Decision 3 — React context and hooks; no state library

State is local to a feature (series per tenant, alerts per tenant) and flows one way: stream →
reducer → render. Two shared things exist (the services bundle, the selected tenant) and neither
changes often. A store library would add a concept without removing code. Reducers are plain
functions and are unit-tested without React. Revisit if a third cross-cutting piece of state appears.

## Decision 4 — hand-rolled SVG charts

Two metrics, line charts, a bounded window. A charting library would be the largest dependency in
the app and its accessibility story (text alternatives, keyboard) would still need wrapping. The
SVG is ~60 lines, its geometry is a pure tested function, each chart carries a `<title>`/`<desc>`
text equivalent and a keyboard-operable data table. Cost: no zoom, tooltips or axes beyond a
baseline; add a library if those become requirements.

Rendering is throttled: events go to a batcher that flushes once per interval (target 250 ms), and
a flush is one reducer dispatch, so fifty events cause one render.

## Decision 5 — a generated client for the control plane; generated files are committed

`openapi-typescript` generates types from `api/openapi/*.yaml`; `openapi-fetch` is the typed
client over them. The generated `.d.ts` files are **committed** (`src/api/generated/`): a contract
change then shows up in review as a diff, the build needs no access to `../api`, and
`npm run api:check` fails when the committed files are stale. Regenerate with `npm run api:generate`.
Every call returns an `ApiResult` (a value, or an RFC 7807 `Problem` including its `errors` list), so components never see a thrown HTTP failure. Rule deletion goes through an in-page confirmation, not `window.confirm`, so it is accessible and testable.
SSE payloads are not described by the contracts, so they are validated at runtime
(`parseMetricEvent`, `parseAlert`) instead of trusted.

## Sign-in is optional, and the backends do not check it

Keycloak authorization-code flow with PKCE (`oidc-client-ts`; it adds an S256 challenge unless
`disablePKCE` is set), public client `streampulse-web`. It is **off by default**
(`VITE_AUTH_ENABLED=true` turns it on). When on, the token is attached to every request including
stream reconnects. When off or not signed in, no header is sent and everything works. No service
validates tokens yet, so enabling it changes nothing a backend can observe.

## Consequences

- Resuming a stream is correct by construction, and visible in an end-to-end test that inspects the
  second request's header.
- We maintain a small SSE parser. It follows the WHATWG field rules (multi-line data, CR/LF/CRLF,
  comments, id persistence); it does not implement the `retry:` field.
- A trailing bare CR is held until the next chunk (it may be half of a CRLF). Both servers use LF.
- Nothing here was run against the real services. See `web/README.md`.
