# Phase 7 — Web dashboard: Tasks

Order is the order of work. Each line: failing test first, then code. Tick only with passing
output in MILESTONES.md (the coordinator owns that file; ticks here mean the output exists in the
Phase 7 report, not that MILESTONES.md has it yet).

- [x] T7-01 spec, design, ADR-0018 written
- [x] T7-02 test `backoff.test.ts` fails → implement `backoff.ts` (pure schedule with jitter and cap)
- [x] T7-03 test `parser.test.ts` fails → implement `parser.ts` (incremental line parser)
- [x] T7-04 test `transport.test.ts` fails → implement `transport.ts` (fetch reader: headers, status, idle watchdog)
- [x] T7-05 test `connection.test.ts` fails → implement `connection.ts` (reconnect loop; carries the last id)
- [x] T7-06 test `useEventSource.test.tsx` fails → implement `useEventSource.ts`
- [x] T7-07 test `batcher.test.ts` fails → implement `batcher.ts` (one flush per interval)
- [x] T7-08 test `seriesReducer.test.ts` and `parseMetricEvent.test.ts` fail → implement `seriesReducer.ts`, `parseMetricEvent.ts`
- [x] T7-09 test `chartGeometry.test.ts` and `LiveChart.test.tsx` fail → implement `chartGeometry.ts`, `LiveChart.tsx`
- [x] T7-10 test `MetricsView.test.tsx` fails → implement `useMetricSeries.ts`, `MetricsView.tsx`, `TenantSwitcher.tsx`
- [x] T7-11 test `alertsReducer.test.ts`, `parseAlert.test.ts` fail → implement `alertsReducer.ts`, `parseAlert.ts`
- [x] T7-12 test `useAlerts.test.tsx`, `AlertFeed.test.tsx` fail → implement `useAlerts.ts`, `AlertFeed.tsx`
- [x] T7-13 test `problem.test.ts`, `submitRule.test.ts`, `RuleForm.test.tsx` fail → implement `problem.ts`, `submitRule.ts`, `RuleForm.tsx`
- [x] T7-14 test `oidcAuth.test.ts` fails → implement `oidcAuth.ts`, `AuthStatus.tsx`
- [x] T7-15 test `config.test.ts`, `route.test.ts` fail → implement `config.ts`, `route.ts`, `App.tsx`
- [x] T7-16 Playwright `dashboard.spec.ts` (all network mocked) fails → wire the app; add the accessibility scan
- [x] T7-17 mutation checks: break the backoff cap and the jitter, then make the batcher emit per event; each named test goes red; restore
- [x] T7-18 `streampulse-web` public client added to the realm; `KeycloakOidcIT` still green
- [x] T7-19 contract gains list/get/update/delete for rules: regenerate; test `problem.test.ts`, `rulesApi.test.ts`, `RuleForm.test.tsx`, `RulesAdmin.test.tsx` fail → implement `result.ts`, `rulesApi.ts`, `useRules.ts`, `RuleForm` edit mode, `RulesAdmin.tsx`
- [x] T7-20 Playwright: stateful rule mock, 422 cycle on edit and create, delete confirmation, axe on list/panel/form/confirmation
- [x] T7-21 mutation check: delete skips the confirmation; `RulesAdmin.test.tsx` goes red (7 tests); restore

## Not done (cut, stated rather than hidden)

- Nothing was run against the real services; every network call in the tests is a stub.
- Chart history backfill; series-count bound (points are bounded, the number of series is not).
- Token expiry handling beyond the library's silent renew (VERIFY: renew uses a refresh token if the
  realm issues one); there is no re-login prompt. Login against the real Keycloak was never run.
- The dev proxy has no automated test (checked once by hand against a stub).
- Playwright ran on installed Google Chrome (`PW_CHANNEL=chrome`), not Playwright's bundled Chromium:
  the browser download was declined.
