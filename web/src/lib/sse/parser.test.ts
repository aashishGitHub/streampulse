// @vitest-environment node
import { describe, expect, it } from 'vitest';
import { SseParser } from './parser';

describe('SseParser', () => {
  it('parses one event with id, name and data', () => {
    const parser = new SseParser();
    const messages = parser.push('id: 1791822805866-0\nevent: metric\ndata: {"value":1}\n\n');
    expect(messages).toEqual([{ id: '1791822805866-0', event: 'metric', data: '{"value":1}' }]);
  });

  it('names the event "message" when no event field is sent', () => {
    expect(new SseParser().push('data: hi\n\n')).toEqual([{ id: '', event: 'message', data: 'hi' }]);
  });

  it('joins several data lines with a newline', () => {
    expect(new SseParser().push('data: a\ndata: b\n\n')[0]?.data).toBe('a\nb');
  });

  it('ignores comment lines, which is how the server heartbeats', () => {
    expect(new SseParser().push(': keep-alive\n\n')).toEqual([]);
  });

  it('does not dispatch a block that has no data', () => {
    expect(new SseParser().push('event: metric\nid: 5-0\n\n')).toEqual([]);
  });

  it('reassembles an event split across chunks, even mid-line', () => {
    const parser = new SseParser();
    expect(parser.push('id: 7-0\neve')).toEqual([]);
    expect(parser.push('nt: metric\ndata: {"a"')).toEqual([]);
    expect(parser.push(':1}\n\n')).toEqual([{ id: '7-0', event: 'metric', data: '{"a":1}' }]);
  });

  it('treats CRLF split across two chunks as one line break', () => {
    const parser = new SseParser();
    expect(parser.push('data: x\r')).toEqual([]);
    expect(parser.push('\n\r\n')).toEqual([{ id: '', event: 'message', data: 'x' }]);
  });

  it('accepts bare CR line endings, releasing a trailing CR with the next chunk', () => {
    const parser = new SseParser();
    expect(parser.push('data: x\r\rdata: y\r\r').map((m) => m.data)).toEqual(['x']);
    expect(parser.push('data: z').map((m) => m.data)).toEqual(['y']);
  });

  it('returns several events from one chunk, in order', () => {
    const messages = new SseParser().push('id: 1\ndata: a\n\nid: 2\ndata: b\n\n');
    expect(messages.map((m) => [m.id, m.data])).toEqual([['1', 'a'], ['2', 'b']]);
  });

  it('keeps the last id for later events that do not repeat it', () => {
    const parser = new SseParser();
    parser.push('id: 9-0\ndata: a\n\n');
    expect(parser.push('data: b\n\n')[0]?.id).toBe('9-0');
  });

  it('strips exactly one space after the colon', () => {
    expect(new SseParser().push('data:  two\n\n')[0]?.data).toBe(' two');
    expect(new SseParser().push('data:none\n\n')[0]?.data).toBe('none');
  });

  it('ignores an id that contains a NUL character', () => {
    expect(new SseParser().push('id: a\u0000b\ndata: x\n\n')[0]?.id).toBe('');
  });

  it('does not reuse the event name of the previous event', () => {
    const parser = new SseParser();
    parser.push('event: alert\ndata: a\n\n');
    expect(parser.push('data: b\n\n')[0]?.event).toBe('message');
  });
});
