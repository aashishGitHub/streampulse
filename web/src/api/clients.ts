import createClient, { type Client } from 'openapi-fetch';
import type { AuthService } from '../auth/oidcAuth';
import type { paths as ControlPaths } from './generated/control-plane-v1';

export type ControlClient = Client<ControlPaths>;

export interface ClientDeps {
  fetch: typeof fetch;
  baseUrl: string;
  auth: AuthService;
}

export function createControlClient({ fetch, baseUrl, auth }: ClientDeps): ControlClient {
  const client = createClient<ControlPaths>({ baseUrl, fetch });
  client.use({
    async onRequest({ request }) {
      const token = await auth.getAccessToken();
      if (token) request.headers.set('Authorization', `Bearer ${token}`);
      return request;
    },
  });
  return client;
}
