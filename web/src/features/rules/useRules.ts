import { useCallback, useEffect, useState } from 'react';
import type { Problem } from '../../api/problem';
import { useControlClient } from '../../api/useControlClient';
import { getRule, listRules, type AlertRule } from './rulesApi';

type Loaded<T> = { key: string } & ({ ok: true; value: T } | { ok: false; problem: Problem });

export type Remote<T> =
  | { status: 'loading' }
  | { status: 'loaded'; value: T }
  | { status: 'failed'; problem: Problem };

// The result is stored with the key it was fetched for, so "loading" is derived (key mismatch)
// instead of being set from inside the effect, and a late reply for an old key is never shown.
function useRemote<T>(key: string, fetcher: () => Promise<{ ok: true; value: T } | { ok: false; problem: Problem }>) {
  const [nonce, setNonce] = useState(0);
  const [result, setResult] = useState<Loaded<T> | null>(null);
  const current = `${key}#${nonce}`;

  useEffect(() => {
    let active = true;
    void fetcher().then((outcome) => {
      if (active) setResult({ key: current, ...outcome });
    });
    return () => {
      active = false;
    };
  }, [current, fetcher]);

  const reload = useCallback(() => setNonce((n) => n + 1), []);
  const state: Remote<T> =
    !result || result.key !== current ? { status: 'loading' } : result.ok ? { status: 'loaded', value: result.value } : { status: 'failed', problem: result.problem };
  return { state, reload };
}

export function useRules(tenantId: string) {
  const client = useControlClient();
  const fetcher = useCallback(() => listRules(client, tenantId), [client, tenantId]);
  return useRemote<AlertRule[]>(tenantId, fetcher);
}

export function useRule(ruleId: string) {
  const client = useControlClient();
  const fetcher = useCallback(() => getRule(client, ruleId), [client, ruleId]);
  return useRemote<AlertRule>(ruleId, fetcher);
}
