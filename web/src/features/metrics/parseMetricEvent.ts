import type { MetricPoint } from './types';

const isString = (value: unknown): value is string => typeof value === 'string';

// The stream payload is not described by the OpenAPI file (it is typed as a plain string there),
// so it is checked here instead of trusted. A bad event is dropped; it must not end the stream.
export function parseMetricEvent(data: string): MetricPoint | null {
  let raw: unknown;
  try {
    raw = JSON.parse(data);
  } catch {
    return null;
  }
  if (typeof raw !== 'object' || raw === null) return null;
  const { titleId, region, metric, windowStart, value, sampleCount } = raw as Record<string, unknown>;

  if (!isString(titleId) || !isString(region) || !isString(metric) || !isString(windowStart)) return null;
  if (typeof value !== 'number' || !Number.isFinite(value)) return null;
  const time = Date.parse(windowStart);
  if (Number.isNaN(time)) return null;

  return { titleId, region, metric, time, value, sampleCount: typeof sampleCount === 'number' ? sampleCount : 0 };
}
