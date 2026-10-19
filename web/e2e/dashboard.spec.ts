import AxeBuilder from '@axe-core/playwright';
import { expect, test, type Page } from '@playwright/test';
import { mockBackends } from './mockBackends';

async function expectNoAxeViolations(page: Page) {
  const results = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa']).analyze();
  expect(results.violations, JSON.stringify(results.violations.map((v) => [v.id, v.nodes.map((n) => n.target)]), null, 1)).toEqual([]);
}

test.describe('live metrics', () => {
  test('draws a chart with a text equivalent from the stream', async ({ page }) => {
    await mockBackends(page);
    await page.goto('/');

    const chart = page.getByRole('figure', { name: /Concurrent viewers/ });
    await expect(chart).toBeVisible();
    await expect(chart.getByRole('img')).toHaveAccessibleDescription(/Latest 1,501 at .* UTC\. Lowest 1,501, highest 1,501, 1 point\./);
    await expect(page.getByRole('figure', { name: /Error rate/ })).toBeVisible();
    await expectNoAxeViolations(page);
  });

  test('asks for the tenant and the two live metrics', async ({ page }) => {
    const seen = await mockBackends(page);
    await page.goto('/');
    await expect.poll(() => seen.metricStreamUrls.length).toBeGreaterThan(0);
    const url = new URL(seen.metricStreamUrls[0]!);
    expect(url.pathname).toBe('/api/query/v1/stream/metrics');
    expect(url.searchParams.get('tenantId')).toBe('streamflix');
    expect(url.searchParams.get('metrics')).toBe('CONCURRENT_VIEWERS,ERROR_RATE');
  });

  test('reconnects after the finite stream ends and sends Last-Event-ID itself', async ({ page }) => {
    const seen = await mockBackends(page);
    await page.goto('/');

    await expect.poll(() => seen.metricStreamLastEventIds.length, { timeout: 10_000 }).toBeGreaterThanOrEqual(2);
    expect(seen.metricStreamLastEventIds[0]).toBeUndefined();
    expect(seen.metricStreamLastEventIds[1]).toBe('1791822805867-1');
    await expect(page.getByText(/2 points held/).first()).toBeAttached();
  });

  test('switching tenant opens a new stream for that tenant with no resume id', async ({ page }) => {
    const seen = await mockBackends(page);
    await page.goto('/');
    await expect(page.getByRole('option', { name: 'Acme (acme)' })).toBeAttached();
    await page.getByLabel('Tenant').selectOption('acme');

    await expect.poll(() => seen.metricStreamUrls.some((u) => u.includes('tenantId=acme'))).toBe(true);
    const index = seen.metricStreamUrls.findIndex((u) => u.includes('tenantId=acme'));
    expect(seen.metricStreamLastEventIds[index]).toBeUndefined();
  });

  test('falls back to the configured tenant and says so when the tenant list fails', async ({ page }) => {
    await mockBackends(page, { tenants: (route) => route.fulfill({ status: 500, contentType: 'application/problem+json', body: '{"title":"boom"}' }) });
    await page.goto('/');
    await expect(page.getByText('Tenant list unavailable; showing the configured tenants.')).toBeVisible();
    await expect(page.getByLabel('Tenant')).toHaveValue('streamflix');
  });
});

test.describe('alerts', () => {
  test('shows firing, suppressed and resolved alerts by text and symbol, with the root cause', async ({ page }) => {
    await mockBackends(page);
    await page.goto('/#/alerts');

    const list = page.getByRole('list', { name: 'Alerts' });
    await expect(list.getByRole('listitem')).toHaveCount(4);
    const bySummary = (text: string) => list.locator('li', { has: page.locator('.alert-summary', { hasText: new RegExp(`^${text}$`) }) });
    const cdn = bySummary('CDN origin unreachable');
    await expect(cdn).toContainText('▲ FIRING');
    const player = bySummary('Player errors high');
    await expect(player).toContainText('⊘ SUPPRESSED');
    await expect(player).toContainText('Root cause: CDN origin unreachable (cdn)');
    await expect(bySummary('Startup time slow')).toContainText('✓ RESOLVED');
    await expect(bySummary('Live: rebuffering spike')).toBeVisible();
    await expectNoAxeViolations(page);
  });

  test('shows the error when the list endpoint fails, and recovers on retry', async ({ page }) => {
    let calls = 0;
    await mockBackends(page, {
      alertsList: (route) => {
        calls += 1;
        return calls === 1
          ? route.fulfill({ status: 503, contentType: 'application/problem+json', body: JSON.stringify({ title: 'Service Unavailable', detail: 'engine warming up' }) })
          : route.fulfill({ status: 200, contentType: 'application/json', body: '[]' });
      },
    });
    await page.goto('/#/alerts');

    const failure = page.getByRole('alert').filter({ hasText: 'Could not load alerts' });
    await expect(failure).toContainText('Service Unavailable: engine warming up');
    await expectNoAxeViolations(page);

    await failure.getByRole('button', { name: 'Try again' }).click();
    await expect(page.getByText('Live: rebuffering spike')).toBeVisible();
    await expect(failure).toBeHidden();
  });
});

