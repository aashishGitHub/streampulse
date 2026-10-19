export const LIVE_METRICS = ['CONCURRENT_VIEWERS', 'ERROR_RATE'] as const;
export type LiveMetric = (typeof LIVE_METRICS)[number];

export const METRIC_LABELS: Record<LiveMetric, string> = {
  CONCURRENT_VIEWERS: 'Concurrent viewers',
  ERROR_RATE: 'Error rate',
};

export interface MetricPoint {
  titleId: string;
  region: string;
  metric: string;
  /** Epoch milliseconds of the window start. */
  time: number;
  value: number;
  sampleCount: number;
}

export interface ChartPoint {
  time: number;
  value: number;
  sampleCount: number;
}

export interface Series {
  key: string;
  metric: string;
  titleId: string;
  region: string;
  points: ChartPoint[];
}
