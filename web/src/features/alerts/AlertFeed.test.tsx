import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { createDisabledAuth } from '../../auth/oidcAuth';
import { sseEvent } from '../../test/fakeSseFetch';
import { renderWithServices, testServices } from '../../test/renderWithServices';
import { createRoutedFetch } from '../../test/routedFetch';
import { AlertFeed, AlertList } from './AlertFeed';
import type { Alert } from './types';

const alert = (over: Partial<Alert> = {}): Alert => ({
  alertId: 'a1',
  tenantId: 'streamflix',
  ruleId: 'r1',
  series: 'ERROR_RATE/tt-1/eu',
  state: 'FIRING',
  severity: 'CRITICAL',
  summary: 'Error rate above 5%',
  firedAt: '2026-10-12T10:00:00Z',
  resolvedAt: null,
  suppressedBy: null,
  ...over,
});

describe('AlertList', () => {
  it('labels every state in words and with a symbol, not by colour alone', () => {
    render(
      <AlertList
        alerts={[
          alert({ alertId: 'f', state: 'FIRING' }),
          alert({ alertId: 's', state: 'SUPPRESSED', suppressedBy: 'f' }),
          alert({ alertId: 'r', state: 'RESOLVED', resolvedAt: '2026-10-12T10:05:00Z' }),
        ]}
      />,
    );
    const [firing, suppressed, resolved] = screen.getAllByRole('listitem');
    expect(firing).toHaveTextContent('▲ FIRING');
    expect(suppressed).toHaveTextContent('⊘ SUPPRESSED');
    expect(resolved).toHaveTextContent('✓ RESOLVED');
    expect(new Set([firing, suppressed, resolved].map((li) => li!.className)).size).toBe(3);
  });

  it('shows severity as text', () => {
    render(<AlertList alerts={[alert({ severity: 'WARNING' })]} />);
    expect(screen.getByText('Severity: WARNING')).toBeInTheDocument();
  });

  it('names the root cause of a suppressed alert by its summary when that alert is in the list', () => {
    render(
      <AlertList
        alerts={[
          alert({ alertId: 'cdn', summary: 'CDN origin unreachable' }),
          alert({ alertId: 'player', state: 'SUPPRESSED', suppressedBy: 'cdn', summary: 'Player errors high' }),
        ]}
      />,
    );
    const suppressed = screen.getByText('Player errors high').closest('li')!;
    expect(within(suppressed).getByText(/root cause/i).parentElement).toHaveTextContent('CDN origin unreachable (cdn)');
  });

  it('falls back to the raw suppressor id when the root cause is not in the list', () => {
    render(<AlertList alerts={[alert({ state: 'SUPPRESSED', suppressedBy: 'rule-cdn' })]} />);
    expect(screen.getByText(/root cause/i).parentElement).toHaveTextContent('rule-cdn');
  });

  it('says so when a suppressed alert reports no cause', () => {
    render(<AlertList alerts={[alert({ state: 'SUPPRESSED' })]} />);
    expect(screen.getByText(/root cause/i).parentElement).toHaveTextContent('not reported');
  });

  it('shows the resolution time of a resolved alert', () => {
    render(<AlertList alerts={[alert({ state: 'RESOLVED', resolvedAt: '2026-10-12T10:05:00Z' })]} />);
    expect(screen.getByText('10:05:00 UTC')).toHaveAttribute('datetime', '2026-10-12T10:05:00Z');
  });
});

