import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { createDisabledAuth, type AuthService } from './oidcAuth';
import { AuthStatus } from './AuthStatus';

const enabled = (over: Partial<AuthService> = {}): AuthService => ({ ...createDisabledAuth(), enabled: true, ...over });

describe('AuthStatus', () => {
  it('renders nothing when sign-in is switched off', () => {
    const { container } = render(<AuthStatus auth={createDisabledAuth()} />);
    expect(container).toBeEmptyDOMElement();
  });

  it('offers sign-in to an anonymous viewer and starts it on click', async () => {
    const login = vi.fn(async () => {});
    render(<AuthStatus auth={enabled({ login })} />);
    await userEvent.click(await screen.findByRole('button', { name: 'Sign in' }));
    expect(login).toHaveBeenCalledTimes(1);
  });

  it('names the signed-in user and offers sign-out', async () => {
    const logout = vi.fn(async () => {});
    render(<AuthStatus auth={enabled({ getUser: async () => ({ name: 'ada' }), logout })} />);
    expect(await screen.findByText('Signed in as ada')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Sign out' }));
    expect(logout).toHaveBeenCalledTimes(1);
  });

  it('reports a failed sign-in without hiding the sign-in button', async () => {
    render(<AuthStatus auth={enabled({ error: 'state mismatch' })} />);
    expect(await screen.findByRole('alert')).toHaveTextContent('Sign-in failed: state mismatch');
    expect(screen.getByRole('button', { name: 'Sign in' })).toBeInTheDocument();
  });
});
