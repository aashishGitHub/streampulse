// @vitest-environment node
import { describe, expect, it } from 'vitest';
import { createSeriesState, seriesKey, seriesReducer } from './seriesReducer';
import type { MetricPoint } from './types';

const point = (over: Partial<MetricPoint> = {}): MetricPoint => ({
  titleId: 't1',
  region: 'eu',
  metric: 'CONCURRENT_VIEWERS',
  time: 1_000,
  value: 1,
  sampleCount: 5,
  ...over,
});

const add = (state: ReturnType<typeof createSeriesState>, ...points: MetricPoint[]) =>
  seriesReducer(state, { type: 'points', points });

describe('seriesReducer', () => {
  it('starts one series per title, region and metric', () => {
    const state = add(
      createSeriesState(10),
      point(),
      point({ region: 'us' }),
      point({ titleId: 't2' }),
      point({ metric: 'ERROR_RATE' }),
    );
    expect(Object.keys(state.series)).toHaveLength(4);
  });

  it('does not confuse ids that happen to contain the separator character', () => {
    expect(seriesKey('M', 'a:b', 'c')).not.toBe(seriesKey('M', 'a', 'b:c'));
  });

  it('appends points in time order', () => {
    const state = add(createSeriesState(10), point({ time: 1, value: 10 }), point({ time: 2, value: 20 }));
    expect(Object.values(state.series)[0]?.points.map((p) => p.value)).toEqual([10, 20]);
  });

  it('replaces the point when a window is re-reported, instead of adding a duplicate', () => {
    const state = add(createSeriesState(10), point({ time: 5, value: 1 }), point({ time: 5, value: 2 }));
    const points = Object.values(state.series)[0]?.points;
    expect(points).toHaveLength(1);
    expect(points?.[0]?.value).toBe(2);
  });

  it('places a late window in order and replaces an old one in place', () => {
    let state = add(createSeriesState(10), point({ time: 10, value: 1 }), point({ time: 30, value: 3 }));
    state = add(state, point({ time: 20, value: 2 }), point({ time: 10, value: 9 }));
    expect(Object.values(state.series)[0]?.points.map((p) => [p.time, p.value])).toEqual([
      [10, 9],
      [20, 2],
      [30, 3],
    ]);
  });

  it('keeps only the newest maxPoints per series, dropping the oldest', () => {
    const points = Array.from({ length: 25 }, (_, i) => point({ time: i, value: i }));
    const state = add(createSeriesState(10), ...points);
    const kept = Object.values(state.series)[0]?.points;
    expect(kept).toHaveLength(10);
    expect(kept?.[0]?.value).toBe(15);
    expect(kept?.at(-1)?.value).toBe(24);
  });

  it('bounds each series separately', () => {
    const state = add(
      createSeriesState(3),
      ...Array.from({ length: 5 }, (_, i) => point({ time: i })),
      ...Array.from({ length: 5 }, (_, i) => point({ time: i, region: 'us' })),
    );
    expect(Object.values(state.series).map((s) => s.points.length)).toEqual([3, 3]);
  });

  it('leaves untouched series as the same object, so their charts need not re-render', () => {
    const first = add(createSeriesState(10), point(), point({ region: 'us' }));
    const second = add(first, point({ time: 2_000 }));
    expect(second.series[seriesKey('CONCURRENT_VIEWERS', 't1', 'us')]).toBe(first.series[seriesKey('CONCURRENT_VIEWERS', 't1', 'us')]);
    expect(second.series[seriesKey('CONCURRENT_VIEWERS', 't1', 'eu')]).not.toBe(first.series[seriesKey('CONCURRENT_VIEWERS', 't1', 'eu')]);
  });

  it('does not mutate the previous state', () => {
    const first = add(createSeriesState(10), point({ time: 1 }));
    const snapshot = JSON.stringify(first);
    add(first, point({ time: 2 }));
    expect(JSON.stringify(first)).toBe(snapshot);
  });

  it('forgets everything on reset but keeps its bound', () => {
    const state = seriesReducer(add(createSeriesState(7), point()), { type: 'reset' });
    expect(state.series).toEqual({});
    expect(state.maxPoints).toBe(7);
  });
});
