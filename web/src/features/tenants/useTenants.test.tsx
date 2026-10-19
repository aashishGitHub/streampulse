import { renderHook, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { describe, expect, it, vi } from 'vitest';
import { ServicesContext } from '../../services';
import { testServices } from '../../test/renderWithServices';
import { useTenants } from './useTenants';

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } });

function setup(respond: () => Response | Promise<Response>) {
  const fetchFn = vi.fn(async () => respond());
  const services = testServices({ fetch: fetchFn as unknown as typeof fetch, config: { defaultTenants: ['streamflix'] } });
  const wrapper = ({ children }: { children: ReactNode }) => <ServicesContext.Provider value={services}>{children}</ServicesContext.Provider>;
  return { ...renderHook(() => useTenants(), { wrapper }), fetchFn };
}

describe('useTenants', () => {
  it('starts with the configured tenants while loading', () => {
    const { result } = setup(() => new Promise<Response>(() => {}));
    expect(result.current.status).toBe('loading');
    expect(result.current.options).toEqual([{ id: 'streamflix', label: 'streamflix' }]);
  });

  it('lists the tenants the control plane returns', async () => {
    const { result, fetchFn } = setup(() => json([{ tenantId: 'acme', displayName: 'Acme Corp' }, { tenantId: 'zed', displayName: 'Zed' }]));
    await waitFor(() => expect(result.current.status).toBe('loaded'));
    expect(result.current.options).toEqual([
      { id: 'acme', label: 'Acme Corp (acme)' },
      { id: 'zed', label: 'Zed (zed)' },
    ]);
    expect((fetchFn.mock.calls[0] as unknown as [Request])[0].url).toBe('http://test.local/api/control/v1/admin/tenants');
  });

  it('falls back to the configured tenants when the request fails', async () => {
    const { result } = setup(() => json({ title: 'down' }, 503));
    await waitFor(() => expect(result.current.status).toBe('fallback'));
    expect(result.current.options).toEqual([{ id: 'streamflix', label: 'streamflix' }]);
  });

  it('falls back when the server cannot be reached', async () => {
    const { result } = setup(() => {
      throw new TypeError('Failed to fetch');
    });
    await waitFor(() => expect(result.current.status).toBe('fallback'));
  });

  it('falls back when the list is empty', async () => {
    const { result } = setup(() => json([]));
    await waitFor(() => expect(result.current.status).toBe('fallback'));
    expect(result.current.options).toHaveLength(1);
  });
});
