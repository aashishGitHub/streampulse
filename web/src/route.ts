import { useSyncExternalStore } from 'react';

export const ROUTES = ['metrics', 'alerts', 'rules'] as const;
export type Route = (typeof ROUTES)[number];

export function parseRoute(hash: string): Route {
  const name = hash.replace(/^#\//, '');
  return (ROUTES as readonly string[]).includes(name) && hash.startsWith('#/') ? (name as Route) : 'metrics';
}

const subscribe = (notify: () => void) => {
  window.addEventListener('hashchange', notify);
  return () => window.removeEventListener('hashchange', notify);
};

// Hash routing: deep links and the Back button work, and no router dependency is needed for three views.
export function useRoute(): Route {
  return parseRoute(useSyncExternalStore(subscribe, () => window.location.hash));
}
