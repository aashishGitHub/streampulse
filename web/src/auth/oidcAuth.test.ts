// @vitest-environment node
import { describe, expect, it, vi } from 'vitest';
import { createDisabledAuth, createOidcAuth, type OidcManager, type OidcUser } from './oidcAuth';

const user = (over: Partial<OidcUser> = {}): OidcUser => ({
  access_token: 'tok-1',
  expired: false,
  profile: { sub: 's1', preferred_username: 'ada' },
  ...over,
});

function setup(options: { stored?: OidcUser | null; href?: string; callbackError?: Error } = {}) {
  const manager: OidcManager = {
    getUser: vi.fn(async () => options.stored ?? null),
    signinRedirect: vi.fn(async () => {}),
    signinRedirectCallback: vi.fn(async () => {
      if (options.callbackError) throw options.callbackError;
      return user();
    }),
    signoutRedirect: vi.fn(async () => {}),
  };
  const replaceUrl = vi.fn();
  const auth = createOidcAuth(manager, { href: options.href ?? 'http://localhost:5173/', replaceUrl });
  return { auth, manager, replaceUrl };
}

describe('createDisabledAuth', () => {
  it('is off, anonymous and hands out no token', async () => {
    const auth = createDisabledAuth();
    expect(auth.enabled).toBe(false);
    expect(await auth.getAccessToken()).toBeNull();
    expect(await auth.getUser()).toBeNull();
  });
});

describe('createOidcAuth', () => {
  it('has no token before anyone signs in, so the demo still works', async () => {
    const { auth } = setup();
    await auth.init();
    expect(auth.enabled).toBe(true);
    expect(await auth.getAccessToken()).toBeNull();
    expect(await auth.getUser()).toBeNull();
  });

  it('returns the access token of the signed-in user and a display name', async () => {
    const { auth } = setup({ stored: user() });
    expect(await auth.getAccessToken()).toBe('tok-1');
    expect(await auth.getUser()).toEqual({ name: 'ada' });
  });

  it('does not hand out an expired token', async () => {
    const { auth } = setup({ stored: user({ expired: true }) });
    expect(await auth.getAccessToken()).toBeNull();
  });

  it('completes the redirect when the address carries a code and state, then cleans the address bar', async () => {
    const href = 'http://localhost:5173/?code=abc&state=xyz&session_state=s&iss=http%3A%2F%2Fkc#/alerts';
    const { auth, manager, replaceUrl } = setup({ href });
    await auth.init();

    expect(manager.signinRedirectCallback).toHaveBeenCalledWith(href);
    expect(replaceUrl).toHaveBeenCalledWith('http://localhost:5173/#/alerts');
  });

  it('keeps unrelated query parameters when cleaning the address', async () => {
    const { auth, replaceUrl } = setup({ href: 'http://localhost:5173/?tenant=a&code=abc&state=xyz' });
    await auth.init();
    expect(replaceUrl).toHaveBeenCalledWith('http://localhost:5173/?tenant=a');
  });

  it('ignores an address with no authorization response', async () => {
    const { auth, manager, replaceUrl } = setup({ href: 'http://localhost:5173/?tenant=a' });
    await auth.init();
    expect(manager.signinRedirectCallback).not.toHaveBeenCalled();
    expect(replaceUrl).not.toHaveBeenCalled();
  });

  it('records a failed callback instead of throwing, and still cleans the address', async () => {
    const { auth, replaceUrl } = setup({ href: 'http://localhost:5173/?code=a&state=b', callbackError: new Error('state mismatch') });
    await expect(auth.init()).resolves.toBeUndefined();
    expect(auth.error).toBe('state mismatch');
    expect(replaceUrl).toHaveBeenCalledWith('http://localhost:5173/');
  });

  it('starts login and logout at the identity provider', async () => {
    const { auth, manager } = setup();
    await auth.login();
    await auth.logout();
    expect(manager.signinRedirect).toHaveBeenCalledTimes(1);
    expect(manager.signoutRedirect).toHaveBeenCalledTimes(1);
  });

  it('falls back to the subject when the profile has no name', async () => {
    const { auth } = setup({ stored: user({ profile: { sub: 'abc-123' } }) });
    expect(await auth.getUser()).toEqual({ name: 'abc-123' });
  });
});
