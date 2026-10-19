import type { ControlClient } from '../../api/clients';
import type { Problem } from '../../api/problem';
import { toResult } from '../../api/result';
import type { AlertRule } from './rulesApi';

export type AlertRuleInput = AlertRule;
export type SubmitResult = { ok: true; rule: AlertRule | undefined } | { ok: false; problem: Problem };

export async function submitRule(client: ControlClient, rule: AlertRuleInput): Promise<SubmitResult> {
  const result = await toResult(() => client.POST('/v1/admin/rules', { body: rule }));
  return result.ok ? { ok: true, rule: result.value } : result;
}
