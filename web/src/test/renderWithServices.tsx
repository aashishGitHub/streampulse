import { render } from '@testing-library/react';
import type { ReactElement } from 'react';
import { createDisabledAuth, type AuthService } from '../auth/oidcAuth';
import { readConfig, type AppConfig } from '../config';
import { ServicesContext, type Services } from '../services';

export const TEST_ORIGIN = 'http://test.local';

export function testServices(overrides: { fetch: typeof fetch; auth?: AuthService; config?: Partial<AppConfig> }): Services {
  const config = readConfig(
    {
      VITE_QUERY_BASE_URL: `${TEST_ORIGIN}/api/query`,
      VITE_CONTROL_BASE_URL: `${TEST_ORIGIN}/api/control`,
      VITE_ALERTS_BASE_URL: `${TEST_ORIGIN}/api/alerts`,
    },
    TEST_ORIGIN,
  );
  return { fetch: overrides.fetch, auth: overrides.auth ?? createDisabledAuth(), config: { ...config, ...overrides.config } };
}

export function renderWithServices(ui: ReactElement, services: Services) {
  const wrap = (node: ReactElement) => <ServicesContext.Provider value={services}>{node}</ServicesContext.Provider>;
  const result = render(wrap(ui));
  return { ...result, rerenderWithServices: (next: ReactElement) => result.rerender(wrap(next)) };
}
