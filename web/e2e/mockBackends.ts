import type { Page, Route } from '@playwright/test';

export interface Seen {
  metricStreamLastEventIds: Array<string | undefined>;
  metricStreamUrls: string[];
  ruleBodies: unknown[];
  ruleCalls: Array<{ method: string; path: string; body: unknown }>;
  authorization: Array<string | undefined>;
}

export const metric = (over: Record<string, unknown> = {}) => ({
  titleId: 'tt-1',
  region: 'eu-west-1',
  metric: 'CONCURRENT_VIEWERS',
  windowStart: '2026-10-12T10:00:00Z',
  windowEnd: '2026-10-12T10:00:05Z',
  value: 1500,
  sampleCount: 40,
  computedAt: '2026-10-12T10:00:06Z',
  ...over,
});

export const alert = (over: Record<string, unknown> = {}) => ({
  alertId: 'a-1',
  tenantId: 'streamflix',
  ruleId: 'r-1',
  series: 'ERROR_RATE/tt-1/eu-west-1',
  state: 'FIRING',
  severity: 'CRITICAL',
  summary: 'Error rate above 5%',
  firedAt: '2026-10-12T10:00:00Z',
  resolvedAt: null,
  suppressedBy: null,
  ...over,
});

export const sse = (id: string, event: string, data: unknown) => `id: ${id}\nevent: ${event}\ndata: ${JSON.stringify(data)}\n\n`;

const json = (route: Route, body: unknown, status = 200, contentType = 'application/json') =>
  route.fulfill({ status, contentType, body: JSON.stringify(body) });

export interface Overrides {
  alertsList?: (route: Route) => Promise<void> | void;
  /** Registered after the default handler; call route.fallback() to let the default answer. */
  rules?: (route: Route) => Promise<void> | void;
  tenants?: (route: Route) => Promise<void> | void;
}

// Every /api call is answered here; anything not listed is aborted, so no test can reach a backend by accident.
export async function mockBackends(page: Page, overrides: Overrides = {}): Promise<Seen> {
  const seen: Seen = { metricStreamLastEventIds: [], metricStreamUrls: [], ruleBodies: [], ruleCalls: [], authorization: [] };

  await page.route('**/api/**', (route) => route.abort('connectionrefused'));

  await page.route(/\/api\/control\/v1\/admin\/tenants$/, (route) =>
    overrides.tenants ? overrides.tenants(route) : json(route, [{ tenantId: 'streamflix', displayName: 'StreamFlix' }, { tenantId: 'acme', displayName: 'Acme' }]),
  );

  const rules = new Map<string, Record<string, unknown>>([
    ['high-errors', { ruleId: 'high-errors', tenantId: 'streamflix', metric: 'ERROR_RATE', comparator: 'GT', threshold: 0.05, severity: 'WARNING', category: 'PLAYER', dependsOn: ['CDN'], version: 2 }],
    ['low-viewers', { ruleId: 'low-viewers', tenantId: 'streamflix', metric: 'CONCURRENT_VIEWERS', comparator: 'LT', threshold: 10, severity: 'CRITICAL', version: 0 }],
  ]);

  // A small stateful stand-in for the control plane's rule endpoints, written from the OpenAPI file.
  await page.route(/\/api\/control\/v1\/admin\/rules(\/[^/?]+)?(\?.*)?$/, (route) => {
    const request = route.request();
    const method = request.method();
    const id = new URL(request.url()).pathname.split('/rules/')[1];
    const body = method === 'POST' || method === 'PUT' ? request.postDataJSON() : undefined;
    seen.ruleCalls.push({ method, path: new URL(request.url()).pathname, body });
    seen.authorization.push(request.headers().authorization);

    if (method === 'GET' && !id) return json(route, [...rules.values()]);
    if (method === 'POST') {
      seen.ruleBodies.push(body);
      rules.set(body.ruleId, { ...body, version: 0 });
      return json(route, { ...body, version: 0 }, 201);
    }
    const existing = id ? rules.get(decodeURIComponent(id)) : undefined;
    if (!existing) return json(route, { title: 'Not Found', status: 404, detail: `rule ${id} does not exist` }, 404, 'application/problem+json');
    if (method === 'GET') return json(route, existing);
    if (method === 'PUT') {
      const updated = { ...existing, ...body, version: (existing.version as number) + 1 };
      rules.set(existing.ruleId as string, updated);
      return json(route, updated);
    }
    rules.delete(existing.ruleId as string);
    return route.fulfill({ status: 204 });
  });

  if (overrides.rules) await page.route(/\/api\/control\/v1\/admin\/rules(\/[^/?]+)?(\?.*)?$/, overrides.rules);

  let metricConnection = 0;
  await page.route(/\/api\/query\/v1\/stream\/metrics\?/, (route) => {
    const request = route.request();
    seen.metricStreamLastEventIds.push(request.headers()['last-event-id']);
    seen.metricStreamUrls.push(request.url());
    metricConnection += 1;
    const n = metricConnection;
    return route.fulfill({
      status: 200,
      contentType: 'text/event-stream',
      body:
        ': heartbeat\n\n' +
        sse(`${1791822805866 + n}-0`, 'metric', metric({ windowStart: `2026-10-12T10:00:${String(n * 5).padStart(2, '0')}Z`, value: 1500 + n })) +
        sse(`${1791822805866 + n}-1`, 'metric', metric({ metric: 'ERROR_RATE', value: 0.02, windowStart: `2026-10-12T10:00:${String(n * 5).padStart(2, '0')}Z` })),
    });
  });

  await page.route(/\/api\/alerts\/v1\/alerts\?/, (route) =>
    overrides.alertsList
      ? overrides.alertsList(route)
      : json(route, [
          alert({ alertId: 'cdn', summary: 'CDN origin unreachable', severity: 'CRITICAL' }),
          alert({ alertId: 'player', state: 'SUPPRESSED', suppressedBy: 'cdn', summary: 'Player errors high', severity: 'WARNING' }),
        ]),
  );

  await page.route(/\/api\/alerts\/v1\/alerts\/stream\?/, (route) =>
    route.fulfill({
      status: 200,
      contentType: 'text/event-stream',
      body:
        sse('1', 'alert', alert({ alertId: 'live', summary: 'Live: rebuffering spike', severity: 'WARNING' })) +
        sse('2', 'alert', alert({ alertId: 'old', state: 'RESOLVED', resolvedAt: '2026-10-12T09:30:00Z', summary: 'Startup time slow', severity: 'INFO' })),
    }),
  );

  return seen;
}
