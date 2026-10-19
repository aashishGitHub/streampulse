import type { components } from '../../api/generated/alerts-v1';

export const ALERT_STATES = ['FIRING', 'RESOLVED', 'SUPPRESSED'] as const;
export const SEVERITIES = ['INFO', 'WARNING', 'CRITICAL'] as const;

export type AlertState = (typeof ALERT_STATES)[number];
export type Severity = (typeof SEVERITIES)[number];

// The contract's Alert, generated from api/openapi/alerts-v1.yaml. Stream and list payloads are
// still validated at runtime in parseAlert: a type does not check what arrives over the wire.
export type Alert = components['schemas']['Alert'];
