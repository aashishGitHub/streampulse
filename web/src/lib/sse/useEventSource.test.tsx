import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createFakeSseFetch, sseEvent } from '../../test/fakeSseFetch';
import { useEventSource, type UseEventSourceOptions } from './useEventSource';

beforeEach(() => vi.useFakeTimers());
afterEach(() => vi.useRealTimers());

const settle = (ms = 0) => act(() => vi.advanceTimersByTimeAsync(ms));

function setup(fake: ReturnType<typeof createFakeSseFetch>, initial: Partial<UseEventSourceOptions> = {}) {
  const onMessage = vi.fn();
  const hook = renderHook((props: Partial<UseEventSourceOptions>) =>
    useEventSource({ url: '/stream?tenantId=a', fetch: fake.fetch, onMessage, random: () => 1, ...props }),
    { initialProps: initial },
  );
  return { ...hook, onMessage };
}

describe('useEventSource', () => {
  it('starts as connecting, then reports open, then retrying when the server drops', async () => {
    const fake = createFakeSseFetch();
    const handle = fake.enqueue({ keepOpen: true });
    fake.enqueue({ keepOpen: true });
    const { result } = setup(fake);

    expect(result.current.state).toBe('connecting');
    await settle();
    expect(result.current.state).toBe('open');

    handle.end();
    await settle();
    expect(result.current.state).toBe('retrying');
    await settle(1_000);
    expect(result.current.state).toBe('open');
  });

  it('hands every message to the caller and tracks the last event id', async () => {
    const fake = createFakeSseFetch();
    fake.enqueue({ keepOpen: true, body: sseEvent('1-0', 'metric', { n: 1 }) + sseEvent('2-0', 'metric', { n: 2 }) });
    const { result, onMessage } = setup(fake);

    await settle();
    expect(onMessage).toHaveBeenCalledTimes(2);
    expect(onMessage.mock.calls.map(([m]) => m.id)).toEqual(['1-0', '2-0']);
    expect(result.current.getLastEventId()).toBe('2-0');
  });

  it('reports every state change to the caller, and nothing after unmount', async () => {
    const fake = createFakeSseFetch();
    const handle = fake.enqueue({ keepOpen: true });
    fake.enqueue({ keepOpen: true });
    const onStateChange = vi.fn();
    const { unmount } = setup(fake, { onStateChange });
    await settle();
    handle.end();
    await settle(1_000);
    expect(onStateChange.mock.calls.map(([state]) => state)).toEqual(['open', 'retrying', 'open']);

    unmount();
    expect(onStateChange.mock.calls.map(([state]) => state)).toEqual(['open', 'retrying', 'open']);
  });

  it('uses the latest callback without reconnecting', async () => {
    const fake = createFakeSseFetch();
    const handle = fake.enqueue({ keepOpen: true });
    const { rerender, onMessage } = setup(fake);
    await settle();

    const replacement = vi.fn();
    rerender({ onMessage: replacement });
    handle.send(sseEvent('1-0', 'metric', {}));
    await settle();

    expect(replacement).toHaveBeenCalledTimes(1);
    expect(onMessage).not.toHaveBeenCalled();
    expect(fake.requests).toHaveLength(1);
  });

  it('closes the connection and cancels timers on unmount', async () => {
    const fake = createFakeSseFetch();
    fake.enqueueNetworkError();
    fake.enqueue({ keepOpen: true });
    const { unmount, result } = setup(fake);
    await settle();
    expect(result.current.state).toBe('retrying');

    unmount();
    await settle(60_000);
    expect(fake.requests).toHaveLength(1);
    expect(vi.getTimerCount()).toBe(0);
  });

  it('aborts an open stream on unmount', async () => {
    const fake = createFakeSseFetch();
    fake.enqueue({ keepOpen: true });
    const { unmount } = setup(fake);
    await settle();

    unmount();
    await settle(60_000);
    expect(fake.requests).toHaveLength(1);
    expect(vi.getTimerCount()).toBe(0);
  });

  it('starts fresh, without the old resume id, when the url changes', async () => {
    const fake = createFakeSseFetch();
    fake.enqueue({ keepOpen: true, body: sseEvent('9-0', 'metric', {}) });
    fake.enqueue({ keepOpen: true });
    const { result, rerender } = setup(fake);
    await settle();
    expect(result.current.getLastEventId()).toBe('9-0');

    rerender({ url: '/stream?tenantId=b' });
    expect(result.current.state).toBe('connecting');
    await settle();

    expect(fake.requests.map((r) => r.url)).toEqual(['/stream?tenantId=a', '/stream?tenantId=b']);
    expect(fake.requests[1]?.headers.has('last-event-id')).toBe(false);
    expect(result.current.getLastEventId()).toBeNull();
  });

  it('does not connect while the url is null', async () => {
    const fake = createFakeSseFetch();
    const { result } = setup(fake, { url: null });
    await settle();
    expect(fake.requests).toHaveLength(0);
    expect(result.current.state).toBe('closed');
  });

  it('exposes the error when the server refuses the request for good', async () => {
    const fake = createFakeSseFetch();
    fake.enqueue({ status: 400, contentType: 'application/problem+json', body: '{}' });
    const { result } = setup(fake);
    await settle();
    expect(result.current.state).toBe('closed');
    expect(result.current.error).toMatchObject({ status: 400 });
  });
});