describe('AlertFeed', () => {
  it('lists the alerts from the server, firing first, and counts them', async () => {
    const routed = createRoutedFetch();
    routed.sse.enqueue({ keepOpen: true });
    routed.reply({
      body: [alert({ alertId: 'r', state: 'SUPPRESSED', suppressedBy: 'f', summary: 'Old one' }), alert({ alertId: 'f', summary: 'Hot one' })],
    });
    renderWithServices(<AlertFeed tenantId="streamflix" />, testServices({ fetch: routed.fetch }));

    const items = await screen.findAllByRole('listitem');
    expect(items[0]).toHaveTextContent('Hot one');
    expect(items[1]).toHaveTextContent('Old one');
    expect(screen.getByText(/1 firing · 1 suppressed · 0 resolved/)).toBeInTheDocument();
    expect(routed.jsonRequests[0]!.url).toBe('http://test.local/api/alerts/v1/alerts?tenantId=streamflix');
  });

  it('applies a live alert event', async () => {
    const routed = createRoutedFetch();
    const stream = routed.sse.enqueue({ keepOpen: true });
    routed.reply({ body: [alert({ summary: 'Before' })] });
    renderWithServices(<AlertFeed tenantId="streamflix" />, testServices({ fetch: routed.fetch }));
    await screen.findByText('Before');

    act(() => stream.send(sseEvent('1', 'alert', alert({ alertId: 'a1', state: 'RESOLVED', summary: 'Before' }))));
    await waitFor(() => expect(screen.getByRole('listitem')).toHaveTextContent('✓ RESOLVED'));

    act(() => stream.send(sseEvent('2', 'alert', alert({ alertId: 'new', summary: 'Brand new' }))));
    expect(await screen.findByText('Brand new')).toBeInTheDocument();
  });

  it('ignores a malformed alert event', async () => {
    const routed = createRoutedFetch();
    const stream = routed.sse.enqueue({ keepOpen: true });
    routed.reply({ body: [] });
    renderWithServices(<AlertFeed tenantId="t" />, testServices({ fetch: routed.fetch }));
    await screen.findByText('No alerts for this tenant.');

    act(() => stream.send('id: 1\nevent: alert\ndata: {broken\n\n'));
    act(() => stream.send(sseEvent('2', 'alert', { alertId: 'x', state: 'PAGED' })));
    expect(screen.getByText('No alerts for this tenant.')).toBeInTheDocument();
  });

  it('shows the error and a retry button when the list cannot be loaded, and recovers', async () => {
    const user = userEvent.setup();
    const routed = createRoutedFetch();
    routed.sse.enqueue({ keepOpen: true });
    routed.reply({ status: 503, contentType: 'application/problem+json', body: { title: 'Service Unavailable', detail: 'engine warming up' } });
    routed.reply({ body: [alert({ summary: 'Recovered' })] });
    renderWithServices(<AlertFeed tenantId="t" />, testServices({ fetch: routed.fetch }));

    const failure = await screen.findByRole('alert');
    expect(failure).toHaveTextContent('Could not load alerts: Service Unavailable: engine warming up');

    await user.click(within(failure).getByRole('button', { name: 'Try again' }));
    expect(await screen.findByText('Recovered')).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('shows the error when the server cannot be reached', async () => {
    const routed = createRoutedFetch();
    routed.sse.enqueue({ keepOpen: true });
    routed.reply({ throws: new TypeError('Failed to fetch') });
    renderWithServices(<AlertFeed tenantId="t" />, testServices({ fetch: routed.fetch }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Could not load alerts: Failed to fetch');
  });

  it('keeps a live change that lands while the list is still loading', async () => {
    const routed = createRoutedFetch();
    let release!: () => void;
    const gate = new Promise<void>((resolve) => (release = resolve));
    const stream = routed.sse.enqueue({ keepOpen: true });
    routed.reply({ body: [alert({ state: 'FIRING', summary: 'Stale snapshot' })], gate });
    renderWithServices(<AlertFeed tenantId="t" />, testServices({ fetch: routed.fetch }));
    await waitFor(() => expect(routed.sse.requests).toHaveLength(1));

    act(() => stream.send(sseEvent('1', 'alert', alert({ state: 'RESOLVED', summary: 'Live wins' }))));
    expect(await screen.findByText('Live wins')).toBeInTheDocument();

    await act(async () => release());
    await waitFor(() => expect(screen.queryByText(/loading alerts/i)).not.toBeInTheDocument());
    expect(screen.getByText('Live wins')).toBeInTheDocument();
    expect(screen.queryByText('Stale snapshot')).not.toBeInTheDocument();
    expect(screen.getByRole('listitem')).toHaveTextContent('✓ RESOLVED');
  });

  it('sends the access token with the list request', async () => {
    const routed = createRoutedFetch();
    routed.sse.enqueue({ keepOpen: true });
    routed.reply({ body: [] });
    const auth = { ...createDisabledAuth(), getAccessToken: async () => 'tok-5' };
    renderWithServices(<AlertFeed tenantId="t" />, testServices({ fetch: routed.fetch, auth }));
    await screen.findByText('No alerts for this tenant.');
    expect(routed.jsonRequests[0]!.headers.get('authorization')).toBe('Bearer tok-5');
    expect(routed.sse.requests[0]!.headers.get('authorization')).toBe('Bearer tok-5');
  });

  it('reloads the list after the stream reconnects, so nothing missed in the gap stays missing', async () => {
    const routed = createRoutedFetch();
    const first = routed.sse.enqueue({ keepOpen: true });
    routed.sse.enqueue({ keepOpen: true });
    routed.reply({ body: [alert({ summary: 'Before the drop' })] });
    routed.reply({ body: [alert({ alertId: 'a2', summary: 'Missed in the gap' })] });
    renderWithServices(<AlertFeed tenantId="t" />, testServices({ fetch: routed.fetch }));
    await screen.findByText('Before the drop');
    await waitFor(() => expect(screen.getByText(/connection: open/i)).toBeInTheDocument());

    act(() => first.end());

    expect(await screen.findByText('Missed in the gap', undefined, { timeout: 4_000 })).toBeInTheDocument();
    expect(routed.jsonRequests).toHaveLength(2);
    expect(routed.sse.requests).toHaveLength(2);
  });

  it('opens the stream for the tenant', async () => {
    const routed = createRoutedFetch();
    routed.sse.enqueue({ keepOpen: true });
    routed.reply({ body: [] });
    renderWithServices(<AlertFeed tenantId="acme corp" />, testServices({ fetch: routed.fetch }));
    await screen.findByText('No alerts for this tenant.');
    expect(routed.sse.requests[0]!.url).toBe('http://test.local/api/alerts/v1/alerts/stream?tenantId=acme%20corp');
  });
});