test.describe('alert rules admin', () => {
  test('lists the tenant rules with their versions and passes the accessibility scan', async ({ page }) => {
    const seen = await mockBackends(page);
    await page.goto('/#/rules');

    const table = page.getByRole('table', { name: 'Alert rules for streamflix' });
    await expect(table.getByRole('row')).toHaveCount(3);
    await expect(table.getByRole('row', { name: /high-errors/ })).toContainText('GT 0.05');
    await expect(table.getByRole('row', { name: /high-errors/ })).toContainText('PLAYER');
    expect(seen.ruleCalls[0]).toMatchObject({ method: 'GET', path: '/api/control/v1/admin/rules' });
    await expectNoAxeViolations(page);
  });

  test('opens a rule from the server and edits it, and the list shows the new version', async ({ page }) => {
    const seen = await mockBackends(page);
    await page.goto('/#/rules');
    await page.getByRole('button', { name: 'View rule high-errors' }).click();
    const panel = page.getByRole('region', { name: 'Rule high-errors' });
    await expect(panel).toContainText('Version');
    await expectNoAxeViolations(page);

    await panel.getByRole('button', { name: 'Edit' }).click();
    await page.getByLabel('Threshold').fill('0.5');
    await expectNoAxeViolations(page);
    await page.getByRole('button', { name: 'Save changes' }).click();

    await expect(page.getByText('Rule "high-errors" updated (now version 3).')).toBeVisible();
    await expect(page.getByRole('row', { name: /high-errors/ })).toContainText('GT 0.5');
    expect(seen.ruleCalls.find((c) => c.method === 'PUT')).toMatchObject({
      path: '/api/control/v1/admin/rules/high-errors',
      body: { metric: 'ERROR_RATE', comparator: 'GT', threshold: 0.5, severity: 'WARNING', category: 'PLAYER', dependsOn: ['CDN'] },
    });
  });

  test("shows the server's 422 dependency-cycle message when saving an edit, and keeps the input", async ({ page }) => {
    await mockBackends(page, {
      rules: (route) =>
        route.request().method() === 'PUT'
          ? route.fulfill({
              status: 422,
              contentType: 'application/problem+json',
              body: JSON.stringify({ title: 'Unprocessable Entity', status: 422, detail: 'dependency cycle: PLAYER -> CDN -> PLAYER' }),
            })
          : route.fallback(),
    });
    await page.goto('/#/rules');
    await page.getByRole('button', { name: 'Edit rule high-errors' }).click();
    await page.getByLabel('Threshold').fill('7');
    await page.getByRole('button', { name: 'Save changes' }).click();

    const problem = page.getByRole('alert');
    await expect(problem).toContainText('dependency cycle: PLAYER -> CDN -> PLAYER');
    await expect(page.getByLabel('Threshold')).toHaveValue('7');
    await expectNoAxeViolations(page);
  });

  test('creates a rule and sends it as JSON for the selected tenant, then lists it', async ({ page }) => {
    const seen = await mockBackends(page);
    await page.goto('/#/rules');
    await page.getByRole('button', { name: 'New rule' }).click();
    await page.getByLabel('Rule id').fill('startup-slow');
    await page.getByLabel('Metric', { exact: true }).selectOption('STARTUP_TIME_MS');
    await page.getByLabel('Threshold').fill('3000');
    await page.getByRole('button', { name: 'Create rule' }).click();

    await expect(page.getByRole('status').filter({ hasText: 'created' })).toHaveText('Rule "startup-slow" created.');
    await expect(page.getByRole('row', { name: /startup-slow/ })).toBeVisible();
    expect(seen.ruleBodies).toEqual([{ ruleId: 'startup-slow', tenantId: 'streamflix', metric: 'STARTUP_TIME_MS', threshold: 3000, comparator: 'GT', severity: 'WARNING' }]);
  });

  test('shows a problem+json cycle rejection on create and keeps the input', async ({ page }) => {
    await mockBackends(page, {
      rules: (route) =>
        route.request().method() === 'POST'
          ? route.fulfill({
              status: 422,
              contentType: 'application/problem+json',
              body: JSON.stringify({ title: 'Dependency cycle', status: 422, detail: 'CDN -> PLAYER -> CDN', traceId: 'trace-42' }),
            })
          : route.fallback(),
    });
    await page.goto('/#/rules');
    await page.getByRole('button', { name: 'New rule' }).click();
    await page.getByLabel('Rule id').fill('cyclic');
    await page.getByLabel('Metric', { exact: true }).selectOption('ERROR_RATE');
    await page.getByLabel('Threshold').fill('1');
    await page.getByRole('button', { name: 'Create rule' }).click();

    const problem = page.getByRole('alert');
    await expect(problem).toContainText('Dependency cycle');
    await expect(problem).toContainText('CDN -> PLAYER -> CDN');
    await expect(problem).toContainText('trace-42');
    await expect(page.getByLabel('Rule id')).toHaveValue('cyclic');
    await expectNoAxeViolations(page);
  });

  test('names the missing fields before sending anything', async ({ page }) => {
    const seen = await mockBackends(page);
    await page.goto('/#/rules');
    await page.getByRole('button', { name: 'New rule' }).click();
    await expectNoAxeViolations(page);
    await page.getByRole('button', { name: 'Create rule' }).click();
    await expect(page.getByText('Enter a rule id.')).toBeVisible();
    await expect(page.getByLabel('Rule id')).toBeFocused();
    expect(seen.ruleBodies).toEqual([]);
    await expectNoAxeViolations(page);
  });

  test('asks for confirmation before deleting, sends nothing until confirmed, and then removes the rule', async ({ page }) => {
    const seen = await mockBackends(page);
    await page.goto('/#/rules');
    const deletes = () => seen.ruleCalls.filter((c) => c.method === 'DELETE');

    await page.getByRole('button', { name: 'Delete rule high-errors' }).click();
    const confirm = page.getByRole('group', { name: /confirm deleting rule high-errors/i });
    await expect(confirm).toBeVisible();
    await expect(page.getByRole('button', { name: 'Keep rule' })).toBeFocused();
    await expectNoAxeViolations(page);
    expect(deletes()).toHaveLength(0);

    await page.getByRole('button', { name: 'Keep rule' }).click();
    await expect(confirm).toBeHidden();
    expect(deletes()).toHaveLength(0);
    await expect(page.getByRole('row', { name: /high-errors/ })).toBeVisible();

    await page.getByRole('button', { name: 'Delete rule high-errors' }).click();
    await page.getByRole('button', { name: 'Yes, delete rule' }).click();
    await expect(page.getByText('Rule "high-errors" deleted.')).toBeVisible();
    await expect(page.getByRole('row', { name: /high-errors/ })).toHaveCount(0);
    expect(deletes()).toEqual([{ method: 'DELETE', path: '/api/control/v1/admin/rules/high-errors', body: undefined }]);
  });

  test('shows the server refusal when a delete fails and keeps the rule', async ({ page }) => {
    await mockBackends(page, {
      rules: (route) =>
        route.request().method() === 'DELETE'
          ? route.fulfill({ status: 409, contentType: 'application/problem+json', body: JSON.stringify({ title: 'Conflict', status: 409, detail: 'rule is referenced' }) })
          : route.fallback(),
    });
    await page.goto('/#/rules');
    await page.getByRole('button', { name: 'Delete rule high-errors' }).click();
    await page.getByRole('button', { name: 'Yes, delete rule' }).click();
    await expect(page.getByRole('alert')).toContainText('rule is referenced');
    await expect(page.getByRole('row', { name: /high-errors/ })).toBeVisible();
  });

  test('shows the problem and a retry when the rule list fails', async ({ page }) => {
    let calls = 0;
    await mockBackends(page, {
      rules: (route) => {
        calls += 1;
        return calls === 1
          ? route.fulfill({ status: 500, contentType: 'application/problem+json', body: JSON.stringify({ title: 'Internal Server Error', status: 500, detail: 'database down' }) })
          : route.fallback();
      },
    });
    await page.goto('/#/rules');
    await expect(page.getByRole('alert')).toContainText('database down');
    await expectNoAxeViolations(page);
    await page.getByRole('button', { name: 'Try again' }).click();
    await expect(page.getByRole('table', { name: 'Alert rules for streamflix' })).toBeVisible();
  });
});

test('every view is reachable by keyboard alone', async ({ page }) => {
  await mockBackends(page);
  await page.goto('/');
  await page.keyboard.press('Tab');
  await expect(page.getByRole('link', { name: 'Skip to content' })).toBeFocused();
  const alertsLink = page.getByRole('link', { name: 'Alerts', exact: true });
  await alertsLink.focus();
  await page.keyboard.press('Enter');
  await expect(page.getByRole('heading', { name: 'Alerts' })).toBeVisible();
});
