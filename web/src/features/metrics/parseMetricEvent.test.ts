// @vitest-environment node
import { describe, expect, it } from 'vitest';
import { parseMetricEvent } from './parseMetricEvent';

const valid = {
  titleId: 'tt-1',
  region: 'eu-west-1',
  metric: 'CONCURRENT_VIEWERS',
  windowStart: '2026-10-12T10:00:00Z',
  windowEnd: '2026-10-12T10:00:05Z',
  value: 1234.5,
  sampleCount: 42,
  computedAt: '2026-10-12T10:00:06Z',
};

describe('parseMetricEvent', () => {
  it('reads the documented payload', () => {
    expect(parseMetricEvent(JSON.stringify(valid))).toEqual({
      titleId: 'tt-1',
      region: 'eu-west-1',
      metric: 'CONCURRENT_VIEWERS',
      time: Date.parse('2026-10-12T10:00:00Z'),
      value: 1234.5,
      sampleCount: 42,
    });
  });

  it('keeps an empty title id: an aggregate may have no title', () => {
    expect(parseMetricEvent(JSON.stringify({ ...valid, titleId: '' }))?.titleId).toBe('');
  });

  it.each([
    ['not json', '{oops'],
    ['not an object', '42'],
    ['null', 'null'],
    ['a missing metric', JSON.stringify({ ...valid, metric: undefined })],
    ['a non-numeric value', JSON.stringify({ ...valid, value: 'high' })],
    ['a null value', JSON.stringify({ ...valid, value: null })],
    ['an unparseable window start', JSON.stringify({ ...valid, windowStart: 'yesterday-ish' })],
    ['a missing region', JSON.stringify({ ...valid, region: undefined })],
  ])('rejects %s without throwing', (_name, data) => {
    expect(parseMetricEvent(data)).toBeNull();
  });

  it('defaults a missing sample count to zero', () => {
    expect(parseMetricEvent(JSON.stringify({ ...valid, sampleCount: undefined }))?.sampleCount).toBe(0);
  });
});
