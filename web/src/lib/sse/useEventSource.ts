import { useEffect, useRef, useState } from 'react';
import { openSseConnection, type ConnectionState, type SseConnection } from './connection';
import type { SseMessage } from './parser';
import type { BackoffConfig } from './backoff';

export interface UseEventSourceOptions {
  /** `null` keeps the hook idle. A different url is a different stream: it starts fresh. */
  url: string | null;
  fetch: typeof fetch;
  onMessage: (message: SseMessage) => void;
  onStateChange?: (state: ConnectionState, error: Error | null) => void;
  getHeaders?: () => Record<string, string> | Promise<Record<string, string>>;
  backoff?: BackoffConfig;
  idleTimeoutMs?: number;
  random?: () => number;
}

export interface UseEventSourceResult {
  state: ConnectionState;
  error: Error | null;
  /** A getter, not state: a render per event would defeat the batching the consumers do. */
  getLastEventId: () => string | null;
}

interface Snapshot {
  url: string | null;
  state: ConnectionState;
  error: Error | null;
}

export function useEventSource(options: UseEventSourceOptions): UseEventSourceResult {
  const { url, fetch: fetchImpl } = options;
  const latest = useRef(options);
  const connection = useRef<SseConnection | null>(null);
  const [snapshot, setSnapshot] = useState<Snapshot>({ url, state: 'connecting', error: null });

  useEffect(() => {
    latest.current = options;
  });

  useEffect(() => {
    if (url === null) return;
    let active = true;
    const opened = openSseConnection({
      url,
      fetch: fetchImpl,
      backoff: latest.current.backoff,
      idleTimeoutMs: latest.current.idleTimeoutMs,
      random: latest.current.random,
      getHeaders: () => latest.current.getHeaders?.() ?? {},
      onMessage: (message) => latest.current.onMessage(message),
      onStateChange: (state, error) => {
        if (!active) return;
        setSnapshot({ url, state, error });
        latest.current.onStateChange?.(state, error);
      },
    });
    connection.current = opened;
    return () => {
      active = false;
      opened.close();
      connection.current = null;
    };
  }, [url, fetchImpl]);

  const current: Snapshot =
    url === null
      ? { url, state: 'closed', error: null }
      : snapshot.url === url
        ? snapshot
        : { url, state: 'connecting', error: null };

  return {
    state: current.state,
    error: current.error,
    getLastEventId: () => connection.current?.getLastEventId() ?? null,
  };
}
