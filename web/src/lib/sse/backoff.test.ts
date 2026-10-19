// @vitest-environment node
import { describe, expect, it } from 'vitest';
import { backoffDelay } from './backoff';

const config = { baseMs: 1_000, capMs: 30_000 };
const lowest = () => 0;
const highest = () => 0.999999;

describe('backoffDelay', () => {
  it('doubles the ceiling on each consecutive failure', () => {
    const ceilings = [0, 1, 2, 3, 4].map((attempt) => backoffDelay(attempt, config, highest));
    expect(ceilings.map(Math.round)).toEqual([1_000, 2_000, 4_000, 8_000, 16_000]);
  });

  it('never exceeds the cap, however many failures', () => {
    for (const attempt of [5, 6, 20, 1_000]) {
      expect(backoffDelay(attempt, config, highest)).toBeLessThanOrEqual(config.capMs);
    }
    expect(Math.round(backoffDelay(1_000, config, highest))).toBe(config.capMs);
  });

  it('keeps every delay between half and all of the ceiling', () => {
    expect(backoffDelay(3, config, lowest)).toBe(4_000);
    expect(backoffDelay(3, config, highest)).toBeCloseTo(8_000, 0);
    for (const draw of [0.1, 0.25, 0.5, 0.75, 0.9]) {
      const delay = backoffDelay(3, config, () => draw);
      expect(delay).toBeGreaterThanOrEqual(4_000);
      expect(delay).toBeLessThanOrEqual(8_000);
    }
  });

  it('gives clients with different random draws different delays', () => {
    expect(backoffDelay(4, config, () => 0.2)).not.toBe(backoffDelay(4, config, () => 0.8));
  });
});
