import { useCallback, useEffect, useReducer, useRef } from 'react';
import { createBatcher, type Batcher } from '../../lib/batcher';
import type { SseMessage } from '../../lib/sse/parser';
import { useEventSource } from '../../lib/sse/useEventSource';
import { bearerHeaders, useServices } from '../../services';
import { parseMetricEvent } from './parseMetricEvent';
import { createSeriesState, seriesReducer } from './seriesReducer';
import { LIVE_METRICS, type MetricPoint, type Series } from './types';

const isLive = (metric: string) => (LIVE_METRICS as readonly string[]).includes(metric);
const metricOrder = (metric: string) => LIVE_METRICS.findIndex((m) => m === metric);

export function metricStreamUrl(queryBase: string, tenantId: string): string {
  return `${queryBase}/v1/stream/metrics?tenantId=${encodeURIComponent(tenantId)}&metrics=${LIVE_METRICS.join(',')}`;
}

// Data flow: stream message -> parse -> batcher (one flush per interval) -> reducer -> render.
// Callers remount this with `key={tenantId}`, which is what resets the series and the stream.
export function useMetricSeries(tenantId: string) {
  const { fetch, config, auth } = useServices();
  const [state, dispatch] = useReducer(seriesReducer, config.chart.maxPoints, createSeriesState);
  const batcher = useRef<Batcher<MetricPoint> | null>(null);
  const { refreshMs } = config.chart;

  useEffect(() => {
    const created = createBatcher<MetricPoint>(refreshMs, (points) => dispatch({ type: 'points', points }));
    batcher.current = created;
    return () => {
      created.cancel();
      batcher.current = null;
    };
  }, [refreshMs]);

  const onMessage = useCallback((message: SseMessage) => {
    if (message.event !== 'metric') return;
    const point = parseMetricEvent(message.data);
    if (point && isLive(point.metric)) batcher.current?.push(point);
  }, []);

  const connection = useEventSource({
    url: metricStreamUrl(config.urls.query, tenantId),
    fetch,
    onMessage,
    getHeaders: bearerHeaders(auth),
  });

  const series: Series[] = Object.values(state.series).sort(
    (a, b) =>
      metricOrder(a.metric) - metricOrder(b.metric) ||
      a.titleId.localeCompare(b.titleId) ||
      a.region.localeCompare(b.region),
  );
  return { series, connection };
}
