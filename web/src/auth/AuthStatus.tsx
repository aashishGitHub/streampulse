import { useEffect, useState } from 'react';
import type { AuthService, AuthUser } from './oidcAuth';

export function AuthStatus({ auth }: { auth: AuthService }) {
  const [user, setUser] = useState<AuthUser | null>(null);
  const [checked, setChecked] = useState(false);

  useEffect(() => {
    let active = true;
    void auth.getUser().then((found) => {
      if (!active) return;
      setUser(found);
      setChecked(true);
    });
    return () => {
      active = false;
    };
  }, [auth]);

  if (!auth.enabled || !checked) return null;

  return (
    <div className="auth">
      {auth.error && (
        <p className="problem" role="alert">
          Sign-in failed: {auth.error}
        </p>
      )}
      {user ? (
        <>
          <span>Signed in as {user.name}</span>
          <button type="button" onClick={() => void auth.logout()}>
            Sign out
          </button>
        </>
      ) : (
        <button type="button" onClick={() => void auth.login()}>
          Sign in
        </button>
      )}
    </div>
  );
}
