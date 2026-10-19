import { vi } from 'vitest';
import type { components } from '../api/generated/control-plane-v1';

type Rule = components['schemas']['AlertRule'];

export interface Call {
  method: string;
  path: string;
  search: string;
  body: unknown;
  authorization: string | null;
}

interface Injected {
  method: string;
  pathEndsWith: string;
  response: () => Response;
}

const problem = (status: number, body: Record<string, unknown>) =>
  new Response(JSON.stringify({ status, ...body }), { status, headers: { 'content-type': 'application/problem+json' } });

// An in-memory stand-in for the control plane's /v1/admin/rules, driven through a real Request.
// It is a stub written from the OpenAPI file, not the real service.
export function createFakeControlPlane(initial: Rule[] = []) {
  const rules = new Map<string, Rule>(initial.map((r) => [r.ruleId, { ...r, version: r.version ?? 0 } as Rule]));
  const calls: Call[] = [];
  const injected: Injected[] = [];

  const fetchFn = vi.fn(async (input: RequestInfo | URL): Promise<Response> => {
    const request = input as Request;
    const url = new URL(request.url);
    const body = request.method === 'GET' || request.method === 'DELETE' ? undefined : await request.clone().json().catch(() => undefined);
    calls.push({ method: request.method, path: url.pathname, search: url.search, body, authorization: request.headers.get('authorization') });

    const hit = injected.findIndex((i) => i.method === request.method && url.pathname.endsWith(i.pathEndsWith));
    if (hit !== -1) return injected.splice(hit, 1)[0]!.response();

    const idMatch = url.pathname.match(/\/v1\/admin\/rules\/([^/]+)$/);
    const id = idMatch ? decodeURIComponent(idMatch[1]!) : undefined;
    const json = (value: unknown, status = 200) => new Response(JSON.stringify(value), { status, headers: { 'content-type': 'application/json' } });

    if (url.pathname.endsWith('/v1/admin/tenants')) return json([{ tenantId: 'streamflix', displayName: 'StreamFlix' }]);
    if (!url.pathname.includes('/v1/admin/rules')) return problem(404, { title: 'Not Found' });

    if (!id) {
      if (request.method === 'GET') {
        const tenant = url.searchParams.get('tenantId');
        return json([...rules.values()].filter((r) => !tenant || r.tenantId === tenant));
      }
      const created = { ...(body as Rule), version: 0 } as Rule;
      if (rules.has(created.ruleId)) return problem(409, { title: 'Conflict', detail: `rule ${created.ruleId} already exists` });
      rules.set(created.ruleId, created);
      return json(created, 201);
    }

    const existing = rules.get(id);
    if (!existing) return problem(404, { title: 'Not Found', detail: `rule ${id} does not exist` });
    if (request.method === 'GET') return json(existing);
    if (request.method === 'PUT') {
      const updated = { ...existing, ...(body as object), version: (existing.version ?? 0) + 1 } as Rule;
      rules.set(id, updated);
      return json(updated);
    }
    rules.delete(id);
    return new Response(null, { status: 204 });
  });

  return {
    fetch: fetchFn as unknown as typeof fetch,
    calls,
    rules,
    callsOf: (method: string) => calls.filter((c) => c.method === method),
    failNext: (method: string, pathEndsWith: string, response: () => Response) => injected.push({ method, pathEndsWith, response }),
    problem,
  };
}
