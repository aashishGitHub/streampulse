import type { ChartPoint } from './types';

export interface ChartBox {
  width: number;
  height: number;
  padLeft: number;
  padRight: number;
  padTop: number;
  padBottom: number;
}

export interface BuiltChart {
  path: string;
  coords: Array<{ x: number; y: number }>;
  yMax: number;
}

const round = (n: number) => Math.round(n * 10) / 10;

export function buildChart(points: ChartPoint[], box: ChartBox): BuiltChart {
  if (points.length === 0) return { path: '', coords: [], yMax: 1 };

  const times = points.map((p) => p.time);
  const tMin = Math.min(...times);
  const tMax = Math.max(...times);
  const max = Math.max(...points.map((p) => p.value));
  const yMax = max > 0 ? max * 1.1 : 1;
  const innerW = box.width - box.padLeft - box.padRight;
  const innerH = box.height - box.padTop - box.padBottom;

  const coords = points.map((p) => ({
    x: round(tMax === tMin ? box.padLeft : box.padLeft + ((p.time - tMin) / (tMax - tMin)) * innerW),
    y: round(box.padTop + (1 - Math.max(0, p.value) / yMax) * innerH),
  }));
  const path = coords.map((c, i) => `${i === 0 ? 'M' : 'L'} ${c.x} ${c.y}`).join(' ');
  return { path, coords, yMax };
}

export function summarize(points: ChartPoint[]) {
  const values = points.map((p) => p.value);
  return {
    latest: points.at(-1),
    min: Math.min(...values),
    max: Math.max(...values),
    count: points.length,
  };
}

const valueFormat = new Intl.NumberFormat('en-US', { maximumFractionDigits: 3 });
export const formatValue = (value: number) => valueFormat.format(value);

export const formatClock = (time: number) => `${new Date(time).toISOString().slice(11, 19)} UTC`;
