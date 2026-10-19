import { useEffect, useRef, useState, type KeyboardEvent } from 'react';
import type { Problem } from '../../api/problem';
import { useControlClient } from '../../api/useControlClient';
import { ProblemAlert } from '../../components/ProblemAlert';
import { RuleForm } from './RuleForm';
import { deleteRule, type AlertRule } from './rulesApi';
import { useRule, useRules } from './useRules';

type Panel = { kind: 'create' } | { kind: 'rule'; ruleId: string; edit: boolean };

const dash = (text: string) => (text === '' ? '—' : text);
const condition = (rule: AlertRule) => `${rule.comparator} ${rule.threshold}`;

function RuleDetails({ rule }: { rule: AlertRule }) {
  const rows: Array<[string, string]> = [
    ['Tenant', rule.tenantId],
    ['Metric', rule.metric],
    ['Condition', condition(rule)],
    ['Severity', rule.severity],
    ['Category', dash(rule.category ?? '')],
    ['Depends on', dash((rule.dependsOn ?? []).join(', '))],
    ['Version', String(rule.version ?? 0)],
  ];
  return (
    <dl className="details">
      {rows.map(([term, value]) => (
        <div key={term}>
          <dt>{term}</dt>
          <dd>{value}</dd>
        </div>
      ))}
    </dl>
  );
}

function RulePanel({ ruleId, startEditing, tenantId, onSaved, onClose }: { ruleId: string; startEditing: boolean; tenantId: string; onSaved: (rule: AlertRule) => void; onClose: () => void }) {
  const { state, reload } = useRule(ruleId);
  const [editing, setEditing] = useState(startEditing);
  const heading = useRef<HTMLHeadingElement>(null);

  useEffect(() => {
    heading.current?.focus();
  }, []);

  return (
    <section className="panel" aria-labelledby={`panel-${ruleId}`}>
      <h3 id={`panel-${ruleId}`} ref={heading} tabIndex={-1}>
        Rule {ruleId}
      </h3>
      {state.status === 'loading' && <p role="status">Loading rule…</p>}
      {state.status === 'failed' && (
        <>
          <ProblemAlert problem={state.problem} />
          <button type="button" onClick={reload}>Try again</button>{' '}
          <button type="button" className="secondary" onClick={onClose}>Close</button>
        </>
      )}
      {state.status === 'loaded' && !editing && (
        <>
          <RuleDetails rule={state.value} />
          <div className="actions">
            <button type="button" onClick={() => setEditing(true)}>Edit</button>
            <button type="button" className="secondary" onClick={onClose}>Close</button>
          </div>
        </>
      )}
      {state.status === 'loaded' && editing && (
        <RuleForm tenantId={tenantId} rule={state.value} onSaved={onSaved} onCancel={onClose} />
      )}
    </section>
  );
}

function DeleteConfirmation({ ruleId, pending, onConfirm, onCancel }: { ruleId: string; pending: boolean; onConfirm: () => void; onCancel: () => void }) {
  const keep = useRef<HTMLButtonElement>(null);
  useEffect(() => {
    keep.current?.focus();
  }, []);
  const onKeyDown = (event: KeyboardEvent) => {
    if (event.key === 'Escape') onCancel();
  };

  return (
    <div role="group" aria-labelledby="confirm-delete" className="confirm" onKeyDown={onKeyDown}>
      <p id="confirm-delete">
        <strong>Confirm deleting rule {ruleId}</strong>
      </p>
      <p>Deleting a rule cannot be undone.</p>
      <button type="button" ref={keep} onClick={onCancel}>Keep rule</button>{' '}
      <button type="button" className="danger" disabled={pending} onClick={onConfirm}>Yes, delete rule</button>
    </div>
  );
}

