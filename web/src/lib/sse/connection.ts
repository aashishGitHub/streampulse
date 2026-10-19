import { backoffDelay, DEFAULT_BACKOFF, type BackoffConfig } from './backoff';
import type { SseMessage } from './parser';
import { isFatalSseError, streamSse } from './transport';

export type ConnectionState = 'connecting' | 'open' | 'retrying' | 'closed';

export interface SseConnectionOptions {
  url: string;
  fetch: typeof fetch;
  onMessage: (message: SseMessage) => void;
  onStateChange?: (state: ConnectionState, error: Error | null) => void;
  getHeaders?: () => Record<string, string> | Promise<Record<string, string>>;
  backoff?: BackoffConfig;
  idleTimeoutMs?: number;
  random?: () => number;
}

export interface SseConnection {
  close(): void;
  getLastEventId(): string | null;
}

// 3 x the 15 s heartbeat the query API documents; a target, not a measurement.
export const DEFAULT_IDLE_TIMEOUT_MS = 45_000;

const toError = (value: unknown): Error => (value instanceof Error ? value : new Error(String(value)));

function sleep(ms: number, signal: AbortSignal): Promise<void> {
  return new Promise((resolve) => {
    const timer = setTimeout(done, ms);
    function done() {
      clearTimeout(timer);
      signal.removeEventListener('abort', done);
      resolve();
    }
    signal.addEventListener('abort', done, { once: true });
  });
}

// Owns the reconnect loop and the resume position. One instance is one stream URL for its whole
// life: the last event id never leaks to a different URL because a new URL is a new connection.
export function openSseConnection(options: SseConnectionOptions): SseConnection {
  const { backoff = DEFAULT_BACKOFF, idleTimeoutMs = DEFAULT_IDLE_TIMEOUT_MS, random = Math.random } = options;
  const lifetime = new AbortController();
  let lastEventId: string | null = null;
  let state: ConnectionState = 'connecting';

  const setState = (next: ConnectionState, error: Error | null = null) => {
    state = next;
    options.onStateChange?.(next, error);
  };

  async function run() {
    let attempt = 0;
    while (!lifetime.signal.aborted) {
      let failure: Error | null = null;
      try {
        await streamSse({
          url: options.url,
          fetch: options.fetch,
          signal: lifetime.signal,
          idleTimeoutMs,
          headers: await options.getHeaders?.(),
          lastEventId,
          onOpen: () => {
            attempt = 0;
            setState('open');
          },
          onMessage: (message) => {
            if (message.id) lastEventId = message.id;
            options.onMessage(message);
          },
        });
      } catch (error) {
        if (lifetime.signal.aborted) return;
        if (isFatalSseError(error)) {
          setState('closed', toError(error));
          return;
        }
        failure = toError(error);
      }
      if (lifetime.signal.aborted) return;
      setState('retrying', failure);
      await sleep(backoffDelay(attempt, backoff, random), lifetime.signal);
      attempt += 1;
    }
  }

  void run();

  return {
    close() {
      if (state === 'closed') return;
      lifetime.abort();
      setState('closed');
    },
    getLastEventId: () => lastEventId,
  };
}
