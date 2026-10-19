import { ConnectionStatus } from '../../components/ConnectionStatus';
import { LiveChart } from './LiveChart';
import { useMetricSeries } from './useMetricSeries';
import { LIVE_METRICS, METRIC_LABELS } from './types';

function MetricsFeed({ tenantId }: { tenantId: string }) {
  const { series, connection } = useMetricSeries(tenantId);

  return (
    <>
      <ConnectionStatus state={connection.state} error={connection.error} what="Metric stream" />
      {series.length === 0 && <p className="empty">Waiting for the first metrics for this tenant.</p>}
      {LIVE_METRICS.map((metric) => {
        const group = series.filter((s) => s.metric === metric);
        if (group.length === 0) return null;
        return (
          <section key={metric} aria-label={METRIC_LABELS[metric]} className="metric-group">
            <h3>{METRIC_LABELS[metric]}</h3>
            <div className="chart-grid">
              {group.map((s) => (
                <LiveChart key={s.key} series={s} />
              ))}
            </div>
          </section>
        );
      })}
    </>
  );
}

export function MetricsView({ tenantId }: { tenantId: string }) {
  return (
    <section aria-labelledby="metrics-heading">
      <h2 id="metrics-heading">Live metrics</h2>
      <MetricsFeed key={tenantId} tenantId={tenantId} />
    </section>
  );
}
