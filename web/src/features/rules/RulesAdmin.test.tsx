import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { createDisabledAuth } from '../../auth/oidcAuth';
import { createFakeControlPlane } from '../../test/fakeControlPlane';
import { renderWithServices, testServices } from '../../test/renderWithServices';
import { RulesAdmin } from './RulesAdmin';
import type { AlertRule } from './rulesApi';

const rule = (over: Partial<AlertRule> = {}): AlertRule => ({
  ruleId: 'high-errors',
  tenantId: 'streamflix',
  metric: 'ERROR_RATE',
  comparator: 'GT',
  threshold: 0.05,
  severity: 'WARNING',
  category: 'PLAYER',
  dependsOn: ['CDN'],
  version: 2,
  ...over,
});

function setup(initial: AlertRule[] = [rule(), rule({ ruleId: 'low-viewers', metric: 'CONCURRENT_VIEWERS', comparator: 'LT', threshold: 10, severity: 'CRITICAL', category: undefined as never, dependsOn: [], version: 0 })], auth = createDisabledAuth()) {
  const fake = createFakeControlPlane(initial);
  const user = userEvent.setup();
  renderWithServices(<RulesAdmin tenantId="streamflix" />, testServices({ fetch: fake.fetch, auth }));
  return { fake, user };
}

const rowFor = (ruleId: string) => screen.getByRole('row', { name: new RegExp(ruleId) });

describe('RulesAdmin list', () => {
  it("lists the tenant's rules with what matters about each, including the version", async () => {
    const { fake } = setup();
    const table = await screen.findByRole('table', { name: /alert rules for streamflix/i });
    const row = within(table).getByRole('row', { name: /high-errors/ });
    expect(within(row).getByRole('rowheader')).toHaveTextContent('high-errors');
    expect(within(row).getAllByRole('cell').map((c) => c.textContent)).toEqual(['ERROR_RATE', 'GT 0.05', 'WARNING', 'PLAYER', 'CDN', '2', expect.stringContaining('View')]);
    expect(fake.callsOf('GET')[0]).toMatchObject({ path: '/api/control/v1/admin/rules', search: '?tenantId=streamflix' });
  });

  it('says so when the tenant has no rules', async () => {
    setup([]);
    expect(await screen.findByText('This tenant has no alert rules yet.')).toBeInTheDocument();
  });

  it('shows the problem and offers a retry when the list fails, and recovers', async () => {
    const fake = createFakeControlPlane([rule()]);
    fake.failNext('GET', '/v1/admin/rules', () => fake.problem(500, { title: 'Internal Server Error', detail: 'database down' }));
    const user = userEvent.setup();
    renderWithServices(<RulesAdmin tenantId="streamflix" />, testServices({ fetch: fake.fetch }));

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Internal Server Error');
    expect(alert).toHaveTextContent('database down');
    await user.click(screen.getByRole('button', { name: 'Try again' }));
    expect(await screen.findByRole('table')).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });
});

