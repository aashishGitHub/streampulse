// @vitest-environment node
import { describe, expect, it, vi } from 'vitest';
import { createControlClient } from '../../api/clients';
import { createDisabledAuth } from '../../auth/oidcAuth';
import { createFakeControlPlane } from '../../test/fakeControlPlane';
import { deleteRule, getRule, listRules, updateRule } from './rulesApi';
import type { AlertRule } from './rulesApi';

const rule = (over: Partial<AlertRule> = {}): AlertRule => ({
  ruleId: 'high-errors',
  tenantId: 'streamflix',
  metric: 'ERROR_RATE',
  comparator: 'GT',
  threshold: 0.05,
  severity: 'WARNING',
  ...over,
});

function setup(initial: AlertRule[] = [rule()], auth = createDisabledAuth()) {
  const fake = createFakeControlPlane(initial);
  const client = createControlClient({ fetch: fake.fetch, baseUrl: 'http://test.local/api/control', auth });
  return { fake, client };
}

describe('rulesApi', () => {
  it('lists the rules of one tenant', async () => {
    const { fake, client } = setup([rule(), rule({ ruleId: 'other', tenantId: 'acme' })]);
    const result = await listRules(client, 'streamflix');
    expect(result).toEqual({ ok: true, value: [expect.objectContaining({ ruleId: 'high-errors' })] });
    expect(fake.calls[0]).toMatchObject({ method: 'GET', path: '/api/control/v1/admin/rules', search: '?tenantId=streamflix' });
  });

  it('reads one rule, encoding the id in the path', async () => {
    const { fake, client } = setup([rule({ ruleId: 'a:b.c' })]);
    const result = await getRule(client, 'a:b.c');
    expect(result).toMatchObject({ ok: true, value: { ruleId: 'a:b.c' } });
    expect(fake.calls[0]!.path).toBe('/api/control/v1/admin/rules/a%3Ab.c');
  });

  it('returns the problem for a rule that does not exist', async () => {
    const { client } = setup([]);
    expect(await getRule(client, 'nope')).toEqual({ ok: false, problem: { title: 'Not Found', detail: 'rule nope does not exist', status: 404 } });
  });

  it('updates with a PUT that carries the editable fields and not the id or tenant', async () => {
    const { fake, client } = setup();
    const result = await updateRule(client, 'high-errors', { metric: 'ERROR_RATE', comparator: 'GTE', threshold: 0.1, severity: 'CRITICAL', category: 'PLAYER', dependsOn: ['CDN'] });

    expect(result).toMatchObject({ ok: true, value: { ruleId: 'high-errors', threshold: 0.1, version: 1 } });
    expect(fake.calls[0]).toMatchObject({
      method: 'PUT',
      path: '/api/control/v1/admin/rules/high-errors',
      body: { metric: 'ERROR_RATE', comparator: 'GTE', threshold: 0.1, severity: 'CRITICAL', category: 'PLAYER', dependsOn: ['CDN'] },
    });
    expect(fake.calls[0]!.body).not.toHaveProperty('ruleId');
    expect(fake.calls[0]!.body).not.toHaveProperty('tenantId');
  });

  it("returns the server's cycle message untouched on a 422", async () => {
    const { fake, client } = setup();
    fake.failNext('PUT', '/high-errors', () => fake.problem(422, { title: 'Unprocessable Entity', detail: 'dependency cycle: CDN -> PLAYER -> CDN' }));
    const result = await updateRule(client, 'high-errors', { metric: 'ERROR_RATE', threshold: 1 });
    expect(result).toEqual({ ok: false, problem: { title: 'Unprocessable Entity', detail: 'dependency cycle: CDN -> PLAYER -> CDN', status: 422 } });
  });

  it('deletes with a DELETE and treats 204 as success', async () => {
    const { fake, client } = setup();
    expect(await deleteRule(client, 'high-errors')).toEqual({ ok: true, value: undefined });
    expect(fake.calls[0]).toMatchObject({ method: 'DELETE', path: '/api/control/v1/admin/rules/high-errors' });
    expect(fake.rules.size).toBe(0);
  });

  it('returns the problem when deleting something that is gone', async () => {
    const { client } = setup([]);
    expect(await deleteRule(client, 'gone')).toMatchObject({ ok: false, problem: { status: 404 } });
  });

  it('turns a network failure into a problem for every operation', async () => {
    const failing = vi.fn(async () => {
      throw new TypeError('Failed to fetch');
    });
    const client = createControlClient({ fetch: failing as unknown as typeof fetch, baseUrl: 'http://test.local/api/control', auth: createDisabledAuth() });
    const expected = { ok: false, problem: { title: 'Could not reach the server', detail: 'Failed to fetch' } };
    expect(await listRules(client, 't')).toEqual(expected);
    expect(await getRule(client, 'r')).toEqual(expected);
    expect(await updateRule(client, 'r', { metric: 'm', threshold: 1 })).toEqual(expected);
    expect(await deleteRule(client, 'r')).toEqual(expected);
  });

  it('sends the access token', async () => {
    const { fake, client } = setup([rule()], { ...createDisabledAuth(), getAccessToken: async () => 'tok-3' });
    await deleteRule(client, 'high-errors');
    expect(fake.calls[0]!.authorization).toBe('Bearer tok-3');
  });
});
