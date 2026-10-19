import { act, screen, within } from '@testing-library/react';
import { Profiler } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createDisabledAuth } from '../../auth/oidcAuth';
import { createFakeSseFetch, sseEvent } from '../../test/fakeSseFetch';
import { renderWithServices, testServices } from '../../test/renderWithServices';
import { MetricsView } from './MetricsView';

beforeEach(() => vi.useFakeTimers());
afterEach(() => vi.useRealTimers());

const settle = (ms = 0) => act(() => vi.advanceTimersByTimeAsync(ms));

const metric = (over: Record<string, unknown> = {}) => ({
  titleId: 'tt-1',
  region: 'eu-west-1',
  metric: 'CONCURRENT_VIEWERS',
  windowStart: '2026-10-12T10:00:00Z',
  windowEnd: '2026-10-12T10:00:05Z',
  value: 100,
  sampleCount: 10,
  computedAt: '2026-10-12T10:00:06Z',
  ...over,
});

const at = (second: number) => `2026-10-12T10:00:${String(second).padStart(2, '0')}Z`;

describe('MetricsView', () => {
  it('connects to the metric stream for the tenant and asks for the two live metrics', async () => {
    const fake = createFakeSseFetch();
    fake.enqueue({ keepOpen: true });
    renderWithServices(<MetricsView tenantId="acme corp" />, testServices({ fetch: fake.fetch }));
    await settle();

    const url = new URL(fake.requests[0]!.url);
    expect(url.pathname).toBe('/api/query/v1/stream/metrics');
    expect(url.searchParams.get('tenantId')).toBe('acme corp');
    expect(url.searchParams.get('metrics')).toBe('CONCURRENT_VIEWERS,ERROR_RATE');
  });

  it('draws one chart per series, grouped under its metric', async () => {
    const fake = createFakeSseFetch();
    fake.enqueue({
      keepOpen: true,
      body:
        sseEvent('1-0', 'metric', metric()) +
        sseEvent('2-0', 'metric', metric({ region: 'us-east-1' })) +
        sseEvent('3-0', 'metric', metric({ metric: 'ERROR_RATE', value: 0.02 })),
    });
    renderWithServices(<MetricsView tenantId="t" />, testServices({ fetch: fake.fetch }));
    await settle(250);

    const viewers = screen.getByRole('region', { name: 'Concurrent viewers' });
    expect(within(viewers).getAllByRole('figure')).toHaveLength(2);
    const errors = screen.getByRole('region', { name: 'Error rate' });
    expect(within(errors).getAllByRole('figure')).toHaveLength(1);
  });

  it('does not render per event: fifty events become one render after the refresh interval', async () => {
    const fake = createFakeSseFetch();
    const handle = fake.enqueue({ keepOpen: true });
    const commits = vi.fn();
    renderWithServices(
      <Profiler id="metrics" onRender={commits}>
        <MetricsView tenantId="t" />
      </Profiler>,
      testServices({ fetch: fake.fetch }),
    );
    await settle();
    const before = commits.mock.calls.length;

    for (let i = 0; i < 50; i++) {
      handle.send(sseEvent(`${i}-0`, 'metric', metric({ windowStart: at(i), value: i })));
    }
    await settle(100);
    expect(commits.mock.calls.length).toBe(before);

    await settle(150);
    expect(commits.mock.calls.length).toBe(before + 1);
    expect(screen.getByText('49', { selector: '.chart-latest' })).toBeInTheDocument();
  });

  it('keeps every update of a burst, not just the first', async () => {
    const fake = createFakeSseFetch();
    fake.enqueue({
      keepOpen: true,
      body: [1, 2, 3].map((n) => sseEvent(`${n}-0`, 'metric', metric({ windowStart: at(n), value: n * 10 }))).join(''),
    });
    renderWithServices(<MetricsView tenantId="t" />, testServices({ fetch: fake.fetch }));
    await settle(250);
    expect(screen.getByText(/3 points held/)).toBeInTheDocument();
    expect(screen.getByText('30', { selector: '.chart-latest' })).toBeInTheDocument();
  });

  it('ignores a malformed event and a metric outside the live set, and keeps streaming', async () => {
    const fake = createFakeSseFetch();
    const handle = fake.enqueue({ keepOpen: true });
    renderWithServices(<MetricsView tenantId="t" />, testServices({ fetch: fake.fetch }));
    await settle();

    handle.send('id: 1-0\nevent: metric\ndata: {nope\n\n');
    handle.send(sseEvent('2-0', 'metric', metric({ metric: 'REBUFFER_RATIO' })));
    handle.send(sseEvent('3-0', 'something-else', metric()));
    await settle(250);
    expect(screen.queryAllByRole('figure')).toHaveLength(0);
    expect(screen.getByText(/connection: open/i)).toBeInTheDocument();

    handle.send(sseEvent('4-0', 'metric', metric({ value: 7 })));
    await settle(250);
    expect(screen.getAllByRole('figure')).toHaveLength(1);
  });

  it('says in words what the connection is doing', async () => {
    const fake = createFakeSseFetch();
    fake.enqueue({ body: sseEvent('1-0', 'metric', metric()) });
    fake.enqueue({ keepOpen: true });
    renderWithServices(<MetricsView tenantId="t" />, testServices({ fetch: fake.fetch }));

    expect(screen.getByText(/connection: connecting/i)).toBeInTheDocument();
    await settle();
    expect(screen.getByText(/connection: retrying/i)).toBeInTheDocument();
    await settle(30_000);
    expect(screen.getByText(/connection: open/i)).toBeInTheDocument();
  });

  it('resumes after a drop with the last event id, and the chart keeps its points', async () => {
    const fake = createFakeSseFetch();
    fake.enqueue({ body: sseEvent('7-0', 'metric', metric({ windowStart: at(1) })) });
    fake.enqueue({ keepOpen: true, body: sseEvent('8-0', 'metric', metric({ windowStart: at(2) })) });
    renderWithServices(<MetricsView tenantId="t" />, testServices({ fetch: fake.fetch }));
    await settle(30_000);

    expect(fake.requests[1]!.headers.get('last-event-id')).toBe('7-0');
    expect(screen.getByText(/2 points held/)).toBeInTheDocument();
  });

  it('switching tenant clears the charts and opens a fresh stream with no resume id', async () => {
    const fake = createFakeSseFetch();
    fake.enqueue({ keepOpen: true, body: sseEvent('9-0', 'metric', metric()) });
    fake.enqueue({ keepOpen: true });
    const services = testServices({ fetch: fake.fetch });
    const { rerenderWithServices } = renderWithServices(<MetricsView tenantId="a" />, services);
    await settle(250);
    expect(screen.getAllByRole('figure')).toHaveLength(1);

    rerenderWithServices(<MetricsView tenantId="b" />);
    await settle(250);

    expect(screen.queryAllByRole('figure')).toHaveLength(0);
    expect(new URL(fake.requests[1]!.url).searchParams.get('tenantId')).toBe('b');
    expect(fake.requests[1]!.headers.has('last-event-id')).toBe(false);
  });

  it('shows why the stream stopped when the server refuses it for good', async () => {
    const fake = createFakeSseFetch();
    fake.enqueue({ status: 400, contentType: 'application/problem+json', body: '{"title":"Missing tenantId"}' });
    renderWithServices(<MetricsView tenantId="" />, testServices({ fetch: fake.fetch }));
    await settle();
    expect(screen.getByRole('alert')).toHaveTextContent(/stream stopped.*HTTP 400/i);
  });

  it('attaches the access token to the stream request', async () => {
    const fake = createFakeSseFetch();
    fake.enqueue({ keepOpen: true });
    const auth = { ...createDisabledAuth(), getAccessToken: async () => 'tok-1' };
    renderWithServices(<MetricsView tenantId="t" />, testServices({ fetch: fake.fetch, auth }));
    await settle();
    expect(fake.requests[0]!.headers.get('authorization')).toBe('Bearer tok-1');
  });

  it('closes the stream when the view goes away', async () => {
    const fake = createFakeSseFetch();
    fake.enqueue({ keepOpen: true });
    const { unmount } = renderWithServices(<MetricsView tenantId="t" />, testServices({ fetch: fake.fetch }));
    await settle();
    unmount();
    await settle(60_000);
    expect(fake.requests).toHaveLength(1);
    expect(vi.getTimerCount()).toBe(0);
  });
});
