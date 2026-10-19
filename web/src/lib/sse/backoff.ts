export interface BackoffConfig {
  baseMs: number;
  capMs: number;
}

export const DEFAULT_BACKOFF: BackoffConfig = { baseMs: 1_000, capMs: 30_000 };

// "Equal jitter": half the ceiling is guaranteed so a reconnect storm cannot collapse to ~0 ms,
// the other half is random so clients that failed together do not return together.
export function backoffDelay(attempt: number, config: BackoffConfig, random: () => number): number {
  const ceiling = Math.min(config.capMs, config.baseMs * 2 ** attempt);
  return ceiling / 2 + random() * (ceiling / 2);
}
