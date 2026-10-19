// @vitest-environment node
import { describe, expect, it } from 'vitest';
import { parseRoute } from './route';

describe('parseRoute', () => {
  it.each([
    ['#/metrics', 'metrics'],
    ['#/alerts', 'alerts'],
    ['#/rules', 'rules'],
  ])('reads %s', (hash, route) => {
    expect(parseRoute(hash)).toBe(route);
  });

  it.each(['', '#', '#/', '#/nope', '#/alerts/extra', 'alerts'])('falls back to metrics for "%s"', (hash) => {
    expect(parseRoute(hash)).toBe('metrics');
  });
});
