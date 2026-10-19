import { useId, useRef, useState, type FormEvent } from 'react';
import type { paths as QueryPaths } from '../../api/generated/query-v1';
import type { Problem } from '../../api/problem';
import { useControlClient } from '../../api/useControlClient';
import { ProblemAlert } from '../../components/ProblemAlert';
import { updateRule, type AlertRule } from './rulesApi';
import { submitRule, type AlertRuleInput } from './submitRule';

type QueryMetric = QueryPaths['/v1/metrics/{tenantId}/{metric}']['get']['parameters']['path']['metric'];
type Comparator = NonNullable<AlertRuleInput['comparator']>;
type Severity = NonNullable<AlertRuleInput['severity']>;
type Category = NonNullable<AlertRuleInput['category']>;

// Typed from the generated contracts, so a renamed value fails the typecheck. These lists are
// not checked for completeness: a new enum value will not appear here by itself.
const METRICS = ['CONCURRENT_VIEWERS', 'REBUFFER_RATIO', 'ERROR_RATE', 'STARTUP_TIME_MS', 'AVG_BITRATE_KBPS'] satisfies QueryMetric[];
const COMPARATORS = ['GT', 'GTE', 'LT', 'LTE'] satisfies Comparator[];
const SEVERITIES = ['INFO', 'WARNING', 'CRITICAL'] satisfies Severity[];
const CATEGORIES = ['CDN', 'PACKAGER', 'PLAYER', 'INGEST', 'PIPELINE'] satisfies Category[];

interface Fields {
  ruleId: string;
  metric: string;
  comparator: Comparator;
  threshold: string;
  severity: Severity;
  category: string;
  dependsOn: Category[];
}

// Defaults are the contract's own (comparator GT, severity WARNING).
const EMPTY: Fields = { ruleId: '', metric: '', comparator: 'GT', threshold: '', severity: 'WARNING', category: '', dependsOn: [] };

const fieldsOf = (rule: AlertRule): Fields => ({
  ruleId: rule.ruleId,
  metric: rule.metric,
  comparator: rule.comparator,
  threshold: String(rule.threshold),
  severity: rule.severity,
  category: rule.category ?? '',
  dependsOn: rule.dependsOn ?? [],
});

const RULE_ID_PATTERN = /^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$/;
const FIELD_ORDER = ['ruleId', 'metric', 'threshold', 'category'] as const;
type Errors = Partial<Record<(typeof FIELD_ORDER)[number], string>>;

// Mirrors constraints the contract states; the server stays the authority and its message is shown when it disagrees.
function validate(fields: Fields, editing: boolean): Errors {
  const errors: Errors = {};
  if (fields.ruleId.trim() === '') errors.ruleId = 'Enter a rule id.';
  else if (!editing && !RULE_ID_PATTERN.test(fields.ruleId.trim())) {
    errors.ruleId = 'Use letters, digits, ".", "_", ":" or "-", up to 64 characters, starting with a letter or digit.';
  }
  if (fields.metric === '') errors.metric = 'Choose a metric.';
  if (fields.threshold.trim() === '' || !Number.isFinite(Number(fields.threshold))) errors.threshold = 'Enter a number.';
  if (fields.dependsOn.length > 0 && fields.category === '') errors.category = 'Choose a category when the rule depends on others.';
  return errors;
}

function toUpdate(fields: Fields) {
  return {
    metric: fields.metric,
    threshold: Number(fields.threshold),
    comparator: fields.comparator,
    severity: fields.severity,
    ...(fields.category && { category: fields.category as Category }),
    ...(fields.dependsOn.length > 0 && { dependsOn: fields.dependsOn }),
  };
}

function toRule(tenantId: string, fields: Fields): AlertRuleInput {
  return { ruleId: fields.ruleId.trim(), tenantId, ...toUpdate(fields) };
}

interface Props {
  tenantId: string;
  /** Present when editing: the id and tenant are then fixed. */
  rule?: AlertRule;
  onSaved?: (rule: AlertRule) => void;
  onCancel?: () => void;
}

