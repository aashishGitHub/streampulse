// @vitest-environment node
import { describe, expect, it } from 'vitest';
import { parseAlert, parseAlertList } from './parseAlert';

const wire = {
  alertId: 'a-1',
  tenantId: 'streamflix',
  ruleId: 'r-1',
  series: 'CONCURRENT_VIEWERS/tt-1/eu',
  state: 'FIRING',
  severity: 'CRITICAL',
  summary: 'Error rate above 5%',
  firedAt: '2026-10-12T10:00:00Z',
  resolvedAt: null,
  suppressedBy: null,
};

describe('parseAlert', () => {
  it('reads the documented shape', () => {
    expect(parseAlert(wire)).toEqual(wire);
  });

  it('treats absent nullable fields as null', () => {
    expect(parseAlert({ ...wire, resolvedAt: undefined, suppressedBy: undefined })).toMatchObject({ resolvedAt: null, suppressedBy: null });
  });

  it('keeps a resolution time and a suppressor when present', () => {
    expect(parseAlert({ ...wire, state: 'SUPPRESSED', suppressedBy: 'a-0', resolvedAt: '2026-10-12T10:05:00Z' })).toMatchObject({
      state: 'SUPPRESSED',
      suppressedBy: 'a-0',
      resolvedAt: '2026-10-12T10:05:00Z',
    });
  });

  it.each([
    ['null', null],
    ['a string', 'alert'],
    ['no id', { ...wire, alertId: undefined }],
    ['an empty id', { ...wire, alertId: '' }],
    ['an unknown state', { ...wire, state: 'PAGED' }],
    ['an unknown severity', { ...wire, severity: 'PANIC' }],
    ['no summary', { ...wire, summary: undefined }],
    ['no fired time', { ...wire, firedAt: undefined }],
  ])('rejects %s', (_name, value) => {
    expect(parseAlert(value)).toBeNull();
  });
});

describe('parseAlertList', () => {
  it('keeps the valid entries of a list and drops the rest', () => {
    expect(parseAlertList([wire, { nope: true }, { ...wire, alertId: 'a-2' }]).map((a) => a.alertId)).toEqual(['a-1', 'a-2']);
  });

  it('returns an empty list for something that is not a list', () => {
    expect(parseAlertList({ alerts: [] })).toEqual([]);
  });
});
