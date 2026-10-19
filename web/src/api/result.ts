import { networkProblem, problemFrom, type Problem } from './problem';

export type ApiResult<T> = { ok: true; value: T } | { ok: false; problem: Problem };

interface FetchOutcome<T> {
  data?: T;
  error?: unknown;
  response: Response;
}

// One place that turns an openapi-fetch outcome, or a thrown network failure, into a value the UI can show.
export async function toResult<T>(call: () => Promise<FetchOutcome<T>>): Promise<ApiResult<T | undefined>> {
  try {
    const { data, error, response } = await call();
    return response.ok ? { ok: true, value: data } : { ok: false, problem: problemFrom(error, response) };
  } catch (cause) {
    return { ok: false, problem: networkProblem(cause) };
  }
}