export function RuleForm({ tenantId, rule, onSaved, onCancel }: Props) {
  const editing = rule !== undefined;
  const client = useControlClient();
  const id = useId();
  const form = useRef<HTMLFormElement>(null);
  const [fields, setFields] = useState<Fields>(rule ? fieldsOf(rule) : EMPTY);
  const [errors, setErrors] = useState<Errors>({});
  const [problem, setProblem] = useState<Problem | null>(null);
  const [created, setCreated] = useState<string | null>(null);
  const [pending, setPending] = useState(false);

  const set = <K extends keyof Fields>(key: K, value: Fields[K]) => setFields((f) => ({ ...f, [key]: value }));

  async function onSubmit(event: FormEvent) {
    event.preventDefault();
    if (pending) return;
    setCreated(null);
    setProblem(null);

    const found = validate(fields, editing);
    setErrors(found);
    const first = FIELD_ORDER.find((name) => found[name]);
    if (first) {
      (form.current?.elements.namedItem(first) as HTMLElement | null)?.focus();
      return;
    }

    setPending(true);
    if (rule) {
      const result = await updateRule(client, rule.ruleId, toUpdate(fields));
      setPending(false);
      if (result.ok) onSaved?.(result.value);
      else setProblem(result.problem);
      return;
    }
    const input = toRule(tenantId, fields);
    const result = await submitRule(client, input);
    setPending(false);
    if (result.ok) {
      setCreated(input.ruleId);
      setFields(EMPTY);
      onSaved?.(result.rule ?? input);
    } else {
      setProblem(result.problem);
    }
  }

  const describe = (name: keyof Errors) => (errors[name] ? `${id}-${name}-error` : undefined);

  return (
    <form ref={form} onSubmit={(event) => void onSubmit(event)} noValidate className="rule-form">
      <p>
        {rule ? (
          <>
            Editing rule <strong>{rule.ruleId}</strong> of tenant <strong>{rule.tenantId}</strong>, editing version {rule.version ?? 0}. The id and tenant cannot be changed.
          </>
        ) : (
          <>
            Rule for tenant <strong>{tenantId}</strong>.
          </>
        )}
      </p>

      <div className="field">
        <label htmlFor={`${id}-ruleId`}>Rule id</label>
        <input id={`${id}-ruleId`} name="ruleId" readOnly={editing} value={fields.ruleId} onChange={(e) => set('ruleId', e.target.value)} aria-invalid={!!errors.ruleId} aria-describedby={describe('ruleId')} autoComplete="off" />
        {errors.ruleId && <p className="field-error" id={`${id}-ruleId-error`}>{errors.ruleId}</p>}
      </div>

      <div className="field">
        <label htmlFor={`${id}-metric`}>Metric</label>
        <select id={`${id}-metric`} name="metric" value={fields.metric} onChange={(e) => set('metric', e.target.value)} aria-invalid={!!errors.metric} aria-describedby={describe('metric')}>
          <option value="">Select a metric</option>
          {[...METRICS, ...(fields.metric && !(METRICS as readonly string[]).includes(fields.metric) ? [fields.metric] : [])].map((m) => (
            <option key={m} value={m}>{m}</option>
          ))}
        </select>
        {errors.metric && <p className="field-error" id={`${id}-metric-error`}>{errors.metric}</p>}
      </div>

      <div className="field-row">
        <div className="field">
          <label htmlFor={`${id}-comparator`}>Comparator</label>
          <select id={`${id}-comparator`} value={fields.comparator} onChange={(e) => set('comparator', e.target.value as Comparator)}>
            {COMPARATORS.map((c) => (
              <option key={c} value={c}>{c}</option>
            ))}
          </select>
        </div>
        <div className="field">
          <label htmlFor={`${id}-threshold`}>Threshold</label>
          <input id={`${id}-threshold`} name="threshold" inputMode="decimal" value={fields.threshold} onChange={(e) => set('threshold', e.target.value)} aria-invalid={!!errors.threshold} aria-describedby={describe('threshold')} autoComplete="off" />
          {errors.threshold && <p className="field-error" id={`${id}-threshold-error`}>{errors.threshold}</p>}
        </div>
      </div>

      <div className="field-row">
        <div className="field">
          <label htmlFor={`${id}-severity`}>Severity</label>
          <select id={`${id}-severity`} value={fields.severity} onChange={(e) => set('severity', e.target.value as Severity)}>
            {SEVERITIES.map((s) => (
              <option key={s} value={s}>{s}</option>
            ))}
          </select>
        </div>
        <div className="field">
          <label htmlFor={`${id}-category`}>Category</label>
          <select id={`${id}-category`} name="category" value={fields.category} onChange={(e) => set('category', e.target.value)} aria-invalid={!!errors.category} aria-describedby={describe('category')}>
            <option value="">None</option>
            {CATEGORIES.map((c) => (
              <option key={c} value={c}>{c}</option>
            ))}
          </select>
          {errors.category && <p className="field-error" id={`${id}-category-error`}>{errors.category}</p>}
        </div>
      </div>

      <fieldset>
        <legend>Depends on (upstream categories)</legend>
        {CATEGORIES.map((c) => (
          <label key={c} className="check">
            <input
              type="checkbox"
              checked={fields.dependsOn.includes(c)}
              onChange={(e) => set('dependsOn', e.target.checked ? [...fields.dependsOn, c] : fields.dependsOn.filter((d) => d !== c))}
            />{' '}
            {c}
          </label>
        ))}
      </fieldset>

      <div className="actions">
        <button type="submit" disabled={pending}>
          {pending ? 'Saving…' : editing ? 'Save changes' : 'Create rule'}
        </button>
        {onCancel && (
          <button type="button" className="secondary" onClick={onCancel}>
            Cancel
          </button>
        )}
      </div>

      {created && <p role="status" className="success">Rule "{created}" created.</p>}
      {problem && <ProblemAlert problem={problem} />}
    </form>
  );
}
