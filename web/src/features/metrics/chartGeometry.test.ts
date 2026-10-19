// @vitest-environment node
import { describe, expect, it } from 'vitest';
import { buildChart, formatClock, formatValue, summarize } from './chartGeometry';

const box = { width: 100, height: 50, padLeft: 10, padRight: 10, padTop: 5, padBottom: 5 };
const p = (time: number, value: number) => ({ time, value, sampleCount: 1 });

describe('buildChart', () => {
  it('maps time left to right and larger values higher up', () => {
    const { coords } = buildChart([p(0, 0), p(10, 50), p(20, 100)], box);
    expect(coords.map((c) => c.x)).toEqual([10, 50, 90]);
    expect(coords[0]!.y).toBeGreaterThan(coords[1]!.y);
    expect(coords[1]!.y).toBeGreaterThan(coords[2]!.y);
  });

  it('keeps every coordinate inside the padded plot area', () => {
    const { coords } = buildChart([p(0, 3), p(7, 900), p(9, 12)], box);
    for (const { x, y } of coords) {
      expect(x).toBeGreaterThanOrEqual(box.padLeft);
      expect(x).toBeLessThanOrEqual(box.width - box.padRight);
      expect(y).toBeGreaterThanOrEqual(box.padTop);
      expect(y).toBeLessThanOrEqual(box.height - box.padBottom);
    }
  });

  it('draws a line through the points', () => {
    const { path } = buildChart([p(0, 1), p(1, 2)], box);
    expect(path).toMatch(/^M [\d.]+ [\d.]+ L [\d.]+ [\d.]+$/);
  });

  it('handles a single point without dividing by zero', () => {
    const { coords, path } = buildChart([p(5, 5)], box);
    expect(coords).toHaveLength(1);
    expect(Number.isFinite(coords[0]!.x)).toBe(true);
    expect(path).toBe(`M ${coords[0]!.x} ${coords[0]!.y}`);
  });

  it('handles a series that is all zeros', () => {
    const { coords, yMax } = buildChart([p(0, 0), p(1, 0)], box);
    expect(yMax).toBeGreaterThan(0);
    expect(coords.every((c) => Number.isFinite(c.y))).toBe(true);
  });

  it('returns nothing to draw for no points', () => {
    expect(buildChart([], box)).toMatchObject({ coords: [], path: '' });
  });
});

describe('summarize', () => {
  it('reports latest, range and count', () => {
    expect(summarize([p(1, 5), p(2, 9), p(3, 2)])).toEqual({ latest: p(3, 2), min: 2, max: 9, count: 3 });
  });
});

describe('formatting', () => {
  it('shows a value with at most three decimals and grouping', () => {
    expect(formatValue(1234.56789)).toBe('1,234.568');
    expect(formatValue(0.0123)).toBe('0.012');
    expect(formatValue(7)).toBe('7');
  });

  it('shows a clock time in UTC so the output does not depend on the viewer machine', () => {
    expect(formatClock(Date.parse('2026-10-12T10:01:02Z'))).toBe('10:01:02 UTC');
  });
});
