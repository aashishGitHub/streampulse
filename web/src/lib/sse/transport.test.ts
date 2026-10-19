// @vitest-environment node
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createFakeSseFetch, sseEvent } from '../../test/fakeSseFetch';
import { SseContentTypeError, SseHttpError, SseIdleTimeoutError, isFatalSseError, streamSse } from './transport';
import type { SseMessage } from './parser';

beforeEach(() => vi.useFakeTimers());
afterEach(() => vi.useRealTimers());

function run(fake: ReturnType<typeof createFakeSseFetch>, overrides: Partial<Parameters<typeof streamSse>[0]> = {}) {
  const messages: SseMessage[] = [];
  const onOpen = vi.fn();
  const controller = new AbortController();
  const promise = streamSse({
    url: '/api/query/v1/stream/metrics?tenantId=t',
    fetch: fake.fetch,
    signal: controller.signal,
    idleTimeoutMs: 45_000,
    onOpen,
    onMessage: (m) => messages.push(m),
    ...overrides,
  });
  return { promise, messages, onOpen, controller };
}

describe('streamSse', () => {
  it('asks for an event stream and sends the Last-Event-ID header itself', async () => {
    const fake = createFakeSseFetch();
    fake.enqueue();
    await run(fake, { lastEventId: '1791822805866-0' }).promise;

    const [request] = fake.requests;
    expect(request?.headers.get('accept')).toBe('text/event-stream');
    expect(request?.headers.get('last-event-id')).toBe('1791822805866-0');
  });

  it('omits Last-Event-ID on a first connection', async () => {
    const fake = createFakeSseFetch();
    fake.enqueue();
    await run(fake, { lastEventId: null }).promise;
    expect(fake.requests[0]?.headers.has('last-event-id')).toBe(false);
  });

  it('passes caller headers such as Authorization', async () => {
    const fake = createFakeSseFetch();
    fake.enqueue();
    await run(fake, { headers: { Authorization: 'Bearer abc' } }).promise;
    expect(fake.requests[0]?.headers.get('authorization')).toBe('Bearer abc');
  });

  it('reports open once headers are accepted, then delivers parsed messages', async () => {
    const fake = createFakeSseFetch();
    fake.enqueue({ body: sseEvent('1-0', 'metric', { value: 1 }) + ': hb\n\n' + sseEvent('2-0', 'metric', { value: 2 }) });
    const { promise, messages, onOpen } = run(fake);
    await promise;

    expect(onOpen).toHaveBeenCalledTimes(1);
    expect(messages.map((m) => m.id)).toEqual(['1-0', '2-0']);
  });

  it('decodes a multi-byte character that is split across two network chunks', async () => {
    const fake = createFakeSseFetch();
    const handle = fake.enqueue({ keepOpen: true });
    const { promise, messages } = run(fake);
    const bytes = new TextEncoder().encode('data: caf\u00e9\n\n');
    const split = bytes.indexOf(0xc3) + 1;

    handle.send(bytes.slice(0, split));
    handle.send(bytes.slice(split));
    handle.end();
    await promise;
    expect(messages[0]?.data).toBe('caf\u00e9');
  });

  it('resolves when the server ends the stream', async () => {
    const fake = createFakeSseFetch();
    fake.enqueue({ body: ': hb\n\n' });
    await expect(run(fake).promise).resolves.toBeUndefined();
  });

  it('rejects a non-2xx answer with its status and body text', async () => {
    const fake = createFakeSseFetch();
    fake.enqueue({ status: 400, contentType: 'application/problem+json', body: '{"title":"Bad Last-Event-ID"}' });
    const { promise, onOpen } = run(fake);

    await expect(promise).rejects.toMatchObject({ status: 400, detail: '{"title":"Bad Last-Event-ID"}' });
    await expect(promise).rejects.toBeInstanceOf(SseHttpError);
    expect(onOpen).not.toHaveBeenCalled();
  });

  it('rejects a 200 that is not an event stream, such as a proxy falling back to an HTML page', async () => {
    const fake = createFakeSseFetch();
    fake.enqueue({ contentType: 'text/html', body: '<html></html>' });
    await expect(run(fake).promise).rejects.toBeInstanceOf(SseContentTypeError);
  });

  it('gives up on a connection that goes quiet for longer than the idle timeout', async () => {
    const fake = createFakeSseFetch();
    fake.enqueue({ keepOpen: true, body: ': hb\n\n' });
    const { promise } = run(fake, { idleTimeoutMs: 45_000 });
    const settled = promise.catch((e: unknown) => e);

    await vi.advanceTimersByTimeAsync(44_999);
    expect(vi.getTimerCount()).toBe(1);
    await vi.advanceTimersByTimeAsync(2);
    expect(await settled).toBeInstanceOf(SseIdleTimeoutError);
  });

  it('counts a heartbeat as activity, so a healthy quiet stream is not cut', async () => {
    const fake = createFakeSseFetch();
    const handle = fake.enqueue({ keepOpen: true });
    const { promise } = run(fake, { idleTimeoutMs: 45_000 });
    const settled = vi.fn();
    promise.then(settled, settled);

    for (let i = 0; i < 4; i++) {
      await vi.advanceTimersByTimeAsync(30_000);
      handle.send(': hb\n\n');
    }
    expect(settled).not.toHaveBeenCalled();
    handle.end();
    await promise;
  });

  it('stops when the caller aborts', async () => {
    const fake = createFakeSseFetch();
    fake.enqueue({ keepOpen: true });
    const { promise, controller } = run(fake);
    await vi.advanceTimersByTimeAsync(0);
    controller.abort();
    await expect(promise).rejects.toMatchObject({ name: 'AbortError' });
    expect(vi.getTimerCount()).toBe(0);
  });
});

describe('isFatalSseError', () => {
  it.each([400, 401, 403, 404, 422])('treats %i as final: the same request would be refused again', (status) => {
    expect(isFatalSseError(new SseHttpError(status, ''))).toBe(true);
  });

  it.each([408, 429, 500, 502, 503])('treats %i as worth retrying', (status) => {
    expect(isFatalSseError(new SseHttpError(status, ''))).toBe(false);
  });

  it('treats a wrong content type as final and a network failure as retryable', () => {
    expect(isFatalSseError(new SseContentTypeError('text/html'))).toBe(true);
    expect(isFatalSseError(new TypeError('Failed to fetch'))).toBe(false);
    expect(isFatalSseError(new SseIdleTimeoutError(1))).toBe(false);
  });
});
