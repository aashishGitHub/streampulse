// @vitest-environment node
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createBatcher } from './batcher';

beforeEach(() => vi.useFakeTimers());
afterEach(() => vi.useRealTimers());

describe('createBatcher', () => {
  it('emits nothing until the interval has elapsed', () => {
    const onFlush = vi.fn();
    const batcher = createBatcher<number>(250, onFlush);
    batcher.push(1);
    vi.advanceTimersByTime(249);
    expect(onFlush).not.toHaveBeenCalled();
  });

  it('collapses a burst into one flush that carries every item, in order', () => {
    const onFlush = vi.fn();
    const batcher = createBatcher<number>(250, onFlush);
    for (let i = 1; i <= 100; i++) batcher.push(i);

    vi.advanceTimersByTime(250);

    expect(onFlush).toHaveBeenCalledTimes(1);
    expect(onFlush.mock.calls[0]?.[0]).toEqual(Array.from({ length: 100 }, (_, i) => i + 1));
  });

  it('flushes at most once per interval however long the burst lasts', () => {
    const onFlush = vi.fn();
    const batcher = createBatcher<number>(250, onFlush);
    for (let ms = 0; ms < 1_000; ms += 10) {
      batcher.push(ms);
      vi.advanceTimersByTime(10);
    }
    expect(onFlush.mock.calls.length).toBeLessThanOrEqual(4);
    expect(onFlush.mock.calls.flatMap(([items]) => items as number[])).toHaveLength(100);
  });

  it('starts a new interval for items that arrive after a flush', () => {
    const onFlush = vi.fn();
    const batcher = createBatcher<string>(250, onFlush);
    batcher.push('a');
    vi.advanceTimersByTime(250);
    batcher.push('b');
    vi.advanceTimersByTime(250);
    expect(onFlush.mock.calls).toEqual([[['a']], [['b']]]);
  });

  it('holds no timer while idle', () => {
    const batcher = createBatcher<number>(250, vi.fn());
    batcher.push(1);
    vi.advanceTimersByTime(250);
    expect(vi.getTimerCount()).toBe(0);
  });

  it('drops pending items and its timer when cancelled', () => {
    const onFlush = vi.fn();
    const batcher = createBatcher<number>(250, onFlush);
    batcher.push(1);
    batcher.cancel();
    vi.advanceTimersByTime(1_000);
    expect(onFlush).not.toHaveBeenCalled();
    expect(vi.getTimerCount()).toBe(0);
  });
});
