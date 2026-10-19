import type { ControlClient } from '../../api/clients';
import type { components } from '../../api/generated/control-plane-v1';
import { toResult, type ApiResult } from '../../api/result';

export type AlertRule = components['schemas']['AlertRule'];
export type AlertRuleUpdate = components['schemas']['AlertRuleUpdate'];

const emptyBody = (what: string): ApiResult<never> => ({ ok: false, problem: { title: 'Empty response', detail: `The server sent no ${what}.` } });

export async function listRules(client: ControlClient, tenantId: string): Promise<ApiResult<AlertRule[]>> {
  const result = await toResult(() => client.GET('/v1/admin/rules', { params: { query: { tenantId } } }));
  return result.ok ? { ok: true, value: result.value ?? [] } : result;
}

export async function getRule(client: ControlClient, ruleId: string): Promise<ApiResult<AlertRule>> {
  const result = await toResult(() => client.GET('/v1/admin/rules/{ruleId}', { params: { path: { ruleId } } }));
  if (!result.ok) return result;
  return result.value ? { ok: true, value: result.value } : emptyBody('rule');
}

export async function updateRule(client: ControlClient, ruleId: string, body: AlertRuleUpdate): Promise<ApiResult<AlertRule>> {
  const result = await toResult(() => client.PUT('/v1/admin/rules/{ruleId}', { params: { path: { ruleId } }, body }));
  if (!result.ok) return result;
  return result.value ? { ok: true, value: result.value } : emptyBody('rule');
}

export async function deleteRule(client: ControlClient, ruleId: string): Promise<ApiResult<undefined>> {
  const result = await toResult(() => client.DELETE('/v1/admin/rules/{ruleId}', { params: { path: { ruleId } } }));
  return result.ok ? { ok: true, value: undefined } : result;
}
