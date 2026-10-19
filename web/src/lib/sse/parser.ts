export interface SseMessage {
  event: string;
  data: string;
  id: string;
}

const LINE_BREAK = /\r\n|\r(?!$)|\n/;

// Implements the field handling of the WHATWG event-stream format. A trailing CR is held back
// until the next chunk, because it may be the first half of a CRLF.
export class SseParser {
  private pending = '';
  private eventName = '';
  private dataLines: string[] = [];
  private lastId = '';

  push(chunk: string): SseMessage[] {
    const lines = (this.pending + chunk).split(LINE_BREAK);
    this.pending = lines.pop() ?? '';

    const messages: SseMessage[] = [];
    for (const line of lines) {
      const message = this.consume(line);
      if (message) messages.push(message);
    }
    return messages;
  }

  private consume(line: string): SseMessage | null {
    if (line === '') return this.dispatch();
    if (line.startsWith(':')) return null;

    const colon = line.indexOf(':');
    const field = colon === -1 ? line : line.slice(0, colon);
    let value = colon === -1 ? '' : line.slice(colon + 1);
    if (value.startsWith(' ')) value = value.slice(1);

    if (field === 'event') this.eventName = value;
    else if (field === 'data') this.dataLines.push(value);
    else if (field === 'id' && !value.includes('\u0000')) this.lastId = value;
    return null;
  }

  private dispatch(): SseMessage | null {
    const hadData = this.dataLines.length > 0;
    const message: SseMessage = {
      event: this.eventName || 'message',
      data: this.dataLines.join('\n'),
      id: this.lastId,
    };
    this.eventName = '';
    this.dataLines = [];
    return hadData ? message : null;
  }
}
