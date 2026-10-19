import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { renderWithServices, testServices } from '../../test/renderWithServices';
import { createFakeControlPlane } from '../../test/fakeControlPlane';
import { RuleForm } from './RuleForm';
import type { AlertRule } from './rulesApi';

interface Sent {
  method: string;
  url: string;
  body: unknown;
}

function setup(respond: () => Response | Promise<Response> = () => new Response(null, { status: 201 })) {
  const sent: Sent[] = [];
  const fetchFn = vi.fn(async (input: RequestInfo | URL) => {
    const request = input as Request;
    sent.push({ method: request.method, url: request.url, body: await request.clone().json().catch(() => undefined) });
    return respond();
  });
  const user = userEvent.setup();
  renderWithServices(<RuleForm tenantId="streamflix" />, testServices({ fetch: fetchFn as unknown as typeof fetch }));
  return { user, sent, fetchFn };
}

async function fillValid(user: ReturnType<typeof userEvent.setup>) {
  await user.type(screen.getByLabelText('Rule id'), 'high-errors');
  await user.selectOptions(screen.getByLabelText('Metric'), 'ERROR_RATE');
  await user.type(screen.getByLabelText('Threshold'), '0.05');
}

describe('RuleForm', () => {
  it('asks for the missing required fields by name, sends nothing, and moves focus to the first', async () => {
    const { user, fetchFn } = setup();
    await user.click(screen.getByRole('button', { name: 'Create rule' }));

    expect(fetchFn).not.toHaveBeenCalled();
    expect(screen.getByLabelText('Rule id')).toHaveAccessibleDescription('Enter a rule id.');
    expect(screen.getByLabelText('Metric')).toHaveAccessibleDescription('Choose a metric.');
    expect(screen.getByLabelText('Threshold')).toHaveAccessibleDescription('Enter a number.');
    expect(screen.getByLabelText('Rule id')).toBeInvalid();
    expect(screen.getByLabelText('Rule id')).toHaveFocus();
  });

  it('rejects a threshold that is not a number', async () => {
    const { user, fetchFn } = setup();
    await user.type(screen.getByLabelText('Rule id'), 'r');
    await user.selectOptions(screen.getByLabelText('Metric'), 'ERROR_RATE');
    await user.type(screen.getByLabelText('Threshold'), 'abc');
    await user.click(screen.getByRole('button', { name: 'Create rule' }));
    expect(fetchFn).not.toHaveBeenCalled();
  });

  it('posts the rule for the selected tenant with the contract defaults, leaving out category and dependencies that were not set', async () => {
    const { user, sent } = setup();
    await fillValid(user);
    await user.click(screen.getByRole('button', { name: 'Create rule' }));

    await screen.findByText(/rule "high-errors" created/i);
    expect(sent).toEqual([
      {
        method: 'POST',
        url: 'http://test.local/api/control/v1/admin/rules',
        body: { ruleId: 'high-errors', tenantId: 'streamflix', metric: 'ERROR_RATE', threshold: 0.05, comparator: 'GT', severity: 'WARNING' },
      },
    ]);
  });

  it('sends severity, category and dependencies when chosen', async () => {
    const { user, sent } = setup();
    await fillValid(user);
    await user.selectOptions(screen.getByLabelText('Comparator'), 'LTE');
    await user.selectOptions(screen.getByLabelText('Severity'), 'CRITICAL');
    await user.selectOptions(screen.getByLabelText('Category'), 'PLAYER');
    await user.click(screen.getByRole('checkbox', { name: 'CDN' }));
    await user.click(screen.getByRole('checkbox', { name: 'PACKAGER' }));
    await user.click(screen.getByRole('button', { name: 'Create rule' }));

    await screen.findByRole('status');
    expect(sent[0]?.body).toMatchObject({ comparator: 'LTE', severity: 'CRITICAL', category: 'PLAYER', dependsOn: ['CDN', 'PACKAGER'] });
  });

  it('clears the form after a successful create so the next rule starts empty', async () => {
    const { user } = setup();
    await fillValid(user);
    await user.click(screen.getByRole('button', { name: 'Create rule' }));
    await screen.findByRole('status');
    expect(screen.getByLabelText('Rule id')).toHaveValue('');
  });

  it('shows a problem+json rejection in full next to the form and keeps what the user typed', async () => {
    const { user } = setup(
      () =>
        new Response(
          JSON.stringify({ title: 'Dependency cycle', status: 422, detail: 'CDN -> PLAYER -> CDN', traceId: 'trace-77' }),
          { status: 422, headers: { 'content-type': 'application/problem+json' } },
        ),
    );
    await fillValid(user);
    await user.click(screen.getByRole('button', { name: 'Create rule' }));

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Dependency cycle');
    expect(alert).toHaveTextContent('CDN -> PLAYER -> CDN');
    expect(alert).toHaveTextContent('trace-77');
    expect(screen.getByLabelText('Rule id')).toHaveValue('high-errors');
    expect(screen.queryByRole('status')).not.toBeInTheDocument();
  });

  it('says the server could not be reached when the request fails outright', async () => {
    const { user } = setup(() => {
      throw new TypeError('Failed to fetch');
    });
    await fillValid(user);
    await user.click(screen.getByRole('button', { name: 'Create rule' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Could not reach the server');
  });

  it('removes an earlier problem when the next attempt succeeds', async () => {
    let attempt = 0;
    const { user } = setup(() => (attempt++ === 0 ? new Response('{"title":"Nope"}', { status: 400 }) : new Response(null, { status: 201 })));
    await fillValid(user);
    await user.click(screen.getByRole('button', { name: 'Create rule' }));
    await screen.findByRole('alert');

    await user.click(screen.getByRole('button', { name: 'Create rule' }));
    await screen.findByRole('status');
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('cannot be submitted twice while a request is in flight', async () => {
    let release!: () => void;
    const gate = new Promise<void>((resolve) => (release = resolve));
    const { user, fetchFn } = setup(async () => {
      await gate;
      return new Response(null, { status: 201 });
    });
    await fillValid(user);
    await user.click(screen.getByRole('button', { name: 'Create rule' }));

    const button = screen.getByRole('button', { name: /saving/i });
    expect(button).toBeDisabled();
    await user.click(button);
    expect(fetchFn).toHaveBeenCalledTimes(1);

    release();
    await waitFor(() => expect(screen.getByRole('button', { name: 'Create rule' })).toBeEnabled());
  });
});

describe('RuleForm validation against the contract', () => {
  it('requires a category when the rule depends on others, as the contract says', async () => {
    const { user, fetchFn } = setup();
    await fillValid(user);
    await user.click(screen.getByRole('checkbox', { name: 'CDN' }));
    await user.click(screen.getByRole('button', { name: 'Create rule' }));

    expect(fetchFn).not.toHaveBeenCalled();
    expect(screen.getByLabelText('Category')).toHaveAccessibleDescription('Choose a category when the rule depends on others.');
    expect(screen.getByLabelText('Category')).toHaveFocus();
  });

  it('checks the rule id against the pattern the contract defines', async () => {
    const { user, fetchFn } = setup();
    await user.type(screen.getByLabelText('Rule id'), '-bad id');
    await user.selectOptions(screen.getByLabelText('Metric'), 'ERROR_RATE');
    await user.type(screen.getByLabelText('Threshold'), '1');
    await user.click(screen.getByRole('button', { name: 'Create rule' }));
    expect(fetchFn).not.toHaveBeenCalled();
    expect(screen.getByLabelText('Rule id')).toHaveAccessibleDescription(/letters, digits/i);
  });

  it('lists the per-field messages the server returns with a 400', async () => {
    const { user } = setup(
      () =>
        new Response(JSON.stringify({ title: 'Bad Request', detail: 'Validation failed', errors: ['threshold: must be a number', 'metric: must not be blank'] }), {
          status: 400,
          headers: { 'content-type': 'application/problem+json' },
        }),
    );
    await fillValid(user);
    await user.click(screen.getByRole('button', { name: 'Create rule' }));
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('threshold: must be a number');
    expect(alert).toHaveTextContent('metric: must not be blank');
  });
});

describe('RuleForm editing', () => {
  const existing: AlertRule = { ruleId: 'high-errors', tenantId: 'streamflix', metric: 'ERROR_RATE', comparator: 'GT', threshold: 0.05, severity: 'WARNING', category: 'PLAYER', dependsOn: ['CDN'], version: 3 };

  function editSetup() {
    const fake = createFakeControlPlane([existing]);
    const onSaved = vi.fn();
    const onCancel = vi.fn();
    const user = userEvent.setup();
    renderWithServices(<RuleForm tenantId="streamflix" rule={existing} onSaved={onSaved} onCancel={onCancel} />, testServices({ fetch: fake.fetch }));
    return { fake, onSaved, onCancel, user };
  }

  it('starts from the rule being edited and announces its version', () => {
    editSetup();
    expect(screen.getByLabelText('Rule id')).toHaveValue('high-errors');
    expect(screen.getByLabelText('Metric')).toHaveValue('ERROR_RATE');
    expect(screen.getByLabelText('Threshold')).toHaveValue('0.05');
    expect(screen.getByLabelText('Category')).toHaveValue('PLAYER');
    expect(screen.getByRole('checkbox', { name: 'CDN' })).toBeChecked();
    expect(screen.getByText(/editing version 3/i)).toBeInTheDocument();
  });

  it('does not let the id be changed: the contract has no way to rename a rule or move its tenant', () => {
    editSetup();
    expect(screen.getByLabelText('Rule id')).toHaveAttribute('readonly');
  });

  it('saves with a PUT to the rule and hands the updated rule to the caller', async () => {
    const { fake, onSaved, user } = editSetup();
    await user.clear(screen.getByLabelText('Threshold'));
    await user.type(screen.getByLabelText('Threshold'), '0.2');
    await user.selectOptions(screen.getByLabelText('Severity'), 'CRITICAL');
    await user.click(screen.getByRole('button', { name: 'Save changes' }));

    await waitFor(() => expect(onSaved).toHaveBeenCalledTimes(1));
    expect(fake.callsOf('PUT')[0]).toMatchObject({
      path: '/api/control/v1/admin/rules/high-errors',
      body: { metric: 'ERROR_RATE', comparator: 'GT', threshold: 0.2, severity: 'CRITICAL', category: 'PLAYER', dependsOn: ['CDN'] },
    });
    expect(onSaved.mock.calls[0]![0]).toMatchObject({ ruleId: 'high-errors', threshold: 0.2, version: 4 });
  });

  it("shows the server's cycle rejection and keeps the edit", async () => {
    const { fake, onSaved, user } = editSetup();
    fake.failNext('PUT', '/high-errors', () => fake.problem(422, { title: 'Unprocessable Entity', detail: 'dependency cycle: PLAYER -> CDN -> PLAYER' }));
    await user.clear(screen.getByLabelText('Threshold'));
    await user.type(screen.getByLabelText('Threshold'), '9');
    await user.click(screen.getByRole('button', { name: 'Save changes' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('dependency cycle: PLAYER -> CDN -> PLAYER');
    expect(screen.getByLabelText('Threshold')).toHaveValue('9');
    expect(onSaved).not.toHaveBeenCalled();
  });

  it('shows a conflict from a concurrent change as the server words it', async () => {
    const { fake, user } = editSetup();
    fake.failNext('PUT', '/high-errors', () => fake.problem(409, { title: 'Conflict', detail: 'rule was modified concurrently' }));
    await user.click(screen.getByRole('button', { name: 'Save changes' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('rule was modified concurrently');
  });

  it('offers cancel', async () => {
    const { onCancel, user } = editSetup();
    await user.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(onCancel).toHaveBeenCalledTimes(1);
  });
});
