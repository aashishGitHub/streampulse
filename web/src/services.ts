import { createContext, useContext } from 'react';
import type { AuthService } from './auth/oidcAuth';
import type { AppConfig } from './config';

// Everything that touches the outside world, in one object: tests hand in a fake fetch and a
// fake auth instead of mocking modules.
export interface Services {
  fetch: typeof fetch;
  config: AppConfig;
  auth: AuthService;
}

export const ServicesContext = createContext<Services | null>(null);

export function useServices(): Services {
  const services = useContext(ServicesContext);
  if (!services) throw new Error('useServices needs a <ServicesContext.Provider>');
  return services;
}

export function bearerHeaders(auth: AuthService) {
  return async (): Promise<Record<string, string>> => {
    const token = await auth.getAccessToken();
    return token ? { Authorization: `Bearer ${token}` } : {};
  };
}
