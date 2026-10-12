import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { mkdir } from 'node:fs/promises';
import path from 'node:path';

const require = createRequire(import.meta.url);
const { chromium } = require(process.argv[2] || 'playwright');
const origin = process.env.AGENT_TEST_ORIGIN || 'http://127.0.0.1:5173';
const output = path.resolve('target/ai-agent-execution');
await mkdir(output, { recursive: true });
const browser = await chromium.launch({
  executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe',
  headless: true,
});
const errors = [];
try {
  for (const width of [1440, 390]) {
    const page = await browser.newPage({ viewport: { width, height: 900 } });
    page.on('pageerror', (error) => errors.push(error.message));
    const savedMessages = [];
    let count = 0;
    await page.route('**/ai-agent/**', async (route) => {
      const pathname = new URL(route.request().url()).pathname;
      let data = [];
      if (pathname.endsWith('/chat/stream')) {
        const { question } = route.request().postDataJSON();
        const failed = question === '测试失败';
        const zero = question === '测试零值';
        const unknown = question === '测试未知';
        const legacyKeywords = question === '统计客户';
        const result = {
          sessionId: 'session',
          messageId: `message-${++count}`,
          answer: failed ? 'SQL 查询失败' : '查询完成',
          queryQuestion: question,
          queryError: failed ? 'SQL 查询失败' : undefined,
          durationMs: zero ? 0 : 8720,
          totalTokens: unknown ? null : zero ? 0 : 14001,
          modelCalls:
            zero || unknown
              ? []
              : [
                  {
                    stage: 'sql_generation',
                    provider: 'primary',
                    model: 'test',
                    durationMs: 6000,
                    promptTokens: 9000,
                    completionTokens: 1000,
                    totalTokens: 10000,
                    status: 'SUCCESS',
                  },
                  {
                    stage: 'chart',
                    provider: 'primary',
                    model: 'test',
                    durationMs: 2000,
                    totalTokens: 4001,
                    status: 'SUCCESS',
                  },
                  {
                    stage: 'guess',
                    provider: 'primary',
                    model: 'test',
                    durationMs: 230,
                    totalTokens: 999,
                    status: 'SUCCESS',
                  },
                ],
          processSteps: [
            { name: 'request', summary: '准备问答', durationMs: 30, status: 'SUCCESS' },
            { name: 'question_context', summary: '查询问题', durationMs: 10, status: 'SUCCESS' },
            {
              name: 'extract_keywords',
              summary: '提取关键词',
              durationMs: 200,
              status: 'SUCCESS',
              details: legacyKeywords ? [] : [{ type: 'text', title: '提取结果', content: '区域,埃及' }],
            },
            { name: 'table_selection', summary: '选择数据表', durationMs: 100, status: 'SUCCESS' },
            {
              name: 'table_selection_result',
              summary: '选表结果与字段',
              durationMs: 20,
              status: 'SUCCESS',
              details: [
                ...(legacyKeywords ? [{ type: 'text', title: '提取关键词', content: '区域,埃及' }] : []),
                { type: 'text', title: '扩展关键词', content: '区域,埃及,客户地区' },
                { type: 'table', title: 'customer_info', content: '客户表与字段' },
              ],
            },
            { name: 'knowledge', summary: '检索问数知识', durationMs: 100, status: 'SUCCESS' },
            {
              name: 'terminology',
              summary: '匹配业务术语',
              durationMs: 120,
              status: 'SUCCESS',
              details: [
                {
                  type: 'text',
                  title: '客户地区',
                  content: '从客户名称匹配地区。<img src=x onerror="window.traceInjected=true">',
                },
              ],
            },
            { name: 'sql_examples', summary: '匹配 SQL 示例', durationMs: 10, status: 'SUCCESS' },
            { name: 'sample_data', summary: '读取样例数据', durationMs: 20, status: 'SUCCESS' },
            {
              name: 'sql_generation',
              summary: '生成查询 SQL',
              durationMs: 6000,
              status: 'SUCCESS',
              details: [
                {
                  type: 'sql',
                  title: '生成 SQL',
                  content: "SELECT '<script>window.traceInjected=true</script>' AS note FROM customer_info",
                },
              ],
            },
            { name: 'sql_generated', summary: 'SQL 与查询说明', durationMs: 10, status: 'SUCCESS' },
            {
              name: 'sql_query',
              summary: failed ? 'SQL 执行失败' : '执行查询',
              durationMs: 480,
              status: failed ? 'FAILED' : 'SUCCESS',
            },
            {
              name: 'query_result',
              summary: '执行结果',
              durationMs: 10,
              status: 'SUCCESS',
              details: [{ type: 'text', title: '执行结果', content: '返回 2 条数据' }],
            },
            { name: 'chart', summary: '生成图表', durationMs: 2000, status: 'SUCCESS' },
            { name: 'guess', summary: '生成推荐追问', durationMs: 120, status: 'SUCCESS' },
          ],
        };
        savedMessages.push(
          { id: `user-${count}`, role: 'user', content: question },
          {
            id: result.messageId,
            role: 'assistant',
            content: result.answer,
            evidenceJson: JSON.stringify({ response: result }),
          }
        );
        return route.fulfill({
          contentType: 'text/event-stream',
          body: `event: result\ndata: ${JSON.stringify(result)}\n\n`,
        });
      }
      if (pathname.endsWith('/datasources'))
        data = [{ id: 'source', name: '测试数据源', type: 'MYSQL', selectedTables: ['customer_info'], tables: [] }];
      if (pathname.endsWith('/sessions') && savedMessages.length)
        data = [{ id: 'session', title: '统计测试', updateTime: Date.now() }];
      if (pathname.endsWith('/sessions/session/messages')) data = savedMessages;
      return route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 200, data }) });
    });
    await page.goto(`${origin}/tests/ai-agent-smoke.html?mode=chat`);
    await page.waitForFunction(() => window.__agentSmokeReady, undefined, { timeout: 60000 });
    const ask = async (question) => {
      const expected = count + 1;
      await page.locator('.send-button[aria-label="停止回答"]').waitFor({ state: 'hidden' });
      await page.locator('.composer-input textarea').fill(question);
      await page.waitForFunction((text) => {
        let instance = document.querySelector('.composer-input textarea').__vueParentComponent;
        while (instance && !('questionInput' in instance.setupState)) instance = instance.parent;
        return instance?.setupState.questionInput === text;
      }, question);
      await page.locator('.composer-input textarea').press('Enter');
      try {
        await page.locator(`.ai-agent-execution[data-message-id="message-${expected}"]`).waitFor();
      } catch (error) {
        await page.screenshot({ path: `${output}/failure-${width}.png` });
        console.error({
          errors,
          body: await page.locator('body').innerText(),
          responses: count,
          expected,
          footers: await page.locator('.ai-agent-execution').count(),
        });
        throw error;
      }
    };
    await ask('统计客户');
    const footer = page.locator('.ai-agent-execution').first();
    assert.match(await footer.innerText(), /消耗 Tokens 14001/);
    assert.match(await footer.innerText(), /耗时 8\.72 s/);
    assert.equal(await page.getByText('模型调用记录', { exact: true }).count(), 0);
    await footer.screenshot({ path: `${output}/footer-${width}.png` });
    await footer.getByRole('button', { name: '执行详情', exact: true }).click();
    const drawer = page.locator('.execution-drawer[open]');
    await drawer.waitFor();
    assert.match(await drawer.locator('.execution-overview').innerText(), /14001/);
    assert.match(await drawer.locator('.execution-overview').innerText(), /8\.72 s/);
    assert.equal(await drawer.locator('[data-stage="guess"]').count(), 0);
    assert.deepEqual(await drawer.locator('.execution-step-title').allTextContents(), [
      '提取关键词',
      '匹配术语',
      '匹配 SQL 示例',
      '匹配数据表 (Schema)',
      '生成 SQL',
      '执行 SQL',
      '生成图表结构',
    ]);
    assert.match(await drawer.locator('[data-stage="sql_generation"] summary').innerText(), /10000 tokens/);
    assert.match(await drawer.locator('[data-stage="sql_generation"] summary').innerText(), /6\.01 s/);
    await page.screenshot({ path: `${output}/steps-${width}.png` });
    await drawer.locator('[data-stage="extract_keywords"] summary').click();
    assert.match(
      await drawer.locator('[data-stage="extract_keywords"] .execution-detail').innerText(),
      /提取结果\s+区域,埃及/
    );
    await drawer.locator('[data-stage="table_selection"] summary').click();
    assert.match(await drawer.locator('[data-stage="table_selection"]').innerText(), /扩展关键词/);
    assert.doesNotMatch(await drawer.locator('[data-stage="table_selection"]').innerText(), /提取关键词/);
    await drawer.locator('[data-stage="table_selection"] summary').click();
    await drawer.locator('[data-stage="extract_keywords"]').screenshot({ path: `${output}/keywords-${width}.png` });
    for (const name of ['terminology', 'sql_generation'])
      await drawer.locator(`[data-stage="${name}"] summary`).click();
    assert.match(await drawer.innerText(), /输入 Tokens 9000/);
    assert.equal(await drawer.locator('img, script').count(), 0);
    assert.equal(await page.evaluate(() => window.traceInjected), undefined);
    const bounds = await drawer.boundingBox();
    assert.equal(Math.round(bounds.width), width < 768 ? width : Math.max(width / 2, 600));
    assert.equal(await drawer.evaluate((element) => element.scrollWidth > element.clientWidth + 2), false);
    await page.screenshot({ path: `${output}/details-${width}.png` });
    await page.keyboard.press('Escape');
    assert.equal(await page.locator('.execution-drawer[open]').count(), 0);
    assert.equal(await footer.getByRole('button').evaluate((element) => element === document.activeElement), true);
    await page.reload();
    await page.waitForFunction(() => window.__agentSmokeReady, undefined, { timeout: 60000 });
    await footer.waitFor();
    assert.match(await footer.innerText(), /消耗 Tokens 14001/);
    assert.match(await footer.innerText(), /耗时 8\.72 s/);
    await footer.getByRole('button', { name: '执行详情', exact: true }).click();
    await drawer.locator('[data-stage="extract_keywords"] summary').click();
    assert.match(await drawer.locator('[data-stage="extract_keywords"] .execution-detail').innerText(), /区域,埃及/);
    await drawer.getByRole('button', { name: '关闭执行详情' }).click();
    await ask('测试失败');
    await page.locator('.ai-agent-execution').last().getByRole('button').click();
    assert.equal(await drawer.locator('[data-stage="sql_query"] .execution-status').getAttribute('aria-label'), '失败');
    assert.equal(await drawer.locator('.execution-error').innerText(), 'SQL 查询失败');
    await drawer.locator('[data-stage="extract_keywords"] summary').click();
    assert.match(await drawer.locator('[data-stage="extract_keywords"] .execution-detail').innerText(), /区域,埃及/);
    await drawer.getByRole('button', { name: '关闭执行详情' }).click();
    await ask('测试零值');
    assert.match(await page.locator('.ai-agent-execution').last().innerText(), /消耗 Tokens 0/);
    assert.match(await page.locator('.ai-agent-execution').last().innerText(), /耗时 0\.00 s/);
    await ask('测试未知');
    assert.match(await page.locator('.ai-agent-execution').last().innerText(), /消耗 Tokens —/);
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth + 2), false);
    await page.close();
  }
  assert.deepEqual(errors, []);
  console.log(
    'Execution footer/drawer passed at 1440px and 390px: totals, step timing/status, safe SQL/term content, history, zero/unknown values and keyboard dismissal.'
  );
} finally {
  await browser.close();
}
