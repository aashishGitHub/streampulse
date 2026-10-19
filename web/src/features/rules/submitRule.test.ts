// @vitest-environment node
import { describe, expect, it, vi } from 'vitest';
import { createControlClient } from '../../api/clients';
import type { AuthService } from '../../auth/oidcAuth';
import { createDisabledAuth } from '../../auth/oidcAuth';
import { submitRule } from './submitRule';

const rule = { ruleId: 'r1', tenantId: 'streamflix', metric: 'ERROR_RATE', threshold: 0.05, comparator: 'GT' as const, severity: 'WARNING' as const };

function clientWith(respond: (request: Request) => Response | Promise<Response>, auth: AuthService = createDisabledAuth()) {
  const calls: Request[] = [];
  const fetchFn = vi.fn(async (input: RequestInfo | URL) => {
    const request = input as Request;
    calls.push(request.clone());
    return respond(request);
  });
  return { client: createControlClient({ fetch: fetchFn as unknown as typeof fetch, baseUrl: 'http://test.local/api/control', auth }), calls };
}

describe('submitRule', () => {
  it('posts the rule as JSON to the admin endpoint and reports success on 201', async () => {
    const { client, calls } = clientWith(() => new Response(null, { status: 201 }));
    await expect(submitRule(client, rule)).resolves.toMatchObject({ ok: true });

    expect(calls[0]?.method).toBe('POST');
    expect(calls[0]?.url).toBe('http://test.local/api/control/v1/admin/rules');
    expect(await calls[0]?.json()).toEqual(rule);
  });

  it('attaches the access token when there is one', async () => {
    const auth = { ...createDisabledAuth(), getAccessToken: async () => 'tok-9' };
    const { client, calls } = clientWith(() => new Response(null, { status: 201 }), auth);
    await submitRule(client, rule);
    expect(calls[0]?.headers.get('authorization')).toBe('Bearer tok-9');
  });

  it('sends no Authorization header when there is no token', async () => {
    const { client, calls } = clientWith(() => new Response(null, { status: 201 }));
    await submitRule(client, rule);
    expect(calls[0]?.headers.has('authorization')).toBe(false);
  });

  it('returns the problem body of a rejected rule, field for field', async () => {
    const body = { type: 'urn:problem:cycle', title: 'Dependency cycle', status: 422, detail: 'CDN -> PLAYER -> CDN', traceId: 't-1' };
    const { client } = clientWith(
      () => new Response(JSON.stringify(body), { status: 422, headers: { 'content-type': 'application/problem+json' } }),
    );
    await expect(submitRule(client, rule)).resolves.toEqual({
      ok: false,
      problem: { title: 'Dependency cycle', status: 422, detail: 'CDN -> PLAYER -> CDN', traceId: 't-1' },
    });
  });

  it('handles an undocumented 400 validation problem the same way', async () => {
    const { client } = clientWith(
      () => new Response(JSON.stringify({ title: 'Bad Request', detail: 'threshold must be a number' }), { status: 400, headers: { 'content-type': 'application/problem+json' } }),
    );
    const result = await submitRule(client, rule);
    expect(result).toMatchObject({ ok: false, problem: { title: 'Bad Request', detail: 'threshold must be a number', status: 400 } });
  });

  it('describes a non-JSON failure from a proxy', async () => {
    const { client } = clientWith(() => new Response('upstream down', { status: 502 }));
    const result = await submitRule(client, rule);
    expect(result).toMatchObject({ ok: false, problem: { title: 'Request failed (HTTP 502)', detail: 'upstream down' } });
  });

  it('turns a network failure into a problem instead of throwing', async () => {
    const { client } = clientWith(() => {
      throw new TypeError('Failed to fetch');
    });
    await expect(submitRule(client, rule)).resolves.toEqual({
      ok: false,
      problem: { title: 'Could not reach the server', detail: 'Failed to fetch' },
    });
  });
});