describe('RulesAdmin view and edit', () => {
  it('opens a rule by reading it from the server and shows every field and the version', async () => {
    const { fake, user } = setup();
    await user.click(await screen.findByRole('button', { name: 'View rule high-errors' }));

    const panel = await screen.findByRole('region', { name: 'Rule high-errors' });
    expect(within(panel).getByText('Version')).toBeInTheDocument();
    expect(panel).toHaveTextContent('2');
    expect(panel).toHaveTextContent('ERROR_RATE');
    expect(panel).toHaveTextContent('GT 0.05');
    expect(panel).toHaveTextContent('CDN');
    expect(fake.callsOf('GET').some((c) => c.path.endsWith('/rules/high-errors'))).toBe(true);
  });

  it('shows the problem when the rule it was asked to open no longer exists', async () => {
    const { fake, user } = setup();
    await screen.findByRole('table');
    fake.rules.delete('high-errors');
    await user.click(screen.getByRole('button', { name: 'View rule high-errors' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('rule high-errors does not exist');
  });

  it('edits a rule, then refreshes the list and confirms with the new version', async () => {
    const { fake, user } = setup();
    await user.click(await screen.findByRole('button', { name: 'Edit rule high-errors' }));
    const threshold = await screen.findByLabelText('Threshold');
    await user.clear(threshold);
    await user.type(threshold, '0.5');
    await user.click(screen.getByRole('button', { name: 'Save changes' }));

    expect(await screen.findByText('Rule "high-errors" updated (now version 3).')).toBeInTheDocument();
    await waitFor(() => expect(within(rowFor('high-errors')).getByText('GT 0.5')).toBeInTheDocument());
    expect(fake.callsOf('PUT')).toHaveLength(1);
  });

  it("shows the server's cycle rejection while editing and leaves the stored rule alone", async () => {
    const { fake, user } = setup();
    fake.failNext('PUT', '/high-errors', () => fake.problem(422, { title: 'Unprocessable Entity', detail: 'dependency cycle: PLAYER -> CDN -> PLAYER' }));
    await user.click(await screen.findByRole('button', { name: 'Edit rule high-errors' }));
    await user.click(await screen.findByRole('button', { name: 'Save changes' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('dependency cycle: PLAYER -> CDN -> PLAYER');
    expect(fake.rules.get('high-errors')!.version).toBe(2);
  });

  it('switches from viewing to editing and back to the list on cancel', async () => {
    const { user } = setup();
    await user.click(await screen.findByRole('button', { name: 'View rule high-errors' }));
    await user.click(await screen.findByRole('button', { name: 'Edit' }));
    expect(await screen.findByRole('button', { name: 'Save changes' })).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(screen.queryByRole('button', { name: 'Save changes' })).not.toBeInTheDocument();
  });
});

describe('RulesAdmin create', () => {
  it('creates a rule from the panel and the new rule appears in the list', async () => {
    const { fake, user } = setup([]);
    await screen.findByText('This tenant has no alert rules yet.');
    await user.click(screen.getByRole('button', { name: 'New rule' }));
    await user.type(screen.getByLabelText('Rule id'), 'fresh');
    await user.selectOptions(screen.getByLabelText('Metric'), 'ERROR_RATE');
    await user.type(screen.getByLabelText('Threshold'), '1');
    await user.click(screen.getByRole('button', { name: 'Create rule' }));

    expect(await screen.findByRole('row', { name: /fresh/ })).toBeInTheDocument();
    expect(fake.callsOf('POST')).toHaveLength(1);
  });
});

describe('RulesAdmin delete', () => {
  it('asks for confirmation and sends nothing until it is given', async () => {
    const { fake, user } = setup();
    await user.click(await screen.findByRole('button', { name: 'Delete rule high-errors' }));

    const confirm = screen.getByRole('group', { name: /confirm deleting rule high-errors/i });
    expect(confirm).toHaveTextContent('cannot be undone');
    expect(fake.callsOf('DELETE')).toHaveLength(0);
    expect(fake.rules.has('high-errors')).toBe(true);
  });

  it('puts keyboard focus on the safe choice', async () => {
    const { user } = setup();
    await user.click(await screen.findByRole('button', { name: 'Delete rule high-errors' }));
    expect(screen.getByRole('button', { name: 'Keep rule' })).toHaveFocus();
  });

  it('does nothing when the confirmation is declined', async () => {
    const { fake, user } = setup();
    await user.click(await screen.findByRole('button', { name: 'Delete rule high-errors' }));
    await user.click(screen.getByRole('button', { name: 'Keep rule' }));

    expect(screen.queryByRole('group', { name: /confirm deleting/i })).not.toBeInTheDocument();
    expect(fake.callsOf('DELETE')).toHaveLength(0);
    expect(rowFor('high-errors')).toBeInTheDocument();
  });

  it('can be declined with Escape', async () => {
    const { fake, user } = setup();
    await user.click(await screen.findByRole('button', { name: 'Delete rule high-errors' }));
    await user.keyboard('{Escape}');
    expect(screen.queryByRole('group', { name: /confirm deleting/i })).not.toBeInTheDocument();
    expect(fake.callsOf('DELETE')).toHaveLength(0);
  });

  it('deletes once confirmed, refreshes the list and says so', async () => {
    const { fake, user } = setup();
    await user.click(await screen.findByRole('button', { name: 'Delete rule high-errors' }));
    await user.click(screen.getByRole('button', { name: 'Yes, delete rule' }));

    expect(await screen.findByText('Rule "high-errors" deleted.')).toBeInTheDocument();
    expect(fake.callsOf('DELETE')).toHaveLength(1);
    expect(fake.callsOf('DELETE')[0]!.path).toBe('/api/control/v1/admin/rules/high-errors');
    expect(screen.queryByRole('row', { name: /high-errors/ })).not.toBeInTheDocument();
    expect(rowFor('low-viewers')).toBeInTheDocument();
  });

  it("shows the server's refusal and keeps the rule listed", async () => {
    const { fake, user } = setup();
    fake.failNext('DELETE', '/high-errors', () => fake.problem(409, { title: 'Conflict', detail: 'rule is referenced' }));
    await user.click(await screen.findByRole('button', { name: 'Delete rule high-errors' }));
    await user.click(screen.getByRole('button', { name: 'Yes, delete rule' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('rule is referenced');
    expect(rowFor('high-errors')).toBeInTheDocument();
    expect(screen.queryByRole('group', { name: /confirm deleting/i })).not.toBeInTheDocument();
  });

  it('shows the problem and refreshes when the rule was already gone', async () => {
    const { fake, user } = setup();
    await screen.findByRole('table');
    fake.rules.delete('high-errors');
    await user.click(screen.getByRole('button', { name: 'Delete rule high-errors' }));
    await user.click(screen.getByRole('button', { name: 'Yes, delete rule' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('does not exist');
    await waitFor(() => expect(screen.queryByRole('row', { name: /high-errors/ })).not.toBeInTheDocument());
  });
});

describe('RulesAdmin tenant', () => {
  it('sends the access token on every call', async () => {
    const { fake } = setup([rule()], { ...createDisabledAuth(), getAccessToken: async () => 'tok-7' });
    await screen.findByRole('table');
    expect(fake.calls.every((c) => c.authorization === 'Bearer tok-7')).toBe(true);
  });
});
