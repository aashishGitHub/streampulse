// @vitest-environment node
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createFakeSseFetch, sseEvent } from '../../test/fakeSseFetch';
import { openSseConnection, type ConnectionState, type SseConnectionOptions } from './connection';

beforeEach(() => vi.useFakeTimers());
afterEach(() => vi.useRealTimers());

const BACKOFF = { baseMs: 1_000, capMs: 5_000 };
const ceilingEveryTime = () => 1;

function connect(fake: ReturnType<typeof createFakeSseFetch>, overrides: Partial<SseConnectionOptions> = {}) {
  const states: ConnectionState[] = [];
  const errors: Array<Error | null> = [];
  const messages: string[] = [];
  const connection = openSseConnection({
    url: '/stream?tenantId=t',
    fetch: fake.fetch,
    backoff: BACKOFF,
    idleTimeoutMs: 45_000,
    random: ceilingEveryTime,
    onMessage: (m) => messages.push(m.id),
    onStateChange: (state, error) => {
      states.push(state);
      errors.push(error);
    },
    ...overrides,
  });
  return { connection, states, errors, messages };
}

const flush = () => vi.advanceTimersByTimeAsync(0);

describe('openSseConnection: reconnect schedule', () => {
  it('waits 1 s, 2 s, then 4 s after consecutive failures', async () => {
    const fake = createFakeSseFetch();
    for (let i = 0; i < 4; i++) fake.enqueueNetworkError();
    connect(fake);

    await flush();
    expect(fake.requests).toHaveLength(1);

    await vi.advanceTimersByTimeAsync(999);
    expect(fake.requests).toHaveLength(1);
    await vi.advanceTimersByTimeAsync(1);
    expect(fake.requests).toHaveLength(2);

    await vi.advanceTimersByTimeAsync(1_999);
    expect(fake.requests).toHaveLength(2);
    await vi.advanceTimersByTimeAsync(1);
    expect(fake.requests).toHaveLength(3);

    await vi.advanceTimersByTimeAsync(3_999);
    expect(fake.requests).toHaveLength(3);
    await vi.advanceTimersByTimeAsync(1);
    expect(fake.requests).toHaveLength(4);
  });

  it('never waits longer than the cap', async () => {
    const fake = createFakeSseFetch();
    for (let i = 0; i < 8; i++) fake.enqueueNetworkError();
    connect(fake);
    await flush();
    await vi.advanceTimersByTimeAsync(1_000 + 2_000 + 4_000);
    expect(fake.requests).toHaveLength(4);

    await vi.advanceTimersByTimeAsync(4_999);
    expect(fake.requests).toHaveLength(4);
    await vi.advanceTimersByTimeAsync(1);
    expect(fake.requests).toHaveLength(5);

    await vi.advanceTimersByTimeAsync(5_000);
    expect(fake.requests).toHaveLength(6);
  });

  it('adds jitter: the wait lies between half and all of the ceiling', async () => {
    const fake = createFakeSseFetch();
    fake.enqueueNetworkError();
    fake.enqueueNetworkError();
    connect(fake, { random: () => 0 });
    await flush();

    await vi.advanceTimersByTimeAsync(499);
    expect(fake.requests).toHaveLength(1);
    await vi.advanceTimersByTimeAsync(1);
    expect(fake.requests).toHaveLength(2);
  });

  it('starts the schedule over after a connection opens', async () => {
    const fake = createFakeSseFetch();
    fake.enqueueNetworkError();
    fake.enqueueNetworkError();
    fake.enqueue({ body: sseEvent('1-0', 'metric', {}) });
    fake.enqueueNetworkError();
    fake.enqueueNetworkError();
    connect(fake);

    await flush();
    await vi.advanceTimersByTimeAsync(1_000 + 2_000);
    expect(fake.requests).toHaveLength(3);

    await vi.advanceTimersByTimeAsync(999);
    expect(fake.requests).toHaveLength(3);
    await vi.advanceTimersByTimeAsync(1);
    expect(fake.requests).toHaveLength(4);
    await vi.advanceTimersByTimeAsync(1_999);
    expect(fake.requests).toHaveLength(4);
    await vi.advanceTimersByTimeAsync(1);
    expect(fake.requests).toHaveLength(5);
  });
});

