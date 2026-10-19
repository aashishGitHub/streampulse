import { UserManager, WebStorageStateStore } from 'oidc-client-ts';
import type { AppConfig } from '../config';

export interface AuthUser {
  name: string;
}

export interface AuthService {
  readonly enabled: boolean;
  /** Set when completing a redirect failed; sign-in is optional, so this is shown, not thrown. */
  readonly error: string | null;
  init(): Promise<void>;
  getUser(): Promise<AuthUser | null>;
  getAccessToken(): Promise<string | null>;
  login(): Promise<void>;
  logout(): Promise<void>;
}

// The part of oidc-client-ts' UserManager this app uses; a fake can stand in for it in tests.
export interface OidcUser {
  access_token: string;
  expired?: boolean | undefined;
  profile: { sub: string; name?: string | undefined; preferred_username?: string | undefined };
}

export interface OidcManager {
  getUser(): Promise<OidcUser | null>;
  signinRedirect(): Promise<void>;
  signinRedirectCallback(url?: string): Promise<OidcUser>;
  signoutRedirect(): Promise<void>;
}

export interface BrowserLocation {
  href: string;
  replaceUrl(url: string): void;
}

const RESPONSE_PARAMS = ['code', 'state', 'session_state', 'iss', 'error', 'error_description'];

export function createDisabledAuth(): AuthService {
  return {
    enabled: false,
    error: null,
    init: async () => {},
    getUser: async () => null,
    getAccessToken: async () => null,
    login: async () => {},
    logout: async () => {},
  };
}

export function createOidcAuth(manager: OidcManager, location: BrowserLocation): AuthService {
  let error: string | null = null;

  return {
    enabled: true,
    get error() {
      return error;
    },
    async init() {
      const url = new URL(location.href);
      if (!url.searchParams.has('code') || !url.searchParams.has('state')) return;
      try {
        await manager.signinRedirectCallback(location.href);
      } catch (cause) {
        error = cause instanceof Error ? cause.message : String(cause);
      }
      for (const name of RESPONSE_PARAMS) url.searchParams.delete(name);
      location.replaceUrl(url.href);
    },
    async getUser() {
      const user = await manager.getUser();
      if (!user) return null;
      return { name: user.profile.preferred_username ?? user.profile.name ?? user.profile.sub };
    },
    async getAccessToken() {
      const user = await manager.getUser();
      return user && !user.expired ? user.access_token : null;
    },
    login: () => manager.signinRedirect(),
    logout: () => manager.signoutRedirect(),
  };
}

// Authorization-code flow; oidc-client-ts adds the S256 PKCE challenge unless disablePKCE is set.
// Tokens live in sessionStorage so they die with the tab.
export function createBrowserAuth(config: AppConfig['auth']): AuthService {
  if (!config.enabled) return createDisabledAuth();
  const manager = new UserManager({
    authority: config.authority,
    client_id: config.clientId,
    redirect_uri: config.redirectUri,
    post_logout_redirect_uri: config.postLogoutRedirectUri,
    response_type: 'code',
    scope: 'openid profile',
    userStore: new WebStorageStateStore({ store: window.sessionStorage }),
  });
  return createOidcAuth(manager, {
    href: window.location.href,
    replaceUrl: (url) => window.history.replaceState(null, '', url),
  });
}
