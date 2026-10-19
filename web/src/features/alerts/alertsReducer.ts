import { SEVERITIES, type Alert, type AlertState } from './types';

export interface AlertsState {
  byId: Record<string, Alert>;
  load: 'idle' | 'loading' | 'loaded' | 'failed';
  error: string | null;
  /** Live alerts received while a snapshot request is in flight; `null` when none is. */
  buffer: Alert[] | null;
}

export type AlertsAction =
  | { type: 'snapshotStarted' }
  | { type: 'snapshotLoaded'; alerts: Alert[] }
  | { type: 'snapshotFailed'; message: string }
  | { type: 'alert'; alert: Alert };

export const initialAlertsState: AlertsState = { byId: {}, load: 'idle', error: null, buffer: null };

// The list endpoint returns active alerts only (FIRING, SUPPRESSED), so a snapshot says nothing
// about RESOLVED ones: those seen on the stream are kept. A live alert is newer than the
// snapshot for anything it mentions, so the buffer is replayed over it.
export function alertsReducer(state: AlertsState, action: AlertsAction): AlertsState {
  switch (action.type) {
    case 'snapshotStarted':
      return { ...state, load: 'loading', error: null, buffer: [] };
    case 'alert':
      return {
        ...state,
        byId: { ...state.byId, [action.alert.alertId]: action.alert },
        buffer: state.buffer ? [...state.buffer, action.alert] : null,
      };
    case 'snapshotLoaded': {
      const resolvedKept = Object.values(state.byId).filter((a) => a.state === 'RESOLVED');
      const byId = Object.fromEntries([...resolvedKept, ...action.alerts].map((a) => [a.alertId, a]));
      for (const live of state.buffer ?? []) byId[live.alertId] = live;
      return { byId, load: 'loaded', error: null, buffer: null };
    }
    case 'snapshotFailed':
      return { ...state, load: 'failed', error: action.message, buffer: null };
  }
}

const STATE_ORDER: AlertState[] = ['FIRING', 'SUPPRESSED', 'RESOLVED'];
const stateRank = (alert: Alert) => STATE_ORDER.indexOf(alert.state);
const severityRank = (alert: Alert) => SEVERITIES.length - SEVERITIES.indexOf(alert.severity);

export function sortAlerts(alerts: Alert[]): Alert[] {
  return [...alerts].sort(
    (a, b) =>
      stateRank(a) - stateRank(b) ||
      severityRank(a) - severityRank(b) ||
      Date.parse(b.firedAt) - Date.parse(a.firedAt),
  );
}
