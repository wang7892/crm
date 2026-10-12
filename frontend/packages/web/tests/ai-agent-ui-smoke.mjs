import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { mkdir } from 'node:fs/promises';
import path from 'node:path';

const require = createRequire(import.meta.url);
const { chromium } = require(process.argv[2] || 'playwright');
const origin = process.env.AGENT_TEST_ORIGIN || 'http://127.0.0.1:5173';
const output = path.resolve('target/ai-agent-ui');
await mkdir(output, { recursive: true });
const browser = await chromium.launch({
  executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe',
  headless: true,
});
const source = {
  id: 'test-source',
  name: 'Sales',
  type: 'MYSQL',
  host: 'localhost',
  port: 3306,
  database: 'sales',
  username: 'reader',
  revision: 1,
  selectedTables: ['customers', 'orders'],
  tableCount: 2,
  relations: [],
  tables: ['customers', 'orders'].map((name) => ({
    name,
    comment: '业务数据',
    columns: [{ name: 'id', type: 'INT', comment: '主键' }],
  })),
};
const chart = (count) => ({
  title: '销售额',
  type: 'column',
  dimension: '客户',
  categories: Array.from({ length: count }, (_, i) => `客户${i + 1}`),
  series: [{ name: '金额', values: Array.from({ length: count }, (_, i) => i + 100) }],
  partial: false,
  coverage: '已绘制本次查询的全部结果。',
});
const errors = [];
try {
  for (const width of [1440, 390]) {
    let savedSource;
    let chatCount = 0;
    const requests = [];
    const savedMessages = [];
    const page = await browser.newPage({ viewport: { width, height: 900 } });
    page.on('pageerror', (error) => {
      errors.push(error.message);
      console.error(error.message);
    });
    await page.route('**/ai-agent/**', async (route) => {
      const pathname = new URL(route.request().url()).pathname;
      let data = [];
      if (pathname.endsWith('/chat/stream')) {
        const request = route.request().postDataJSON();
        requests.push(request);
        assert.equal(request.stream, true);
        const count = request.limitRows ? 234 : 1234;
        const result = {
          sessionId: 'session',
          messageId: `message-${++chatCount}`,
          answer: '查询完成',
          reasoningContent:
            '用户要统计客户销售额。根据客户地区术语，从客户名称匹配印度、香港、义乌，再按金额汇总。<img src=x onerror="window.traceInjected=true">',
          llmProvider: 'primary',
          llmModel: 'test',
          charts: [chart(count)],
          points: Array.from({ length: count }, (_, i) => `第 ${i + 1} 条\n客户：客户${i + 1}\n金额：${i + 100}`),
          pageInfo: {
            total: count,
            offset: 0,
            returned: count,
            remaining: 0,
            hasMore: false,
            continuable: false,
            unit: '组',
          },
          sqlQueries: ['SELECT 1 AS amount'],
          processSteps: [
            { name: 'knowledge', summary: '检索适用术语及 SQL 示例' },
            {
              name: 'terminology',
              summary: '已向查询模型提供 1 条业务术语',
              details: [
                {
                  type: 'terminology',
                  title: '客户地区识别',
                  content:
                    '同义词：地区、国家\n优先使用地区字段；字段为空时，从客户名称匹配印度、香港、义乌等地区。<img src=x onerror="window.traceInjected=true">',
                },
              ],
            },
            {
              name: 'sql_examples',
              summary: '已向查询模型提供 1 条 SQL 示例',
              details: [
                {
                  type: 'sql',
                  title: '印度客户查询',
                  content:
                    "SELECT customer_name AS name FROM customer_info WHERE region = '印度' OR ((region IS NULL OR TRIM(region) = '') AND customer_name LIKE '%印度%')",
                },
              ],
            },
            {
              name: 'table_selection_result',
              summary: '已选择相关表：customer_info',
              details: [
                {
                  type: 'table',
                  title: 'customer_info',
                  content:
                    '客户表\n可供生成 SQL 参考的字段：\nregion (VARCHAR) 客户地区\ncustomer_name (VARCHAR) 客户名称',
                },
              ],
            },
            {
              name: 'sql_generated',
              summary: '已生成 SQL，等待只读与访问范围校验',
              details: [
                {
                  type: 'text',
                  title: '模型查询说明',
                  content: '按客户地区术语筛选印度客户，地区为空时从客户名称补充匹配，并按客户名称排序。',
                },
                {
                  type: 'sql',
                  title: '模型生成的 SQL（待校验）',
                  content:
                    "SELECT customer_name AS name, '<script>window.traceInjected=true</script>' AS note FROM customer_info WHERE customer_name LIKE '%印度%' ORDER BY name",
                },
              ],
            },
            { name: 'sql_query', summary: '校验只读 SQL、访问范围并执行查询' },
            {
              name: 'query_result',
              summary: '只读与访问范围校验通过，查询完成',
              details: [
                {
                  type: 'text',
                  title: '执行结果',
                  content: `本次返回：${count} 条\n匹配总数：${count} 条\n本次查询结果已全部返回`,
                },
              ],
            },
          ],
        };
        savedMessages.push({ id: `user-${chatCount}`, role: 'user', content: request.question });
        savedMessages.push({
          id: result.messageId,
          role: 'assistant',
          content: result.answer,
          evidenceJson: JSON.stringify({ response: result }),
        });
        return route.fulfill({
          contentType: 'text/event-stream',
          body:
            result.processSteps.map((step) => `event: stage\ndata: ${JSON.stringify(step)}\n\n`).join('') +
            `event: result\ndata: ${JSON.stringify(result)}\n\n`,
        });
      }
      if (pathname.endsWith('/datasources')) {
        if (route.request().method() === 'POST') {
          savedSource = route.request().postDataJSON();
          data = { ...source, ...savedSource };
        } else data = [source];
      }
      assert.ok(!pathname.endsWith('/query-knowledge/settings'), 'Knowledge configuration must stay in the backend');
      assert.ok(!pathname.endsWith('/query-knowledge/reindex'), 'Knowledge indexes must be built in the backend');
      if (pathname.endsWith('/query-knowledge'))
        data = [
          {
            id: 'knowledge',
            kind: 'TERM',
            name: '销售额',
            content: '已审核订单金额',
            synonyms: [],
            allDatasources: true,
            datasourceIds: [],
            enabled: true,
            revision: 1,
            embeddingStatus: 'READY',
          },
        ];
      if (pathname.endsWith('/sessions') && savedMessages.length)
        data = [{ id: 'session', title: '历史问数记录', updateTime: Date.now() }];
      if (pathname.endsWith('/sessions/session/messages')) data = savedMessages;
      await route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 200, data }) });
    });
    await page.goto(`${origin}/tests/ai-agent-smoke.html?mode=chat`);
    await page.waitForFunction(() => window.__agentSmokeReady, undefined, { timeout: 60000 });
    await page.locator('.composer-input textarea').fill('统计客户销售额');
    await page.locator('.send-button').click();
    await page.getByText('查询完成', { exact: true }).waitFor();
    assert.equal(requests[0].limitRows, true);
    assert.equal(await page.locator('.message-detail-table tbody tr').count(), 234);
    assert.ok((await page.locator('.message-table-wrap').innerText()).includes('已获取全部结果'));
    assert.equal(await page.getByRole('button', { name: '继续查看', exact: true }).count(), 0);
    await page.locator('.chart-canvas canvas').waitFor();
    const chartData = await page.evaluate(() => window.__agentChart(0, true));
    assert.equal(chartData.categories.length, 234);
    assert.equal(chartData.categories.at(-1), '客户234');
    assert.equal(chartData.values.at(-1), 333);
    await page.locator('.message-table-scroll').evaluate((element) => {
      element.scrollTop = element.scrollHeight;
    });
    const canvas = await page.locator('.chart-canvas canvas').evaluate((element) => {
      const pixels = element.getContext('2d').getImageData(0, 0, element.width, element.height).data;
      let colored = 0;
      for (let i = 0; i < pixels.length; i += 4)
        if (
          pixels[i + 3] > 0 &&
          Math.max(pixels[i], pixels[i + 1], pixels[i + 2]) - Math.min(pixels[i], pixels[i + 1], pixels[i + 2]) > 30
        )
          colored += 1;
      return { width: element.width, height: element.height, colored };
    });
    assert.ok(canvas.width > 150 && canvas.height > 150 && canvas.colored > 100, JSON.stringify(canvas));
    await page.locator('.chart-modes label').filter({ hasText: '折线图' }).first().click();
    const thinking = page
      .locator('.message-row--assistant')
      .filter({ hasText: '查询完成' })
      .first()
      .locator('.ai-agent-thinking');
    await thinking.locator('.thinking-heading').click();
    assert.ok((await thinking.innerText()).includes('从客户名称匹配印度、香港、义乌'));
    assert.equal(await page.locator('.process-stages, .stage-number').count(), 0);
    assert.equal(await thinking.locator('img, script').count(), 0);
    assert.equal(await page.evaluate(() => window.traceInjected), undefined);
    await page.evaluate(() => window.__agentChart(0, true));
    await page.waitForTimeout(350);
    await page.screenshot({ path: `${output}/chat-${width}.png`, fullPage: true });
    assert.equal(
      await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth + 2),
      false,
      `Chat horizontal overflow at ${width}`
    );
    await page.getByRole('switch', { name: '限制 1000 行数据' }).click();
    await page.locator('.composer-input textarea').fill('查询全部客户');
    await page.locator('.send-button').click();
    await page.getByText('查询完成', { exact: true }).last().waitFor();
    assert.equal(requests[1].limitRows, false);
    assert.equal(await page.locator('.message-detail-table').last().locator('tbody tr').count(), 1234);
    const allChart = await page.evaluate(() => window.__agentChart(1, true));
    assert.equal(allChart.categories.length, 1234);
    assert.equal(allChart.values.at(-1), 1333);
    assert.equal(await page.evaluate(() => localStorage.getItem('crm-ai-agent-limit-rows')), 'false');
    await page.getByRole('button', { name: '查看 SQL' }).last().click();
    await page.locator('.sql-query-code').waitFor();
    assert.ok((await page.locator('.sql-query-code').innerText()).includes('SELECT'));
    await page.keyboard.press('Escape');
    await page.reload();
    await page.waitForFunction(() => window.__agentSmokeReady, undefined, { timeout: 60000 });
    assert.equal(await page.getByRole('switch', { name: '限制 1000 行数据' }).getAttribute('aria-checked'), 'false');
    const historyThinking = page.locator('.ai-agent-thinking').last();
    await historyThinking.waitFor();
    await historyThinking.locator('.thinking-heading').click();
    assert.ok((await historyThinking.innerText()).includes('从客户名称匹配印度、香港、义乌'));
    if (width < 768) await page.getByRole('button', { name: '聊天菜单', exact: true }).click();
    await page.getByRole('button', { name: '配置', exact: true }).click();
    await page.getByRole('button', { name: '选择数据表', exact: true }).click();
    await page.getByRole('textbox', { name: '表业务说明', exact: true }).fill('客户业务说明');
    await page.getByPlaceholder('字段业务说明').first().fill('客户编号');
    await page.getByRole('button', { name: '添加关系', exact: true }).click();
    const relation = page.locator('.relationship-row .n-select');
    for (const [index, value] of [
      [0, 'customers'],
      [1, 'id'],
      [2, 'orders'],
      [3, 'id'],
    ]) {
      await relation.nth(index).click();
      await page.locator('.n-base-select-menu').last().getByText(value, { exact: true }).click();
    }
    await page.locator('.relationship-heading h3').click();
    await page.waitForFunction(() =>
      [...document.querySelectorAll('.n-base-select-menu')].every(
        (element) => !element.getClientRects().length || getComputedStyle(element).visibility === 'hidden'
      )
    );
    await page.screenshot({ path: `${output}/metadata-${width}.png`, fullPage: true });
    assert.equal(
      await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth + 2),
      false,
      `Metadata horizontal overflow at ${width}`
    );
    await page.getByRole('button', { name: '保存', exact: true }).click();
    await page.getByText('数据源已保存', { exact: true }).waitFor();
    assert.equal(savedSource.metadata[0].comment, '客户业务说明');
    assert.equal(savedSource.metadata[0].columns[0].comment, '客户编号');
    assert.deepEqual(savedSource.relations, [
      { sourceTable: 'customers', sourceColumn: 'id', targetTable: 'orders', targetColumn: 'id' },
    ]);
    await page.goto(`${origin}/tests/ai-agent-smoke.html?mode=knowledge`);
    await page.waitForFunction(() => window.__agentSmokeReady, undefined, { timeout: 60000 });
    await page.getByRole('heading', { name: '术语配置', exact: true }).waitFor();
    await page.getByText('已就绪', { exact: true }).waitFor();
    assert.equal(await page.getByRole('button', { name: '语义检索设置' }).count(), 0);
    assert.equal(await page.getByRole('button', { name: '重建索引' }).count(), 0);
    await page.screenshot({ path: `${output}/knowledge-${width}.png`, fullPage: true });
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth + 2);
    assert.equal(overflow, false, `Horizontal overflow at ${width}`);
    await page.close();
  }
  assert.deepEqual(errors, []);
  console.log(
    'Agent UI smoke checks passed for 1440px and 390px: all 234/1234 table/chart rows, final chart category, nonblank canvas, persisted row switch.'
  );
} finally {
  await browser.close();
}
