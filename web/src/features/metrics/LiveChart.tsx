import { useId } from 'react';
import { buildChart, formatClock, formatValue, summarize, type ChartBox } from './chartGeometry';
import { METRIC_LABELS, type LiveMetric, type Series } from './types';

const BOX: ChartBox = { width: 320, height: 120, padLeft: 8, padRight: 8, padTop: 8, padBottom: 8 };
const TABLE_ROWS = 10;

export function metricLabel(metric: string): string {
  return METRIC_LABELS[metric as LiveMetric] ?? metric;
}

export function LiveChart({ series }: { series: Series }) {
  const id = useId();
  const { points } = series;
  const { path, coords } = buildChart(points, BOX);
  const { latest, min, max, count } = summarize(points);
  const label = metricLabel(series.metric);
  const where = `${series.titleId || 'all titles'}, ${series.region}`;
  const description =
    latest === undefined
      ? 'No data yet.'
      : `Latest ${formatValue(latest.value)} at ${formatClock(latest.time)}. Lowest ${formatValue(min)}, highest ${formatValue(max)}, ${count} ${count === 1 ? 'point' : 'points'}.`;
  const lastCoord = coords.at(-1);

  return (
    <figure className="chart" aria-labelledby={`${id}-caption`}>
      <figcaption id={`${id}-caption`}>
        <span className="chart-title">
          {label} <span className="chart-where">({where})</span>
        </span>
        {latest && <span className="chart-latest">{formatValue(latest.value)}</span>}
      </figcaption>

      <svg viewBox={`0 0 ${BOX.width} ${BOX.height}`} role="img" aria-labelledby={`${id}-title`} aria-describedby={`${id}-desc`}>
        <title id={`${id}-title`}>{`${label}, ${where}`}</title>
        <desc id={`${id}-desc`}>{description}</desc>
        <line className="chart-axis" x1={BOX.padLeft} x2={BOX.width - BOX.padRight} y1={BOX.height - BOX.padBottom} y2={BOX.height - BOX.padBottom} />
        <path className="chart-line" d={path} fill="none" />
        {lastCoord && <circle className="chart-dot" cx={lastCoord.x} cy={lastCoord.y} r={3.5} />}
      </svg>

      <details>
        <summary>Data table ({count} points held)</summary>
        <table>
          <caption className="visually-hidden">{`Most recent values for ${label}, ${where}`}</caption>
          <thead>
            <tr>
              <th scope="col">Window start</th>
              <th scope="col">Value</th>
              <th scope="col">Samples</th>
            </tr>
          </thead>
          <tbody>
            {points
              .slice(-TABLE_ROWS)
              .reverse()
              .map((p) => (
                <tr key={p.time}>
                  <td>{formatClock(p.time)}</td>
                  <td>{formatValue(p.value)}</td>
                  <td>{p.sampleCount}</td>
                </tr>
              ))}
          </tbody>
        </table>
      </details>
    </figure>
  );
}
