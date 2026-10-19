import { vi } from 'vitest';
import { createFakeSseFetch } from './fakeSseFetch';

type Json = { status?: number; body?: unknown; contentType?: string; throws?: Error; gate?: Promise<void>; match?: string };

// Stream URLs go to the scripted event-stream fake; everything else is answered from a queue of JSON replies.
export function createRoutedFetch() {
  const sse = createFakeSseFetch();
  const replies: Json[] = [];
  const jsonRequests: Array<{ url: string; headers: Headers }> = [];

  const fetchFn = vi.fn(async (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
    const url = String(input instanceof Request ? input.url : input);
    if (url.includes('/stream')) return sse.fetch(input, init);
    jsonRequests.push({ url, headers: new Headers(init?.headers) });
    const index = replies.findIndex((r) => !r.match || url.includes(r.match));
    const reply = index === -1 ? undefined : replies.splice(index, 1)[0];
    if (!reply) throw new TypeError(`routed fetch: no reply queued for ${url}`);
    await reply.gate;
    if (reply.throws) throw reply.throws;
    return new Response(reply.body === undefined ? null : JSON.stringify(reply.body), {
      status: reply.status ?? 200,
      headers: { 'content-type': reply.contentType ?? 'application/json' },
    });
  });

  return {
    fetch: fetchFn as unknown as typeof fetch,
    sse,
    jsonRequests,
    reply: (r: Json) => replies.push(r),
  };
}
