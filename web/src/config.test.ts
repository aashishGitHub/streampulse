// @vitest-environment node
import { describe, expect, it } from 'vitest';
import { readConfig } from './config';

describe('readConfig', () => {
  it('defaults to same-origin proxy paths, the local identity provider, and sign-in off', () => {
    const config = readConfig({}, 'http://localhost:5173');
    expect(config.urls).toEqual({ query: '/api/query', control: '/api/control', alerts: '/api/alerts' });
    expect(config.auth).toEqual({
      enabled: false,
      authority: 'http://localhost:8081/realms/streampulse',
      clientId: 'streampulse-web',
      redirectUri: 'http://localhost:5173/',
      postLogoutRedirectUri: 'http://localhost:5173/',
    });
    expect(config.defaultTenants).toEqual(['streamflix']);
  });

  it('turns sign-in on only for the exact string "true"', () => {
    expect(readConfig({ VITE_AUTH_ENABLED: 'true' }, 'http://x').auth.enabled).toBe(true);
    for (const value of ['1', 'yes', 'TRUE', '', undefined]) {
      expect(readConfig({ VITE_AUTH_ENABLED: value }, 'http://x').auth.enabled).toBe(false);
    }
  });

  it('reads a comma separated tenant list and ignores blanks', () => {
    expect(readConfig({ VITE_DEFAULT_TENANTS: ' a, b ,,c ' }, 'http://x').defaultTenants).toEqual(['a', 'b', 'c']);
  });

  it('falls back to the default tenant when the list is empty', () => {
    expect(readConfig({ VITE_DEFAULT_TENANTS: ' , ' }, 'http://x').defaultTenants).toEqual(['streamflix']);
  });

  it('lets each setting be overridden', () => {
    const config = readConfig(
      { VITE_QUERY_BASE_URL: 'http://q', VITE_OIDC_AUTHORITY: 'http://idp/realms/r', VITE_OIDC_CLIENT_ID: 'c' },
      'http://x',
    );
    expect(config.urls.query).toBe('http://q');
    expect(config.auth.authority).toBe('http://idp/realms/r');
    expect(config.auth.clientId).toBe('c');
  });
});
