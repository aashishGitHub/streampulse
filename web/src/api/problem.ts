export interface Problem {
  title: string;
  detail?: string;
  status?: number;
  traceId?: string;
  /** `field: message` per offending field, on validation failures. */
  errors?: string[];
}

const str = (value: unknown): string | undefined => (typeof value === 'string' && value !== '' ? value : undefined);

// Reads application/problem+json (RFC 7807). The body arrives as whatever the client could
// parse, so every field is checked before it is shown.
export function problemFrom(body: unknown, response: Response): Problem {
  const fields = typeof body === 'object' && body !== null ? (body as Record<string, unknown>) : {};
  const status = typeof fields.status === 'number' ? fields.status : response.status;
  const detail = str(fields.detail) ?? str(body);
  const traceId = str(fields.traceId);
  const errors = Array.isArray(fields.errors) ? fields.errors.filter((e): e is string => typeof e === 'string') : undefined;

  return {
    title: str(fields.title) ?? `Request failed (HTTP ${response.status})`,
    ...(detail !== undefined && { detail }),
    status,
    ...(traceId !== undefined && { traceId }),
    ...(errors !== undefined && { errors }),
  };
}

export function networkProblem(error: unknown): Problem {
  return {
    title: 'Could not reach the server',
    detail: error instanceof Error ? error.message : String(error),
  };
}
