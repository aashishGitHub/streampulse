export interface Batcher<T> {
  push(item: T): void;
  cancel(): void;
}

// Collects items and hands them over together, at most once per interval. Idle means no timer.
export function createBatcher<T>(intervalMs: number, onFlush: (items: T[]) => void): Batcher<T> {
  let pending: T[] = [];
  let timer: ReturnType<typeof setTimeout> | undefined;

  const flush = () => {
    timer = undefined;
    const items = pending;
    pending = [];
    onFlush(items);
  };

  return {
    push(item) {
      pending.push(item);
      timer ??= setTimeout(flush, intervalMs);
    },
    cancel() {
      clearTimeout(timer);
      timer = undefined;
      pending = [];
    },
  };
}
