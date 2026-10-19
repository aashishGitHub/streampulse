import type { ConnectionState } from '../lib/sse/connection';
import { SseHttpError } from '../lib/sse/transport';

function describeError(error: Error): string {
  return error instanceof SseHttpError ? `HTTP ${error.status}` : error.message;
}

export function ConnectionStatus({ state, error, what }: { state: ConnectionState; error: Error | null; what: string }) {
  return (
    <>
      <p className={`connection connection-${state}`} role="status">
        <span className="connection-dot" aria-hidden="true" />
        Connection: {state}
        {state === 'retrying' && error && <span> (last error: {describeError(error)})</span>}
      </p>
      {state === 'closed' && error && (
        <p className="problem" role="alert">
          {what} stopped: {describeError(error)}. It will not retry because the same request would fail again.
        </p>
      )}
    </>
  );
}
