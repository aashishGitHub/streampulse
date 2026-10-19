// @vitest-environment node
import { describe, expect, it } from 'vitest';
import { networkProblem, problemFrom } from './problem';

const response = (status: number) => new Response(null, { status });

describe('problemFrom', () => {
  it('reads an RFC 7807 body', () => {
    const body = { type: 'urn:x', title: 'Dependency cycle', status: 422, detail: 'CDN -> PLAYER -> CDN', traceId: 'abc123' };
    expect(problemFrom(body, response(422))).toEqual({ title: 'Dependency cycle', status: 422, detail: 'CDN -> PLAYER -> CDN', traceId: 'abc123' });
  });

  it('takes the status from the response when the body omits it', () => {
    expect(problemFrom({ title: 'Bad' }, response(400))).toEqual({ title: 'Bad', status: 400 });
  });

  it('copes with a body that has only a detail', () => {
    expect(problemFrom({ detail: 'ruleId must not be blank' }, response(400))).toMatchObject({
      title: 'Request failed (HTTP 400)',
      detail: 'ruleId must not be blank',
    });
  });

  it('shows a plain-text body as the detail', () => {
    expect(problemFrom('Bad Gateway', response(502))).toEqual({ title: 'Request failed (HTTP 502)', detail: 'Bad Gateway', status: 502 });
  });

  it('copes with an empty body', () => {
    expect(problemFrom(undefined, response(500))).toEqual({ title: 'Request failed (HTTP 500)', status: 500 });
  });

  it('ignores fields of the wrong type rather than rendering them', () => {
    expect(problemFrom({ title: 7, detail: { a: 1 }, traceId: false }, response(400))).toEqual({
      title: 'Request failed (HTTP 400)',
      status: 400,
    });
  });
});

describe('problemFrom validation errors', () => {
  it('keeps the per-field messages the server lists in errors', () => {
    const body = { title: 'Bad Request', detail: 'Validation failed', errors: ['threshold: must be a number', 'metric: must not be blank'] };
    expect(problemFrom(body, response(400)).errors).toEqual(['threshold: must be a number', 'metric: must not be blank']);
  });

  it('drops an errors field that is not a list of strings', () => {
    expect(problemFrom({ title: 'x', errors: 'nope' }, response(400)).errors).toBeUndefined();
    expect(problemFrom({ title: 'x', errors: [1, 'ok', null] }, response(400)).errors).toEqual(['ok']);
  });
});

describe('networkProblem', () => {
  it('says the server could not be reached and keeps the reason', () => {
    expect(networkProblem(new TypeError('Failed to fetch'))).toEqual({
      title: 'Could not reach the server',
      detail: 'Failed to fetch',
    });
  });
});
