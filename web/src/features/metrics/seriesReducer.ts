import type { ChartPoint, MetricPoint, Series } from './types';

export interface SeriesState {
  maxPoints: number;
  series: Record<string, Series>;
}

export type SeriesAction = { type: 'points'; points: MetricPoint[] } | { type: 'reset' };

export const createSeriesState = (maxPoints: number): SeriesState => ({ maxPoints, series: {} });

export const seriesKey = (metric: string, titleId: string, region: string) => JSON.stringify([metric, titleId, region]);

function insert(points: ChartPoint[], next: ChartPoint) {
  const last = points.at(-1);
  if (!last || next.time > last.time) {
    points.push(next);
    return;
  }
  const index = points.findIndex((p) => p.time >= next.time);
  if (points[index]?.time === next.time) points[index] = next;
  else points.splice(index, 0, next);
}

export function seriesReducer(state: SeriesState, action: SeriesAction): SeriesState {
  if (action.type === 'reset') return createSeriesState(state.maxPoints);

  const touched = new Map<string, Series>();
  for (const incoming of action.points) {
    const key = seriesKey(incoming.metric, incoming.titleId, incoming.region);
    let series = touched.get(key);
    if (!series) {
      const existing = state.series[key];
      series = existing
        ? { ...existing, points: [...existing.points] }
        : { key, metric: incoming.metric, titleId: incoming.titleId, region: incoming.region, points: [] };
      touched.set(key, series);
    }
    insert(series.points, { time: incoming.time, value: incoming.value, sampleCount: incoming.sampleCount });
  }

  const next = { ...state.series };
  for (const [key, series] of touched) {
    series.points.splice(0, Math.max(0, series.points.length - state.maxPoints));
    next[key] = series;
  }
  return { ...state, series: next };
}