describe('openSseConnection: resuming', () => {
  it('carries the last event id into a reconnect after the server closes the stream', async () => {
    const fake = createFakeSseFetch();
    fake.enqueue({ body: sseEvent('1-0', 'metric', {}) + sseEvent('2-0', 'metric', {}) });
    fake.enqueue({ keepOpen: true });
    const { connection } = connect(fake);

    await flush();
    await vi.advanceTimersByTimeAsync(1_000);

    expect(fake.requests[0]?.headers.has('last-event-id')).toBe(false);
    expect(fake.requests[1]?.headers.get('last-event-id')).toBe('2-0');
    expect(connection.getLastEventId()).toBe('2-0');
  });

  it('keeps the id through failed attempts in between', async () => {
    const fake = createFakeSseFetch();
    fake.enqueue({ body: sseEvent('5-0', 'metric', {}) });
    fake.enqueueNetworkError();
    fake.enqueue({ status: 503, contentType: 'text/plain', body: 'busy' });
    fake.enqueue({ keepOpen: true });
    connect(fake);

    await flush();
    await vi.advanceTimersByTimeAsync(1_000 + 2_000 + 4_000);

    expect(fake.requests).toHaveLength(4);
    expect(fake.requests.slice(1).map((r) => r.headers.get('last-event-id'))).toEqual(['5-0', '5-0', '5-0']);
  });

  it('builds fresh headers for every attempt, so a renewed token is used', async () => {
    const fake = createFakeSseFetch();
    fake.enqueueNetworkError();
    fake.enqueue({ keepOpen: true });
    let n = 0;
    connect(fake, { getHeaders: () => ({ Authorization: `Bearer token-${++n}` }) });

    await flush();
    await vi.advanceTimersByTimeAsync(1_000);
    expect(fake.requests.map((r) => r.headers.get('authorization'))).toEqual(['Bearer token-1', 'Bearer token-2']);
  });
});

describe('openSseConnection: state', () => {
  it('goes connecting, open, retrying, open when the server drops and returns', async () => {
    const fake = createFakeSseFetch();
    fake.enqueue({ body: sseEvent('1-0', 'metric', {}) });
    fake.enqueue({ keepOpen: true });
    const { states } = connect(fake);

    expect(states).toEqual([]);
    await flush();
    await vi.advanceTimersByTimeAsync(1_000);
    expect(states).toEqual(['open', 'retrying', 'open']);
  });

  it('names the failure while retrying', async () => {
    const fake = createFakeSseFetch();
    fake.enqueueNetworkError(new TypeError('Failed to fetch'));
    const { errors } = connect(fake);
    await flush();
    expect(errors.at(-1)?.message).toBe('Failed to fetch');
  });

  it('treats a stalled connection as dead and retries', async () => {
    const fake = createFakeSseFetch();
    fake.enqueue({ keepOpen: true });
    fake.enqueue({ keepOpen: true });
    const { states } = connect(fake);
    await flush();

    await vi.advanceTimersByTimeAsync(45_000);
    expect(states).toEqual(['open', 'retrying']);
    await vi.advanceTimersByTimeAsync(1_000);
    expect(fake.requests).toHaveLength(2);
  });

  it('retries a server error', async () => {
    const fake = createFakeSseFetch();
    fake.enqueue({ status: 503, contentType: 'application/problem+json', body: '{}' });
    fake.enqueue({ keepOpen: true });
    const { states } = connect(fake);
    await flush();
    await vi.advanceTimersByTimeAsync(1_000);
    expect(states).toEqual(['retrying', 'open']);
  });

  it('stops for good on a client error and reports it', async () => {
    const fake = createFakeSseFetch();
    fake.enqueue({ status: 400, contentType: 'application/problem+json', body: '{"title":"bad id"}' });
    const { states, errors } = connect(fake);
    await flush();
    await vi.advanceTimersByTimeAsync(120_000);

    expect(fake.requests).toHaveLength(1);
    expect(states).toEqual(['closed']);
    expect(errors.at(-1)).toMatchObject({ status: 400 });
  });
});

describe('openSseConnection: close', () => {
  it('cancels a pending reconnect', async () => {
    const fake = createFakeSseFetch();
    fake.enqueueNetworkError();
    fake.enqueue({ keepOpen: true });
    const { connection, states } = connect(fake);
    await flush();
    expect(states).toEqual(['retrying']);

    connection.close();
    await vi.advanceTimersByTimeAsync(60_000);
    expect(fake.requests).toHaveLength(1);
    expect(states).toEqual(['retrying', 'closed']);
    expect(vi.getTimerCount()).toBe(0);
  });

  it('aborts an open stream and does not reconnect', async () => {
    const fake = createFakeSseFetch();
    fake.enqueue({ keepOpen: true });
    fake.enqueue({ keepOpen: true });
    const { connection, states } = connect(fake);
    await flush();

    connection.close();
    await vi.advanceTimersByTimeAsync(60_000);
    expect(fake.requests).toHaveLength(1);
    expect(states).toEqual(['open', 'closed']);
    expect(vi.getTimerCount()).toBe(0);
  });

  it('is safe to call twice', async () => {
    const fake = createFakeSseFetch();
    fake.enqueue({ keepOpen: true });
    const { connection, states } = connect(fake);
    await flush();
    connection.close();
    connection.close();
    expect(states).toEqual(['open', 'closed']);
  });
});
