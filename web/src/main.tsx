import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { App } from './App';
import { createBrowserAuth } from './auth/oidcAuth';
import { readConfig } from './config';
import { ServicesContext, type Services } from './services';
import './styles.css';

async function start() {
  const config = readConfig(import.meta.env, window.location.origin);
  const auth = createBrowserAuth(config.auth);
  await auth.init();

  const services: Services = { fetch: (input, init) => window.fetch(input, init), config, auth };
  const root = document.getElementById('root');
  if (!root) throw new Error('index.html has no #root element');

  createRoot(root).render(
    <StrictMode>
      <ServicesContext.Provider value={services}>
        <App />
      </ServicesContext.Provider>
    </StrictMode>,
  );
}

void start();
