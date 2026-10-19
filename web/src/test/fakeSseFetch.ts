import { vi } from 'vitest';

export interface ScriptedResponse {
  status?: number;
  contentType?: string;
  body?: string;
  keepOpen?: boolean;
}

export interface StreamHandle {
  send(chunk: string | Uint8Array): void;
  end(): void;
}

export interface RecordedRequest {
  url: string;
  headers: Headers;
}

interface Queued {
  status: number;
  contentType: string;
  stream: ReadableStream<Uint8Array>;
  abort(reason: unknown): void;
  networkError?: Error;
}

const encoder = new TextEncoder();

// A scripted fetch: each call takes the next queued response, so a test reads as a timeline.
export function createFakeSseFetch() {
  const queue: Queued[] = [];
  const requests: RecordedRequest[] = [];

  const fetchFn = vi.fn(async (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
    requests.push({ url: String(input), headers: new Headers(init?.headers) });
    const next = queue.shift();
    if (!next) throw new TypeError('fake fetch: nothing queued');
    if (next.networkError) throw next.networkError;

    init?.signal?.addEventListener('abort', () => next.abort(init.signal?.reason ?? new DOMException('Aborted', 'AbortError')));
    return new Response(next.stream, { status: next.status, headers: { 'content-type': next.contentType } });
  });

  function enqueue(script: ScriptedResponse = {}): StreamHandle {
    let controller!: ReadableStreamDefaultController<Uint8Array>;
    const stream = new ReadableStream<Uint8Array>({
      start(c) {
        controller = c;
        if (script.body) c.enqueue(encoder.encode(script.body));
        if (!script.keepOpen) c.close();
      },
    });
    queue.push({
      status: script.status ?? 200,
      contentType: script.contentType ?? 'text/event-stream',
      stream,
      abort: (reason) => {
        try {
          controller.error(reason);
        } catch {
          // stream already closed
        }
      },
    });
    return {
      send: (chunk) => controller.enqueue(typeof chunk === 'string' ? encoder.encode(chunk) : chunk),
      end: () => controller.close(),
    };
  }

  function enqueueNetworkError(error = new TypeError('Failed to fetch')) {
    queue.push({ status: 0, contentType: '', stream: new ReadableStream(), abort: () => {}, networkError: error });
  }

  return { fetch: fetchFn as unknown as typeof fetch, fetchMock: fetchFn, requests, enqueue, enqueueNetworkError };
}

export const sseEvent = (id: string, event: string, data: unknown) =>
  `id: ${id}\nevent: ${event}\ndata: ${JSON.stringify(data)}\n\n`;
