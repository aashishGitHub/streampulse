import { ConnectionStatus } from '../../components/ConnectionStatus';
import { formatClock } from '../metrics/chartGeometry';
import { useAlerts } from './useAlerts';
import type { Alert, AlertState } from './types';

const STATE_LABEL: Record<AlertState, { symbol: string; text: string }> = {
  FIRING: { symbol: '▲', text: 'FIRING' },
  SUPPRESSED: { symbol: '⊘', text: 'SUPPRESSED' },
  RESOLVED: { symbol: '✓', text: 'RESOLVED' },
};

const time = (iso: string) => {
  const ms = Date.parse(iso);
  return Number.isNaN(ms) ? iso : formatClock(ms);
};

function rootCause(alert: Alert, all: Alert[]): string {
  const id = alert.suppressedBy;
  if (!id) return 'not reported';
  const cause = all.find((a) => a.alertId === id);
  return cause ? `${cause.summary} (${id})` : id;
}

export function AlertList({ alerts }: { alerts: Alert[] }) {
  return (
    <ul className="alert-list" aria-label="Alerts">
      {alerts.map((alert) => {
        const label = STATE_LABEL[alert.state];
        return (
          <li key={alert.alertId} className={`alert alert-${alert.state.toLowerCase()}`}>
            <p className="alert-head">
              <span className="badge">
                <span aria-hidden="true">{label.symbol}</span> {label.text}
              </span>
              <span className="severity">Severity: {alert.severity}</span>
            </p>
            <p className="alert-summary">{alert.summary}</p>
            <p className="alert-meta">
              {alert.series && <span>{alert.series} · </span>}
              fired <time dateTime={alert.firedAt}>{time(alert.firedAt)}</time>
              {alert.resolvedAt && (
                <>
                  {' '}
                  · resolved <time dateTime={alert.resolvedAt}>{time(alert.resolvedAt)}</time>
                </>
              )}
            </p>
            {alert.state === 'SUPPRESSED' && (
              <p className="alert-cause">
                Suppressed. Root cause: <strong>{rootCause(alert, alerts)}</strong>
              </p>
            )}
          </li>
        );
      })}
    </ul>
  );
}

function AlertsPanel({ tenantId }: { tenantId: string }) {
  const { alerts, load, error, connection, reload } = useAlerts(tenantId);
  const count = (state: AlertState) => alerts.filter((a) => a.state === state).length;

  return (
    <>
      <ConnectionStatus state={connection.state} error={connection.error} what="Alert stream" />
      {load === 'failed' && (
        <div className="problem" role="alert">
          <p>Could not load alerts: {error}</p>
          <button type="button" onClick={() => void reload()}>
            Try again
          </button>
        </div>
      )}
      {load === 'loading' && alerts.length === 0 && <p role="status">Loading alerts…</p>}
      {alerts.length > 0 && (
        <p className="alert-counts" aria-live="polite">
          {count('FIRING')} firing · {count('SUPPRESSED')} suppressed · {count('RESOLVED')} resolved
        </p>
      )}
      {load === 'loaded' && alerts.length === 0 && <p className="empty">No alerts for this tenant.</p>}
      <AlertList alerts={alerts} />
    </>
  );
}

export function AlertFeed({ tenantId }: { tenantId: string }) {
  return (
    <section aria-labelledby="alerts-heading">
      <h2 id="alerts-heading">Alerts</h2>
      <AlertsPanel key={tenantId} tenantId={tenantId} />
    </section>
  );
}
