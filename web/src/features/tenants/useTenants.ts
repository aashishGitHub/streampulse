import { useEffect, useState } from 'react';
import { useControlClient } from '../../api/useControlClient';
import { useServices } from '../../services';

export interface TenantOption {
  id: string;
  label: string;
}

export interface TenantList {
  options: TenantOption[];
  status: 'loading' | 'loaded' | 'fallback';
}

// A failing control plane must not take the dashboard down, so the configured tenants stand in.
export function useTenants(): TenantList {
  const { config } = useServices();
  const client = useControlClient();
  const fallback: TenantOption[] = config.defaultTenants.map((id) => ({ id, label: id }));
  const [result, setResult] = useState<TenantList | null>(null);

  useEffect(() => {
    let active = true;
    const toFallback = (): TenantList => ({ options: config.defaultTenants.map((id) => ({ id, label: id })), status: 'fallback' });
    client
      .GET('/v1/admin/tenants')
      .then(({ data }) => {
        if (!active) return;
        setResult(
          data && data.length > 0
            ? { options: data.map((t) => ({ id: t.tenantId, label: `${t.displayName} (${t.tenantId})` })), status: 'loaded' }
            : toFallback(),
        );
      })
      .catch(() => {
        if (active) setResult(toFallback());
      });
    return () => {
      active = false;
    };
  }, [client, config.defaultTenants]);

  return result ?? { options: fallback, status: 'loading' };
}
