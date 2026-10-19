export interface AppConfig {
  urls: { query: string; control: string; alerts: string };
  defaultTenants: string[];
  auth: {
    enabled: boolean;
    authority: string;
    clientId: string;
    redirectUri: string;
    postLogoutRedirectUri: string;
  };
  chart: { maxPoints: number; refreshMs: number };
}

type Env = Record<string, string | undefined>;

const DEFAULT_TENANT = 'streamflix';

function tenantsFrom(raw: string | undefined): string[] {
  const tenants = (raw ?? '').split(',').map((t) => t.trim()).filter(Boolean);
  return tenants.length > 0 ? tenants : [DEFAULT_TENANT];
}

export function readConfig(env: Env, origin: string): AppConfig {
  return {
    urls: {
      query: env.VITE_QUERY_BASE_URL ?? '/api/query',
      control: env.VITE_CONTROL_BASE_URL ?? '/api/control',
      alerts: env.VITE_ALERTS_BASE_URL ?? '/api/alerts',
    },
    defaultTenants: tenantsFrom(env.VITE_DEFAULT_TENANTS),
    auth: {
      enabled: env.VITE_AUTH_ENABLED === 'true',
      authority: env.VITE_OIDC_AUTHORITY ?? 'http://localhost:8081/realms/streampulse',
      clientId: env.VITE_OIDC_CLIENT_ID ?? 'streampulse-web',
      redirectUri: `${origin}/`,
      postLogoutRedirectUri: `${origin}/`,
    },
    // Targets, not measurements (specs/07-web/requirements.md).
    chart: { maxPoints: 120, refreshMs: 250 },
  };
}
