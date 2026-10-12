import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { mkdir } from 'node:fs/promises';
import path from 'node:path';

const require = createRequire(import.meta.url);
const { chromium } = require(process.argv[2] || 'playwright');
const origin = process.env.AGENT_TEST_ORIGIN || 'http://127.0.0.1:5173';
const output = path.resolve('target/ai-agent-sqlbot');
await mkdir(output, { recursive: true });
const browser = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: true });
const resultTable = {
  columns: [{ key: 'month', label: '月份', type: 'string' }, { key: 'amount', label: '收入', type: 'number' }],
  rows: [{ month: '一月：备注\n第二行', amount: 100 }, { month: '二月', amount: 200 }],
};
const baseChart = { title: '每月收入', type: 'column', dimension: '月份', categories: resultTable.rows.map(r => r.month), series: [{ name: '收入', values: [100, 200] }], partial: false };
const errors = [];
let activePage;
try {
  for (const width of [1440, 390]) {
    const requests = [], stored = [];
    const page = await browser.newPage({ viewport: { width, height: 950 } });
    activePage = page;
    page.on('pageerror', error => { errors.push(error.message); console.error(error.message); });
    page.on('response', response => { if (response.status() >= 400) console.error(response.status(), new URL(response.url()).pathname); });
    await page.route('**/ai-agent/**', async route => {
      const pathname = new URL(route.request().url()).pathname;
      if (pathname.endsWith('/result/export')) {
        const request = route.request().postDataJSON(); assert.equal(request.referenceMessageId, 'q1');
        return route.fulfill({ contentType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', body: 'export-test' });
      }
      if (pathname.endsWith('/chat/stream')) {
        const request = route.request().postDataJSON(); requests.push(request);
        const action = request.action || 'QUERY';
        const result = {
          sessionId: 'session', messageId: `q${requests.length}`, answer: action === 'ANALYSIS' ? '收入增长100' : action === 'PREDICT' ? '预测完成' : '查询完成',
          queryQuestion: '每月收入', sqlQueries: ['SELECT month AS month, amount AS amount FROM sales'],
          charts: action === 'ANALYSIS' ? [] : [baseChart],
          resultTable: ['QUERY', 'REGENERATE'].includes(action) ? resultTable : undefined,
          recommendedQuestions: ['QUERY', 'REGENERATE'].includes(action) ? ['按季度汇总收入'] : [],
          modelCalls: [{ stage: 'sql_generation', provider: 'primary', model: 'test-model', durationMs: 25, promptTokens: 12, completionTokens: 5, totalTokens: 17, status: 'SUCCESS' }],
        };
        if (action === 'PREDICT') {
          result.predictionTable = { columns: resultTable.columns, rows: [{ month: '三月', amount: 300 }, { month: '四月', amount: 400 }] };
          result.charts = [{ ...baseChart, type: 'line', categories: ['一月', '二月', '三月', '四月'], series: [{ name: '收入', values: [100, 200, 300, 400] }], predictionStart: 2 }];
        }
        stored.push({ id: `u${requests.length}`, role: 'user', content: request.question });
        stored.push({ id: result.messageId, role: 'assistant', content: result.answer, evidenceJson: JSON.stringify({ response: result }) });
        return route.fulfill({ contentType: 'text/event-stream', body: `event: result\ndata: ${JSON.stringify(result)}\n\n` });
      }
      const data = pathname.endsWith('/sessions') && stored.length ? [{ id: 'session', title: '历史问数', updateTime: Date.now() }]
        : pathname.endsWith('/sessions/session/messages') ? stored : [];
      return route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 200, data }) });
    });
    await page.goto(`${origin}/tests/ai-agent-smoke.html?mode=chat`);
    await page.waitForFunction(() => window.__agentSmokeReady, undefined, { timeout: 60000 });
    if (width < 768) await page.locator('.mobile-menu-button').click();
    await page.locator('.sidebar-action').filter({ hasText: '新聊天' }).click();
    await page.locator('.composer-input textarea').fill('每月收入'); await page.locator('.send-button').click();
    const first = page.locator('.agent-result').first(); await first.locator('tbody tr').last().waitFor();
    assert.equal(await first.locator('tbody tr').count(), 2);
    assert.ok((await first.locator('tbody').innerText()).includes('一月：备注'));
    assert.equal(await page.locator('.message-detail-table').count(), 0, 'New results must not be parsed from formatted points');
    await page.locator('.chart-canvas canvas').first().waitFor();
    for (const [label, expected, xType] of [['条形图', 'bar', 'value'], ['折线图', 'line', 'category'], ['饼图', 'pie', undefined], ['柱状图', 'bar', 'category']]) {
      await page.locator('.chart-modes').first().getByText(label, { exact: true }).click();
      await page.waitForFunction(([type, axis]) => {
        const chart = window.__agentChart(0);
        return chart?.type === type && chart.xType === axis;
      }, [expected, xType]);
      const chart = await page.evaluate(() => window.__agentChart(0)); assert.equal(chart.xType, xType);
    }
    await first.locator('summary').filter({ hasText: '模型调用记录' }).click();
    assert.ok((await first.innerText()).includes('Token 17'));
    await first.getByRole('button', { name: '分析', exact: true }).click(); await page.getByText('收入增长100', { exact: true }).waitFor();
    assert.equal(requests.at(-1).action, 'ANALYSIS'); assert.equal(requests.at(-1).referenceMessageId, 'q1');
    await first.getByRole('button', { name: '预测', exact: true }).click(); await page.getByText('预测完成', { exact: true }).waitFor();
    assert.equal(requests.at(-1).action, 'PREDICT');
    await page.locator('.agent-result').last().locator('tbody tr').last().waitFor();
    assert.ok((await page.locator('.agent-result').last().locator('tbody').innerText()).includes('四月'));
    await page.waitForFunction(() => window.__agentChart(1)?.series?.length === 2);
    const prediction = await page.evaluate(() => window.__agentChart(1));
    assert.equal(prediction.series.length, 2); assert.equal(prediction.series[1].lineStyle.type, 'dashed');
    const download = page.waitForEvent('download'); await first.getByRole('button', { name: '导出 Excel', exact: true }).click();
    assert.equal((await download).suggestedFilename(), '查询结果.xlsx');
    await first.getByRole('button', { name: '重新生成', exact: true }).click(); await page.locator('.agent-result').nth(3).locator('tbody tr').last().waitFor();
    assert.equal(requests.at(-1).action, 'REGENERATE'); assert.equal(requests.at(-1).referenceMessageId, 'q1');
    await first.getByRole('button', { name: '按季度汇总收入', exact: true }).click();
    await page.locator('.agent-result').nth(4).locator('tbody tr').last().waitFor();
    assert.equal(requests.at(-1).question, '按季度汇总收入');
    await page.screenshot({ path: `${output}/query-${width}.png`, fullPage: true });
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth + 2), false);
    await page.reload(); await page.waitForFunction(() => window.__agentSmokeReady, undefined, { timeout: 60000 });
    await page.locator('.agent-result').last().locator('tbody tr').last().waitFor();
    assert.equal(await page.getByText('预测数据', { exact: true }).count(), 1);
    assert.equal(await page.locator('.agent-result').first().locator('tbody tr').count(), 2);
    await page.close();
  }
  assert.deepEqual(errors, []); console.log('SQLBot result, actions, chart modes, export and history checks passed at both widths.');
} catch (error) {
  if (activePage && !activePage.isClosed()) {
    console.error(await activePage.locator('body').innerText());
    await activePage.screenshot({ path: `${output}/failure.png`, fullPage: true });
  }
  throw error;
} finally { await browser.close(); }
