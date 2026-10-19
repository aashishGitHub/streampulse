import { SseParser, type SseMessage } from './parser';

export class SseHttpError extends Error {
  constructor(
    readonly status: number,
    readonly detail: string,
  ) {
    super(`Stream request failed with HTTP ${status}`);
    this.name = 'SseHttpError';
  }
}

export class SseContentTypeError extends Error {
  constructor(readonly contentType: string) {
    super(`Expected text/event-stream but received "${contentType}"`);
    this.name = 'SseContentTypeError';
  }
}

export class SseIdleTimeoutError extends Error {
  constructor(readonly idleMs: number) {
    super(`No data or heartbeat for ${idleMs} ms`);
    this.name = 'SseIdleTimeoutError';
  }
}

// 408 and 429 are the client errors that say "try later"; every other 4xx repeats on retry.
export function isFatalSseError(error: unknown): boolean {
  if (error instanceof SseContentTypeError) return true;
  if (error instanceof SseHttpError) {
    return error.status >= 400 && error.status < 500 && error.status !== 408 && error.status !== 429;
  }
  return false;
}

export interface StreamSseOptions {
  url: string;
  fetch: typeof fetch;
  signal: AbortSignal;
  idleTimeoutMs: number;
  headers?: Record<string, string>;
  lastEventId?: string | null;
  onOpen: () => void;
  onMessage: (message: SseMessage) => void;
}

// Resolves when the server ends the stream; rejects on every other way a stream can end.
// Not the browser's EventSource: that cannot set Authorization, and a fresh EventSource
// does not carry Last-Event-ID, so resuming after a failure needs a reader we control (ADR-0018).
export async function streamSse(options: StreamSseOptions): Promise<void> {
  const { url, signal, idleTimeoutMs } = options;
  const request = new AbortController();
  const forwardAbort = () => request.abort(signal.reason);
  if (signal.aborted) forwardAbort();
  signal.addEventListener('abort', forwardAbort, { once: true });

  // Also bounds the wait for response headers, so a hung connect is not a silent hang.
  let timedOut = false;
  let idleTimer: ReturnType<typeof setTimeout> | undefined;
  const armIdleTimer = () => {
    clearTimeout(idleTimer);
    idleTimer = setTimeout(() => {
      timedOut = true;
      request.abort();
    }, idleTimeoutMs);
  };

  try {
    armIdleTimer();
    const headers: Record<string, string> = { Accept: 'text/event-stream', ...options.headers };
    if (options.lastEventId) headers['Last-Event-ID'] = options.lastEventId;

    const response = await options.fetch(url, { headers, signal: request.signal, cache: 'no-store' });
    if (!response.ok) throw new SseHttpError(response.status, await response.text().catch(() => ''));
    const contentType = response.headers.get('content-type') ?? '';
    if (!contentType.includes('text/event-stream')) throw new SseContentTypeError(contentType);
    if (!response.body) throw new Error('The response has no body to read');

    options.onOpen();
    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    const parser = new SseParser();
    for (;;) {
      const { done, value } = await reader.read();
      if (done) return;
      armIdleTimer();
      for (const message of parser.push(decoder.decode(value, { stream: true }))) options.onMessage(message);
    }
  } catch (error) {
    if (timedOut) throw new SseIdleTimeoutError(idleTimeoutMs);
    throw error;
  } finally {
    clearTimeout(idleTimer);
    signal.removeEventListener('abort', forwardAbort);
    request.abort();
  }
}
