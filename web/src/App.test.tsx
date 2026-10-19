import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { App } from './App';
import { renderWithServices, testServices } from './test/renderWithServices';
import { createRoutedFetch } from './test/routedFetch';

beforeEach(() => {
  window.location.hash = '';
});
afterEach(() => {
  window.location.hash = '';
});

function setup() {
  const routed = createRoutedFetch();
  routed.reply({ match: '/admin/tenants', body: [{ tenantId: 'streamflix', displayName: 'StreamFlix' }, { tenantId: 'acme', displayName: 'Acme' }] });
  routed.sse.enqueue({ keepOpen: true });
  routed.reply({ match: '/v1/alerts?', body: [] });
  routed.sse.enqueue({ keepOpen: true });
  renderWithServices(<App />, testServices({ fetch: routed.fetch }));
  return routed;
}

const navigate = (hash: string) => act(async () => {
  window.location.hash = hash;
  await new Promise((resolve) => setTimeout(resolve, 0));
});

describe('App', () => {
  it('shows the live metrics view first and marks it as the current page', async () => {
    setup();
    expect(await screen.findByRole('heading', { name: 'Live metrics' })).toBeVisible();
    expect(screen.getByRole('link', { name: 'Live metrics' })).toHaveAttribute('aria-current', 'page');
    expect(screen.getByRole('heading', { name: 'Alerts', hidden: true })).not.toBeVisible();
  });

  it('switches view with the address hash, so links and the Back button work', async () => {
    setup();
    await screen.findByRole('heading', { name: 'Live metrics' });
    await navigate('#/alerts');
    expect(screen.getByRole('heading', { name: 'Alerts' })).toBeVisible();
    expect(screen.getByRole('link', { name: 'Alerts' })).toHaveAttribute('aria-current', 'page');
    await navigate('#/rules');
    expect(screen.getByRole('heading', { name: 'Alert rules' })).toBeVisible();
  });

  it('keeps the live views connected while another view is shown', async () => {
    const routed = setup();
    await waitFor(() => expect(routed.sse.requests).toHaveLength(2));
    await navigate('#/rules');
    await navigate('#/metrics');
    expect(routed.sse.requests).toHaveLength(2);
  });

  it('moves both live views to the tenant chosen in the switcher', async () => {
    const routed = setup();
    routed.reply({ match: '/v1/alerts?', body: [] });
    routed.sse.enqueue({ keepOpen: true });
    routed.sse.enqueue({ keepOpen: true });
    await waitFor(() => expect(routed.sse.requests).toHaveLength(2));

    await userEvent.selectOptions(await screen.findByLabelText('Tenant'), 'acme');

    await waitFor(() => expect(routed.sse.requests).toHaveLength(4));
    const tenants = routed.sse.requests.slice(2).map((r) => new URL(r.url).searchParams.get('tenantId'));
    expect(tenants).toEqual(['acme', 'acme']);
  });

  it('tells the viewer when the tenant list is only the configured fallback', async () => {
    const routed = createRoutedFetch();
    routed.reply({ match: '/admin/tenants', status: 503, body: { title: 'down' } });
    routed.reply({ match: '/v1/alerts?', body: [] });
    routed.sse.enqueue({ keepOpen: true });
    routed.sse.enqueue({ keepOpen: true });
    renderWithServices(<App />, testServices({ fetch: routed.fetch }));

    const note = await screen.findByText(/tenant list unavailable/i);
    expect(note).toBeInTheDocument();
    expect(within(screen.getByLabelText('Tenant')).getByRole('option', { name: 'streamflix' })).toBeInTheDocument();
  });

  it('has a skip link and a focusable main landmark', async () => {
    setup();
    expect(await screen.findByRole('link', { name: 'Skip to content' })).toHaveAttribute('href', '#main');
    expect(screen.getByRole('main')).toHaveAttribute('tabindex', '-1');
  });
});