export function RulesAdmin({ tenantId }: { tenantId: string }) {
  const client = useControlClient();
  const { state, reload } = useRules(tenantId);
  const [panel, setPanel] = useState<Panel | null>(null);
  const [confirming, setConfirming] = useState<string | null>(null);
  const [deleting, setDeleting] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const [actionProblem, setActionProblem] = useState<Problem | null>(null);

  const open = (next: Panel | null) => {
    setPanel(next);
    setNotice(null);
    setActionProblem(null);
  };

  async function confirmDelete(ruleId: string) {
    setDeleting(true);
    const result = await deleteRule(client, ruleId);
    setDeleting(false);
    setConfirming(null);
    if (result.ok) {
      setNotice(`Rule "${ruleId}" deleted.`);
      setActionProblem(null);
      if (panel?.kind === 'rule' && panel.ruleId === ruleId) setPanel(null);
    } else {
      setNotice(null);
      setActionProblem(result.problem);
    }
    reload();
  }

  return (
    <section aria-labelledby="rules-heading">
      <h2 id="rules-heading">Alert rules</h2>
      <p>
        Tenant <strong>{tenantId}</strong>.{' '}
        <button type="button" onClick={() => open({ kind: 'create' })}>New rule</button>
      </p>

      {notice && <p role="status" className="success">{notice}</p>}
      {actionProblem && <ProblemAlert problem={actionProblem} />}

      {state.status === 'loading' && <p role="status">Loading rules…</p>}
      {state.status === 'failed' && (
        <>
          <ProblemAlert problem={state.problem} />
          <button type="button" onClick={reload}>Try again</button>
        </>
      )}
      {state.status === 'loaded' && state.value.length === 0 && <p className="empty">This tenant has no alert rules yet.</p>}
      {state.status === 'loaded' && state.value.length > 0 && (
        <table className="rules">
          <caption>Alert rules for {tenantId}</caption>
          <thead>
            <tr>
              <th scope="col">Rule</th>
              <th scope="col">Metric</th>
              <th scope="col">Condition</th>
              <th scope="col">Severity</th>
              <th scope="col">Category</th>
              <th scope="col">Depends on</th>
              <th scope="col">Version</th>
              <th scope="col">Actions</th>
            </tr>
          </thead>
          <tbody>
            {state.value.map((rule) => (
              <tr key={rule.ruleId}>
                <th scope="row">{rule.ruleId}</th>
                <td>{rule.metric}</td>
                <td>{condition(rule)}</td>
                <td>{rule.severity}</td>
                <td>{dash(rule.category ?? '')}</td>
                <td>{dash((rule.dependsOn ?? []).join(', '))}</td>
                <td>{rule.version ?? 0}</td>
                <td className="row-actions">
                  <button type="button" aria-label={`View rule ${rule.ruleId}`} onClick={() => open({ kind: 'rule', ruleId: rule.ruleId, edit: false })}>View</button>
                  <button type="button" aria-label={`Edit rule ${rule.ruleId}`} onClick={() => open({ kind: 'rule', ruleId: rule.ruleId, edit: true })}>Edit</button>
                  <button type="button" className="danger" aria-label={`Delete rule ${rule.ruleId}`} onClick={() => setConfirming(rule.ruleId)}>Delete</button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      {confirming && (
        <DeleteConfirmation ruleId={confirming} pending={deleting} onConfirm={() => void confirmDelete(confirming)} onCancel={() => setConfirming(null)} />
      )}

      {panel?.kind === 'create' && (
        <section className="panel" aria-labelledby="create-heading">
          <h3 id="create-heading">New rule</h3>
          <RuleForm tenantId={tenantId} onSaved={() => reload()} onCancel={() => open(null)} />
        </section>
      )}
      {panel?.kind === 'rule' && (
        <RulePanel
          key={`${panel.ruleId}:${panel.edit}`}
          ruleId={panel.ruleId}
          startEditing={panel.edit}
          tenantId={tenantId}
          onSaved={(saved) => {
            setPanel(null);
            setNotice(`Rule "${saved.ruleId}" updated (now version ${saved.version ?? 0}).`);
            reload();
          }}
          onClose={() => open(null)}
        />
      )}
    </section>
  );
}
