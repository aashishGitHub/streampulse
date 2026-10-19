import { ALERT_STATES, SEVERITIES, type Alert, type AlertState, type Severity } from './types';

const str = (value: unknown): value is string => typeof value === 'string';
const nullableString = (value: unknown): string | null => (str(value) && value !== '' ? value : null);

export function parseAlert(raw: unknown): Alert | null {
  if (typeof raw !== 'object' || raw === null) return null;
  const f = raw as Record<string, unknown>;

  if (!str(f.alertId) || f.alertId === '' || !str(f.summary) || !str(f.firedAt)) return null;
  if (!ALERT_STATES.includes(f.state as AlertState) || !SEVERITIES.includes(f.severity as Severity)) return null;

  return {
    alertId: f.alertId,
    tenantId: str(f.tenantId) ? f.tenantId : '',
    ruleId: str(f.ruleId) ? f.ruleId : '',
    series: str(f.series) ? f.series : '',
    state: f.state as AlertState,
    severity: f.severity as Severity,
    summary: f.summary,
    firedAt: f.firedAt,
    resolvedAt: nullableString(f.resolvedAt),
    suppressedBy: nullableString(f.suppressedBy),
  };
}

export function parseAlertList(raw: unknown): Alert[] {
  if (!Array.isArray(raw)) return [];
  return raw.map(parseAlert).filter((alert): alert is Alert => alert !== null);
}
