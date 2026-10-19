import { useCallback, useEffect, useReducer, useRef } from 'react';
import { problemFrom } from '../../api/problem';
import type { SseMessage } from '../../lib/sse/parser';
import { useEventSource } from '../../lib/sse/useEventSource';
import { bearerHeaders, useServices } from '../../services';
import { alertsReducer, initialAlertsState, sortAlerts } from './alertsReducer';
import { parseAlert, parseAlertList } from './parseAlert';

// Data flow: GET snapshot + live stream -> one reducer. The snapshot is taken again after every
// reconnect, because the stream's ids are per-instance and a gap cannot be proven closed.
// Callers remount this with `key={tenantId}`.
export function useAlerts(tenantId: string) {
  const { fetch, config, auth } = useServices();
  const [state, dispatch] = useReducer(alertsReducer, initialAlertsState);
  const inFlight = useRef<AbortController | null>(null);
  const opens = useRef(0);
  const tenant = encodeURIComponent(tenantId);
  const listUrl = `${config.urls.alerts}/v1/alerts?tenantId=${tenant}`;

  const reload = useCallback(async () => {
    inFlight.current?.abort();
    const mine = new AbortController();
    inFlight.current = mine;
    dispatch({ type: 'snapshotStarted' });
    try {
      const headers = { Accept: 'application/json', ...(await bearerHeaders(auth)()) };
      const response = await fetch(listUrl, { headers, signal: mine.signal });
      if (!response.ok) {
        const problem = problemFrom(await response.json().catch(() => undefined), response);
        throw new Error(problem.detail ? `${problem.title}: ${problem.detail}` : problem.title);
      }
      dispatch({ type: 'snapshotLoaded', alerts: parseAlertList(await response.json()) });
    } catch (cause) {
      if (mine.signal.aborted) return;
      dispatch({ type: 'snapshotFailed', message: cause instanceof Error ? cause.message : String(cause) });
    }
  }, [fetch, auth, listUrl]);

  useEffect(() => {
    void reload();
    return () => inFlight.current?.abort();
  }, [reload]);

  const onMessage = useCallback((message: SseMessage) => {
    if (message.event !== 'alert') return;
    let raw: unknown;
    try {
      raw = JSON.parse(message.data);
    } catch {
      return;
    }
    const alert = parseAlert(raw);
    if (alert) dispatch({ type: 'alert', alert });
  }, []);

  const onStateChange = useCallback(
    (next: string) => {
      if (next !== 'open') return;
      opens.current += 1;
      if (opens.current > 1) void reload();
    },
    [reload],
  );

  const connection = useEventSource({
    url: `${config.urls.alerts}/v1/alerts/stream?tenantId=${tenant}`,
    fetch,
    onMessage,
    onStateChange,
    getHeaders: bearerHeaders(auth),
  });

  return { alerts: sortAlerts(Object.values(state.byId)), load: state.load, error: state.error, connection, reload };
}
