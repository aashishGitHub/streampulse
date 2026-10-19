import { useState } from 'react';
import { AuthStatus } from './auth/AuthStatus';
import { AlertFeed } from './features/alerts/AlertFeed';
import { MetricsView } from './features/metrics/MetricsView';
import { RulesAdmin } from './features/rules/RulesAdmin';
import { TenantSwitcher } from './features/tenants/TenantSwitcher';
import { useTenants } from './features/tenants/useTenants';
import { useRoute, type Route } from './route';
import { useServices } from './services';

const NAV: Array<{ route: Route; label: string }> = [
  { route: 'metrics', label: 'Live metrics' },
  { route: 'alerts', label: 'Alerts' },
  { route: 'rules', label: 'Alert rules (admin)' },
];

export function App() {
  const { auth } = useServices();
  const route = useRoute();
  const tenants = useTenants();
  const [selected, setSelected] = useState<string | null>(null);
  const tenantId = tenants.options.find((o) => o.id === selected)?.id ?? tenants.options[0]?.id ?? '';

  return (
    <>
      <a className="skip-link" href="#main">
        Skip to content
      </a>
      <header className="app-header">
        <h1>StreamPulse</h1>
        <TenantSwitcher tenants={tenants} value={tenantId} onChange={setSelected} />
        <AuthStatus auth={auth} />
      </header>
      <nav aria-label="Views">
        <ul className="nav">
          {NAV.map((item) => (
            <li key={item.route}>
              <a href={`#/${item.route}`} aria-current={route === item.route ? 'page' : undefined}>
                {item.label}
              </a>
            </li>
          ))}
        </ul>
      </nav>
      <main id="main" tabIndex={-1}>
        {/* The two live views stay mounted so their charts and feed survive a trip to another view. */}
        <div hidden={route !== 'metrics'}>
          <MetricsView tenantId={tenantId} />
        </div>
        <div hidden={route !== 'alerts'}>
          <AlertFeed tenantId={tenantId} />
        </div>
        {route === 'rules' && <RulesAdmin tenantId={tenantId} />}
      </main>
    </>
  );
}
