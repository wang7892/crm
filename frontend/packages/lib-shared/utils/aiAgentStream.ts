import type { AiAgentProcessDetail } from '../api/modules/aiAgent';

export interface AiAgentStreamEvent {
  event: string;
  data: {
    name?: string;
    summary?: string;
    details?: AiAgentProcessDetail[];
    text?: string;
    message?: string;
    callId?: number;
  };
}

/** A failed model attempt removes only its own reasoning, preserving earlier successful calls. */
export function updateAiAgentReasoning(current: Record<string, string>, event: AiAgentStreamEvent) {
  if (event.event !== 'reasoning' && event.event !== 'reasoning_reset') return current;
  if (event.data.callId === undefined) return current;
  const next = { ...current };
  if (event.event === 'reasoning_reset') delete next[event.data.callId];
  else next[event.data.callId] = (next[event.data.callId] || '') + (event.data.text || '');
  return next;
}

/** Handles split UTF-8 characters, CRLF, comments and multiline SSE data. */
export async function readAiAgentStream<T>(
  stream: ReadableStream<Uint8Array>,
  onEvent: (event: AiAgentStreamEvent) => void
): Promise<T> {
  const reader = stream.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  let event = 'message';
  let data: string[] = [];
  let result: T | undefined;
  const dispatch = () => {
    if (!data.length) {
      event = 'message';
      return;
    }
    const value = JSON.parse(data.join('\n'));
    data = [];
    const name = event;
    event = 'message';
    if (name === 'result') {
      result = value as T;
      return;
    }
    if (name === 'error') throw new Error(value.message || '问答处理失败');
    if (name === 'cancelled') throw new DOMException('已停止回答', 'AbortError');
    onEvent({ event: name, data: value });
  };
  const line = (value: string) => {
    if (value === '') {
      dispatch();
      return;
    }
    if (value.startsWith(':')) return;
    const colon = value.indexOf(':');
    const field = colon < 0 ? value : value.slice(0, colon);
    const content = colon < 0 ? '' : value.slice(colon + 1).replace(/^ /, '');
    if (field === 'event') event = content;
    if (field === 'data') data.push(content);
  };
  try {
    while (result === undefined) {
      // Stream chunks must be consumed in order before parsing the next SSE frame.
      // eslint-disable-next-line no-await-in-loop
      const chunk = await reader.read();
      buffer += decoder.decode(chunk.value, { stream: !chunk.done });
      if (buffer.length > 2_000_000) throw new Error('流式消息过长');
      let newline = buffer.indexOf('\n');
      while (newline >= 0 && result === undefined) {
        line(buffer.slice(0, newline).replace(/\r$/, ''));
        buffer = buffer.slice(newline + 1);
        newline = buffer.indexOf('\n');
      }
      if (chunk.done) {
        if (buffer) line(buffer.replace(/\r$/, ''));
        dispatch();
        break;
      }
    }
    if (result === undefined || result === null) throw new Error('回答连接中断，请重试');
    return result;
  } finally {
    await reader.cancel().catch(() => undefined);
    reader.releaseLock();
  }
}
