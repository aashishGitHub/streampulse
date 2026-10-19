import { useMemo } from 'react';
import { useServices } from '../services';
import { createControlClient, type ControlClient } from './clients';

export function useControlClient(): ControlClient {
  const { fetch, config, auth } = useServices();
  return useMemo(() => createControlClient({ fetch, baseUrl: config.urls.control, auth }), [fetch, config.urls.control, auth]);
}
