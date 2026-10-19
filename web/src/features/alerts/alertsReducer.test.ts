// @vitest-environment node
import { describe, expect, it } from 'vitest';
import { alertsReducer, initialAlertsState, sortAlerts, type AlertsState } from './alertsReducer';
import type { Alert } from './types';

const alert = (over: Partial<Alert> = {}): Alert => ({
  alertId: 'a1',
  tenantId: 't',
  ruleId: 'r1',
  series: 's',
  state: 'FIRING',
  severity: 'WARNING',
  summary: 'x',
  firedAt: '2026-10-12T10:00:00Z',
  resolvedAt: null,
  suppressedBy: null,
  ...over,
});

const run = (state: AlertsState, ...actions: Parameters<typeof alertsReducer>[1][]) => actions.reduce(alertsReducer, state);

describe('alertsReducer', () => {
  it('replaces the list with a loaded snapshot', () => {
    const state = run(initialAlertsState, { type: 'snapshotStarted' }, { type: 'snapshotLoaded', alerts: [alert(), alert({ alertId: 'a2' })] });
    expect(Object.keys(state.byId)).toEqual(['a1', 'a2']);
    expect(state.load).toBe('loaded');
  });

  it('applies a live alert as an update of the same id, not a second row', () => {
    const state = run(
      initialAlertsState,
      { type: 'snapshotStarted' },
      { type: 'snapshotLoaded', alerts: [alert()] },
      { type: 'alert', alert: alert({ state: 'RESOLVED', resolvedAt: '2026-10-12T10:05:00Z' }) },
    );
    expect(Object.keys(state.byId)).toEqual(['a1']);
    expect(state.byId.a1?.state).toBe('RESOLVED');
  });

  it('shows a live alert that arrives before the snapshot has loaded', () => {
    const state = run(initialAlertsState, { type: 'snapshotStarted' }, { type: 'alert', alert: alert({ alertId: 'early' }) });
    expect(state.byId.early).toBeDefined();
  });

  it('keeps a live change received while the snapshot was loading, even though the snapshot is older', () => {
    const state = run(
      initialAlertsState,
      { type: 'snapshotStarted' },
      { type: 'alert', alert: alert({ state: 'RESOLVED' }) },
      { type: 'snapshotLoaded', alerts: [alert({ state: 'FIRING' }), alert({ alertId: 'a2' })] },
    );
    expect(state.byId.a1?.state).toBe('RESOLVED');
    expect(state.byId.a2).toBeDefined();
  });

  it('keeps resolved alerts seen on the stream when a reload lists only the active ones', () => {
    const state = run(
      initialAlertsState,
      { type: 'snapshotStarted' },
      { type: 'snapshotLoaded', alerts: [alert({ alertId: 'gone' })] },
      { type: 'alert', alert: alert({ alertId: 'gone', state: 'RESOLVED', resolvedAt: '2026-10-12T10:05:00Z' }) },
      { type: 'snapshotStarted' },
      { type: 'snapshotLoaded', alerts: [alert({ alertId: 'still' })] },
    );
    expect(Object.keys(state.byId).sort()).toEqual(['gone', 'still']);
    expect(state.byId.gone?.state).toBe('RESOLVED');
  });

  it('drops a firing alert the reload no longer lists: it is no longer active', () => {
    const state = run(
      initialAlertsState,
      { type: 'snapshotStarted' },
      { type: 'snapshotLoaded', alerts: [alert({ alertId: 'a' })] },
      { type: 'snapshotStarted' },
      { type: 'snapshotLoaded', alerts: [] },
    );
    expect(state.byId).toEqual({});
  });

  it('stops buffering once the snapshot has loaded', () => {
    const state = run(initialAlertsState, { type: 'snapshotStarted' }, { type: 'snapshotLoaded', alerts: [] });
    expect(state.buffer).toBeNull();
  });

  it('keeps showing what it has while a reload is under way', () => {
    const state = run(
      initialAlertsState,
      { type: 'snapshotStarted' },
      { type: 'snapshotLoaded', alerts: [alert()] },
      { type: 'snapshotStarted' },
    );
    expect(state.load).toBe('loading');
    expect(state.byId.a1).toBeDefined();
  });

  it('records a failed load with its message and keeps the live alerts it has', () => {
    const state = run(
      initialAlertsState,
      { type: 'snapshotStarted' },
      { type: 'alert', alert: alert() },
      { type: 'snapshotFailed', message: 'HTTP 503' },
    );
    expect(state.load).toBe('failed');
    expect(state.error).toBe('HTTP 503');
    expect(state.byId.a1).toBeDefined();
    expect(state.buffer).toBeNull();
  });

  it('clears the error when a retry starts', () => {
    const state = run(initialAlertsState, { type: 'snapshotFailed', message: 'x' }, { type: 'snapshotStarted' });
    expect(state.error).toBeNull();
  });
});

describe('sortAlerts', () => {
  it('puts firing first, then suppressed, then resolved', () => {
    const sorted = sortAlerts([
      alert({ alertId: 'r', state: 'RESOLVED' }),
      alert({ alertId: 's', state: 'SUPPRESSED' }),
      alert({ alertId: 'f', state: 'FIRING' }),
    ]);
    expect(sorted.map((a) => a.alertId)).toEqual(['f', 's', 'r']);
  });

  it('orders by severity, most severe first, within a state', () => {
    const sorted = sortAlerts([
      alert({ alertId: 'i', severity: 'INFO' }),
      alert({ alertId: 'c', severity: 'CRITICAL' }),
      alert({ alertId: 'w', severity: 'WARNING' }),
    ]);
    expect(sorted.map((a) => a.alertId)).toEqual(['c', 'w', 'i']);
  });

  it('orders newest first when state and severity tie', () => {
    const sorted = sortAlerts([
      alert({ alertId: 'old', firedAt: '2026-10-12T09:00:00Z' }),
      alert({ alertId: 'new', firedAt: '2026-10-12T11:00:00Z' }),
    ]);
    expect(sorted.map((a) => a.alertId)).toEqual(['new', 'old']);
  });

  it('does not reorder the input', () => {
    const input = [alert({ alertId: 'r', state: 'RESOLVED' }), alert({ alertId: 'f' })];
    sortAlerts(input);
    expect(input.map((a) => a.alertId)).toEqual(['r', 'f']);
  });
});
