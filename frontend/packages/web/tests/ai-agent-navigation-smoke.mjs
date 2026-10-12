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
const errors = [];
const warnings = [];
const response = {
  answer: '历史查询完成',
  points: Array.from({ length: 1234 }, (_, i) => `第 ${i + 1} 条\n客户：客户${i + 1}\n金额：${i + 100}`),
  charts: [
    {
      title: '销售额',
      type: 'bar',
      dimension: '客户',
      categories: Array.from({ length: 1234 }, (_, i) => `客户${i + 1}`),
      series: [{ name: '金额', values: Array.from({ length: 1234 }, (_, i) => i + 100) }],
      partial: false,
      coverage: '已绘制本次查询的全部结果。',
    },
  ],
};
try {
  for (const width of [1440, 390]) {
    let malformedChart = false;
    const page = await browser.newPage({ viewport: { width, height: 900 } });
    page.setDefaultTimeout(10000);
    page.on('pageerror', (error) => {
      errors.push(error.message);
      console.error(error.stack);
    });
    page.on('console', (message) => {
      if (message.type() === 'error' || message.type() === 'warning') {
        warnings.push(message.text());
        console.error(message.text());
      }
    });
    await page.route('**/ai-agent/**', async (route) => {
      const pathname = new URL(route.request().url()).pathname;
      let data = [];
      if (pathname.endsWith('/sessions')) data = [{ id: 'history', title: '历史客户金额' }];
      if (pathname.endsWith('/messages'))
        data = [
          {
            id: 'history-answer',
            role: 'assistant',
            content: response.answer,
            evidenceJson: JSON.stringify({
              response: malformedChart ? { ...response, charts: [{ ...response.charts[0], series: null }] } : response,
            }),
          },
        ];
      await route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 200, data }) });
    });
    await page.goto(`${origin}/tests/ai-agent-smoke.html?mode=navigation`, { timeout: 60000 });
    await page.waitForFunction(() => window.__agentSmokeReady, undefined, { timeout: 60000 });
    await page.getByText('历史查询完成', { exact: true }).waitFor();
    await page.locator('.chart-canvas canvas').waitFor();
    console.log(`Initial history rendered at ${width}px`);
    assert.equal(await page.locator('.message-detail-table tbody tr').count(), 1234);
    for (const name of ['首页', '客户', '联系专员']) {
      await page.locator('.smoke-navigation').getByRole('button', { name, exact: true }).click();
      await page.locator('.smoke-destination').filter({ hasText: name }).waitFor();
      await page.locator('.smoke-navigation').getByRole('button', { name: '智能体', exact: true }).click();
      await page.locator('.chart-canvas canvas').waitFor();
    }
    console.log(`Route navigation and returning to history responded at ${width}px`);
    if (width < 768) await page.getByRole('button', { name: '聊天菜单', exact: true }).click();
    await page.getByRole('button', { name: '新聊天', exact: true }).click();
    await page.locator('.empty-title').waitFor();
    console.log(`New chat rendered at ${width}px`);
    if (width < 768) await page.getByRole('button', { name: '聊天菜单', exact: true }).click();
    await page.getByRole('button', { name: '搜索聊天', exact: true }).click();
    await page.getByRole('searchbox', { name: '搜索聊天标题或问题' }).fill('历史');
    await page.locator('.history-item').filter({ hasText: '历史客户金额' }).click();
    await page.getByText('历史查询完成', { exact: true }).waitFor();
    if (width < 768) await page.getByRole('button', { name: '聊天菜单', exact: true }).click();
    await page.getByRole('button', { name: '配置', exact: true }).click();
    await page.getByRole('button', { name: '新建数据源', exact: true }).waitFor();
    console.log(`Search and configuration responded at ${width}px`);
    await page.screenshot({ path: `${output}/navigation-${width}.png`, fullPage: true });
    await page.getByRole('button', { name: '返回聊天', exact: true }).click();
    await page.locator('.chart-canvas canvas').waitFor();
    await page.setViewportSize({ width: width === 1440 ? 1200 : 420, height: 900 });
    assert.equal((await page.evaluate(() => window.__agentChart())).categories.length, 1234);
    malformedChart = true;
    await page.locator('.smoke-navigation').getByRole('button', { name: '首页', exact: true }).click();
    await page.locator('.smoke-destination').waitFor();
    await page.locator('.smoke-navigation').getByRole('button', { name: '智能体', exact: true }).click();
    await page.getByText('图表暂时无法显示。', { exact: true }).waitFor();
    assert.equal(await page.locator('.message-detail-table tbody tr').count(), 1234);
    if (width < 768) await page.getByRole('button', { name: '聊天菜单', exact: true }).click();
    await page.getByRole('button', { name: '配置', exact: true }).click();
    await page.getByRole('button', { name: '新建数据源', exact: true }).waitFor();
    await page.locator('.smoke-navigation').getByRole('button', { name: '客户', exact: true }).click();
    await page.locator('.smoke-destination').filter({ hasText: '客户' }).waitFor();
    malformedChart = false;
    await page.locator('.smoke-navigation').getByRole('button', { name: '智能体', exact: true }).click();
    await page.locator('.chart-canvas canvas').waitFor();
    console.log(`Chart failure stayed local and recovered at ${width}px`);
    await page.close();
  }
  assert.deepEqual(errors, []);
  assert.ok(
    warnings.every((warning) => warning.startsWith('AI agent chart rendering failed')),
    warnings.join('\n')
  );
  console.log(
    'Agent initial history, route navigation, new chat, search, configuration and chart resize checks passed.'
  );
} finally {
  await browser.close();
}
