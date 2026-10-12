import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { mkdir } from 'node:fs/promises';
import path from 'node:path';

const require = createRequire(import.meta.url);
const { chromium } = require(process.argv[2] || 'playwright');
const origin = process.env.AGENT_TEST_ORIGIN || 'http://127.0.0.1:5173';
const output = path.resolve('target/ai-agent-thinking');
await mkdir(output, { recursive: true });
const browser = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe' });
const errors = [];
const reasoningContent =
  '用户问印尼市场前五大产品。需要按 `product_name` 汇总金额，筛选印尼客户，再按金额降序取前五名。\n\n产品名称较长，使用条形图便于比较。<img src=x onerror="window.traceInjected=true">';
const result = {
  sessionId: 'session',
  messageId: 'answer',
  answer: '查询完成',
  reasoningContent,
  durationMs: 5000,
  totalTokens: 1000,
  processSteps: [
    { name: 'request', summary: '准备问答' },
    { name: 'sql_generation', summary: '生成 SQL' },
  ],
};
try {
  for (const width of [1440, 390]) {
    const page = await browser.newPage({ viewport: { width, height: 900 } });
    page.on('pageerror', (error) => errors.push(error.message));
    let history = false;
    await page.route('**/ai-agent/**', async (route) => {
      const pathname = new URL(route.request().url()).pathname;
      let data = [];
      if (pathname.endsWith('/datasources'))
        data = [{ id: 'source', name: '测试数据源', type: 'MYSQL', selectedTables: ['contract_info'], tables: [] }];
      if (pathname.endsWith('/sessions') && history)
        data = [{ id: 'session', title: '印尼产品统计', updateTime: Date.now() }];
      if (pathname.endsWith('/sessions/session/messages'))
        data = [
          { id: 'user', role: 'user', content: '印尼市场前五大产品' },
          {
            id: 'answer',
            role: 'assistant',
            content: result.answer,
            evidenceJson: JSON.stringify({ response: result }),
          },
          {
            id: 'legacy',
            role: 'assistant',
            content: '旧问答没有模型思考文本',
            evidenceJson: JSON.stringify({ response: { ...result, reasoningContent: undefined } }),
          },
        ];
      await route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 200, data }) });
    });
    await page.addInitScript(
      ({ result }) => {
        const original = window.fetch.bind(window);
        window.fetch = (input, init) => {
          if (!String(input).includes('/ai-agent/chat/stream')) return original(input, init);
          const encoder = new TextEncoder();
          const body = new ReadableStream({
            start(controller) {
              const send = (event, data) =>
                controller.enqueue(encoder.encode(`event: ${event}\ndata: ${JSON.stringify(data)}\n\n`));
              send('stage', { name: 'request', summary: '准备问答' });
              window.__startThinking = () =>
                send('reasoning', { callId: 1, text: '用户问印尼市场前五大产品。需要按 `product_name` 汇总金额，' });
              window.__continueThinking = () => {
                send('reasoning', { callId: 1, text: '筛选印尼客户，再按金额降序取前五名。' });
                send('reasoning', { callId: 2, text: '失败请求的片段' });
                send('reasoning_reset', { callId: 2 });
                send('reset', {});
                send('reasoning', {
                  callId: 3,
                  text: '产品名称较长，使用条形图便于比较。<img src=x onerror="window.traceInjected=true">',
                });
              };
              window.__finishThinking = () => {
                send('result', result);
                controller.close();
              };
            },
          });
          return Promise.resolve(new Response(body, { headers: { 'Content-Type': 'text/event-stream' } }));
        };
      },
      { result }
    );
    await page.goto(`${origin}/tests/ai-agent-smoke.html?mode=chat`);
    await page.waitForFunction(() => window.__agentSmokeReady, undefined, { timeout: 60000 });
    await page.locator('.composer-input textarea').fill('印尼市场前五大产品');
    await page.locator('.send-button').click();
    const thinking = page.locator('.message-row--assistant .ai-agent-thinking').last();
    await thinking.getByText('思考中', { exact: true }).waitFor();
    assert.equal(await thinking.locator('.thinking-content').count(), 0);
    assert.equal(await page.locator('.message-row--user .ai-agent-thinking').count(), 0);
    await page.evaluate(() => window.__startThinking());
    await thinking
      .locator('.thinking-content')
      .getByText('用户问印尼市场前五大产品。需要按', { exact: false })
      .waitFor();
    assert.equal(await thinking.locator('code').innerText(), 'product_name');
    assert.equal(await page.locator('.process-stages, .stage-number').count(), 0);
    assert.ok(!(await thinking.innerText()).includes('准备问答'));
    await page.evaluate(() => window.__continueThinking());
    await thinking.getByText('产品名称较长，使用条形图便于比较。', { exact: false }).waitFor();
    assert.ok(!(await thinking.innerText()).includes('失败请求的片段'));
    assert.ok((await thinking.innerText()).includes('筛选印尼客户，再按金额降序取前五名。'));
    assert.equal(await thinking.locator('img, script').count(), 0);
    await page.screenshot({ path: `${output}/stream-${width}.png`, fullPage: true });
    await page.evaluate(() => window.__finishThinking());
    await page.getByText('查询完成', { exact: true }).waitFor();
    await thinking.getByRole('button', { name: '思考过程' }).click();
    assert.equal(await thinking.locator('.thinking-content').innerText(), reasoningContent.replaceAll('`', ''));
    await page.getByRole('button', { name: '执行详情' }).click();
    await page.locator('dialog[open]').waitFor();
    assert.ok((await page.locator('dialog[open]').innerText()).includes('生成 SQL'));
    await page.keyboard.press('Escape');
    history = true;
    await page.reload();
    await page.waitForFunction(() => window.__agentSmokeReady, undefined, { timeout: 60000 });
    await page.getByText('旧问答没有模型思考文本', { exact: true }).waitFor();
    const historyThinking = page.locator('.message-row--assistant .ai-agent-thinking');
    assert.equal(await historyThinking.count(), 2);
    await historyThinking.first().getByRole('button', { name: '思考过程' }).click();
    assert.equal(
      await historyThinking.first().locator('.thinking-content').innerText(),
      reasoningContent.replaceAll('`', '')
    );
    await historyThinking.last().getByRole('button', { name: '思考过程' }).click();
    assert.equal(
      (await historyThinking.last().locator('.thinking-content').innerText()).trim(),
      '本次回答没有可展示的思考内容。'
    );
    assert.equal(await page.locator('.message-row--user .ai-agent-thinking').count(), 0);
    assert.equal(await page.locator('.process-stages, .stage-number').count(), 0);
    assert.equal(await page.evaluate(() => window.traceInjected), undefined);
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth + 2), false);
    await page.screenshot({ path: `${output}/history-${width}.png`, fullPage: true });
    await page.close();
  }
  assert.deepEqual(errors, []);
  console.log(
    'Thinking UI passed at 1440px and 390px: persistent answer buttons, explicit empty state, live provider text, retry reset, persisted history, no user-message buttons or numbered stages.'
  );
} finally {
  await browser.close();
}
