import assert from 'node:assert/strict';
import test from 'node:test';
import { readAiAgentStream, updateAiAgentReasoning } from './aiAgentStream.ts';

test('streams model reasoning separately and removes only the failed attempt', async () => {
  let reasoning = {};
  const result = await readAiAgentStream(
    stream(
      'event: reasoning\ndata: {"callId":1,"text":"先按产品"}\n\n' +
        'event: reasoning\ndata: {"callId":1,"text":"统计金额。"}\n\n' +
        'event: reasoning\ndata: {"callId":2,"text":"失败片段"}\n\n' +
        'event: reasoning_reset\ndata: {"callId":2}\n\n' +
        'event: reset\ndata: {}\n\n' +
        'event: reasoning\ndata: {"callId":3,"text":"使用条形图。"}\n\n' +
        'event: result\ndata: {"answer":"完成","reasoningContent":"先按产品统计金额。\\n\\n使用条形图。"}\n\n'
    ),
    (event) => {
      reasoning = updateAiAgentReasoning(reasoning, event);
    }
  );
  assert.deepEqual(reasoning, { 1: '先按产品统计金额。', 3: '使用条形图。' });
  assert.equal(Object.values(reasoning).join('\n\n'), result.reasoningContent);
});

function stream(text, chunkSize = 1) {
  const bytes = new TextEncoder().encode(text);
  return new ReadableStream({
    start(controller) {
      for (let i = 0; i < bytes.length; i += chunkSize) controller.enqueue(bytes.slice(i, i + chunkSize));
      controller.close();
    },
  });
}

test('handles split UTF-8 characters, CRLF, comments, resets and final result', async () => {
  const events = [];
  const result = await readAiAgentStream(
    stream(
      ': ping\r\n\r\nevent: stage\r\ndata: {"summary":"生成 SQL"}\r\n\r\n' +
        'event: reset\r\ndata: {}\r\n\r\nevent: delta\r\ndata: {"text":"查询完成"}\r\n\r\n' +
        'event: result\r\ndata: {"answer":"完成","charts":[]}\r\n\r\n'
    ),
    (event) => events.push(event)
  );
  assert.deepEqual(result, { answer: '完成', charts: [] });
  assert.deepEqual(
    events.map((e) => e.event),
    ['stage', 'reset', 'delta']
  );
  assert.equal(events[2].data.text, '查询完成');
});

test('handles multiline JSON and a final frame without a trailing empty line', async () => {
  const result = await readAiAgentStream(stream('event: result\ndata: {"answer":\ndata: "done"}', 100), () => {});
  assert.equal(result.answer, 'done');
});

test('server errors, cancellations and truncated streams do not produce an answer', async () => {
  await assert.rejects(
    readAiAgentStream(stream('event: error\ndata: {"message":"配置错误"}\n\n'), () => {}),
    /配置错误/
  );
  await assert.rejects(
    readAiAgentStream(stream('event: cancelled\ndata: {}\n\n'), () => {}),
    { name: 'AbortError' }
  );
  await assert.rejects(
    readAiAgentStream(stream('event: delta\ndata: {"text":"partial"}\n\n'), () => {}),
    /连接中断/
  );
  await assert.rejects(
    readAiAgentStream(stream('event: result\ndata: null\n\n'), () => {}),
    /连接中断/
  );
});

test('cancels the reader immediately after final result, without waiting for the network to close', async () => {
  let cancelled = false;
  const body = new ReadableStream({
    start(controller) {
      controller.enqueue(new TextEncoder().encode('event: result\ndata: {"answer":"ok"}\n\n'));
    },
    cancel() {
      cancelled = true;
    },
  });
  assert.equal((await readAiAgentStream(body, () => {})).answer, 'ok');
  assert.equal(cancelled, true);
});
