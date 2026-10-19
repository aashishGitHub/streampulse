# StreamPulse web dashboard (Phase 7)

React 19 + TypeScript (strict) + Vite. Live metric charts, a live alert feed, and alert-rule
administration. Spec: `specs/07-web/`. Decisions: `docs/adr/0018-react-dashboard-fetch-sse-and-dev-proxy.md`.

## What was and was not run

**Every test in this directory runs against stubs. Nothing here has been exercised against the real
query-api, control-plane, alert-engine or Keycloak.**

- Unit and component tests (Vitest): a scripted fake `fetch` stands in for every service.
- End-to-end tests (Playwright): every `/api/**` request is answered by `page.route`; anything not
  listed is aborted, so no test can reach a backend. The proxy is bypassed (the browser is intercepted
  before it).
- The dev proxy was checked once by hand against a throwaway stub server on :8082 (path rewrite,
  `Last-Event-ID` and `Authorization` forwarded, events not buffered). Not automated.
- The Keycloak login with the `streampulse-web` client was never run. `KeycloakOidcIT` only shows
  the edited realm still imports.
- Sign-in is optional and off by default; no service validates tokens yet.

## Install and run

```
cd web
npm install
npm run dev
```

Open http://localhost:5173 (port is fixed: the Keycloak client allows only that origin).
Start the backends separately; the dev server proxies to them:

| Browser path | Proxied to |
|---|---|
| `/api/query/*` | http://localhost:8082 (query-api) |
| `/api/control/*` | http://localhost:8085 (control-plane) |
| `/api/alerts/*` | http://localhost:8086 (alert-engine) |

The prefix is stripped (`/api/query/v1/stream/metrics` becomes `/v1/stream/metrics`). The services
set no CORS headers, so this proxy is what makes the browser calls work in development. A deployment
needs a reverse proxy with the same mapping. (`control-plane-v1.yaml` now lists 8085.)

### Configuration (Vite env, all optional)

| Variable | Default |
|---|---|
| `VITE_QUERY_BASE_URL`, `VITE_CONTROL_BASE_URL`, `VITE_ALERTS_BASE_URL` | `/api/query`, `/api/control`, `/api/alerts` |
| `VITE_DEFAULT_TENANTS` | `streamflix` (used when the tenant list cannot be loaded) |
| `VITE_AUTH_ENABLED` | off; only the exact value `true` enables sign-in |
| `VITE_OIDC_AUTHORITY` | `http://localhost:8081/realms/streampulse` |
| `VITE_OIDC_CLIENT_ID` | `streampulse-web` |

Sign-in: authorization code + PKCE against Keycloak. The realm client `streampulse-web` is in
`docker/keycloak/realms/streampulse-realm.json`; Keycloak must be (re)started to import it.

## Test

```
npm run typecheck
npm run lint
npm test
npm run build
npm run e2e:install   # downloads Playwright's Chromium
npm run e2e           # builds, serves with `vite preview` on :4173, runs Playwright
npm run e2e:chrome    # same, using the installed Google Chrome (no download)
npm run api:check     # committed generated types still match api/openapi/*.yaml
```

Pure logic (backoff, SSE parser, batcher, reducers, geometry, config, route) is tested with
`// @vitest-environment node`, so it provably needs no DOM.

## Generated API types

`npm run api:generate` regenerates `src/api/generated/*.d.ts` from `../api/openapi/*.yaml`
(`openapi-typescript`). They are committed so contract changes show up in review.
`openapi-fetch` provides the typed client for the control plane. Stream payloads are not in the
contracts as typed schemas and are validated at runtime.

## Known gaps

- Rule edits cannot send the rule's `version`: `AlertRuleUpdate` in the contract has no field for it, so only the server's own conflict answer (409) protects against concurrent edits.
- The metric field of a rule is a free string in the contract (max 120 chars); the form offers the five metrics the query API knows.
- No history backfill for charts; a chart fills from the stream.
- The number of series is not bounded (points per series are: 120).
- No token re-login prompt after expiry.
- Numbers (250 ms refresh, 120 points, 30 s backoff cap, 45 s idle timeout) are targets, not measurements.
