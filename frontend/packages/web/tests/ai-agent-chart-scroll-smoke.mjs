import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { mkdir } from 'node:fs/promises';
import path from 'node:path';

const require = createRequire(import.meta.url);
const { chromium } = require(process.argv[2] || 'playwright');
const origin = process.env.AGENT_TEST_ORIGIN || 'http://127.0.0.1:5173';
const output = path.resolve('target/ai-agent-chart-scroll');
await mkdir(output, { recursive: true });
const modes = [
  ['\u6761\u5f62\u56fe', 'bar', 'value'],
  ['\u6298\u7ebf\u56fe', 'line', 'category'],
  ['\u997c\u56fe', 'pie', undefined],
  ['\u67f1\u72b6\u56fe', 'bar', 'category'],
];
const rows = Array.from({ length: 234 }, (_, index) => ({ product: `Product ${index + 1}`, amount: 1000 + index }));
const messages = Array.from({ length: 3 }, (_, index) => {
  const answer = `Purchase totals ${index + 1}`;
  const response = {
    answer,
    queryQuestion: `Purchase question ${index + 1}`,
    sqlQueries: ['SELECT product, SUM(amount) AS amount FROM purchases GROUP BY product'],
    resultTable: {
      columns: [
        { key: 'product', label: 'Product', type: 'string' },
        { key: 'amount', label: 'Amount', type: 'number' },
      ],
      rows,
    },
    recommendedQuestions: ['Purchase trend by month'],
    charts: [
      {
        title: answer,
        type: 'column',
        dimension: 'Product',
        categories: rows.map((row) => row.product),
        series: [{ name: 'Amount', values: rows.map((row) => row.amount) }],
        coverage: 'All 234 products are included.',
        partial: false,
      },
    ],
  };
  return [
    { id: `question-${index}`, role: 'user', content: response.queryQuestion },
    { id: `answer-${index}`, role: 'assistant', content: answer, evidenceJson: JSON.stringify({ response }) },
  ];
}).flat();

async function snapshot(chart) {
  return chart.evaluate((element) => {
    const rect = (node) => {
      const { x, y, width, height } = node.getBoundingClientRect();
      return { x, y, width, height };
    };
    const scrolls = [];
    for (let node = element.parentElement; node; node = node.parentElement) {
      scrolls.push({
        className: node.className,
        top: node.scrollTop,
        left: node.scrollLeft,
        height: node.scrollHeight,
        clientHeight: node.clientHeight,
      });
    }
    return {
      chart: rect(element),
      label: rect(element.querySelector('label')),
      radio: rect(element.querySelector('input')),
      focus: document.activeElement?.outerHTML.slice(0, 250),
      scrolls,
    };
  });
}

async function assertCanvasVisible(chart) {
  assert.ok(
    await chart.locator('.chart-canvas').evaluate((element) => {
      const area = element.getBoundingClientRect();
      const container = element.closest('.chat-scroll').getBoundingClientRect();
      return (
        area.top < container.bottom &&
        area.bottom > container.top &&
        area.left < container.right &&
        area.right > container.left
      );
    }),
    'The chart must remain inside the visible conversation area'
  );
  assert.ok(
    await chart
      .locator('canvas')
      .first()
      .evaluate((canvas) => {
        const pixels = canvas.getContext('2d').getImageData(0, 0, canvas.width, canvas.height).data;
        let drawn = 0;
        for (let index = 3; index < pixels.length; index += 4) if (pixels[index]) drawn++;
        return drawn > 100;
      }),
    'The chart canvas must contain rendered pixels'
  );
}

const browser = await chromium.launch({
  executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe',
  headless: true,
});
const errors = [];
let activePage;
try {
  for (const width of [1440, 390]) {
    const page = await browser.newPage({ viewport: { width, height: 900 } });
    activePage = page;
    page.setDefaultTimeout(10000);
    page.on('pageerror', (error) => errors.push(error.message));
    const requests = [];
    await page.route('**/ai-agent/**', async (route) => {
      const pathname = new URL(route.request().url()).pathname;
      requests.push(pathname);
      const data = pathname.endsWith('/sessions')
        ? [{ id: 'history', title: 'Purchase questions' }]
        : pathname.endsWith('/messages')
        ? messages
        : [];
      await route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 200, data }) });
    });
    await page.goto(`${origin}/tests/ai-agent-smoke.html?mode=navigation`, { timeout: 60000 });
    await page.waitForFunction(() => window.__agentSmokeReady, undefined, { timeout: 60000 });
    await page.locator('.chart-canvas canvas').last().waitFor();
    assert.equal(await page.locator('.agent-result tbody tr').count(), rows.length * 3);
    const requestCount = requests.length;
    for (const index of [0, 2]) {
      const chart = page.locator('.agent-chart').nth(index);
      await chart.evaluate((element) => element.scrollIntoView({ block: 'center' }));
      await page.waitForTimeout(350);
      await assertCanvasVisible(chart);
      await page.screenshot({ path: `${output}/before-${width}-${index}.png` });
      for (const [label, type, axis] of [...modes, ...modes]) {
        const before = await snapshot(chart);
        const button = await chart.locator('label').filter({ hasText: label }).boundingBox();
        assert.ok(button && button.y >= 0 && button.y < 900, 'Chart mode must be onscreen before clicking');
        // A real click exposes focus scrolling that locator auto-scrolling can conceal.
        await page.mouse.click(button.x + button.width / 2, button.y + button.height / 2);
        await page.waitForFunction(
          ([index, type, axis]) => {
            const chart = window.__agentChart(index);
            return chart?.type === type && chart.xType === axis;
          },
          [index, type, axis]
        );
        await page.waitForTimeout(350);
        const after = await snapshot(chart);
        if (Math.abs(after.chart.y - before.chart.y) > 2)
          console.error(JSON.stringify({ width, index, label, before, after }));
        await page.screenshot({ path: `${output}/after-${width}-${index}-${type}-${axis || 'pie'}.png` });
        assert.ok(Math.abs(after.chart.y - before.chart.y) <= 2, 'Switching chart type must not move the conversation');
        assert.deepEqual(
          after.scrolls.map((scroll) => scroll.top),
          before.scrolls.map((scroll) => scroll.top),
          'Ancestor scroll positions must not change'
        );
        await assertCanvasVisible(chart);
      }
      const radio = chart.locator('input:checked');
      const beforeKeyboard = await snapshot(chart);
      await radio.press('ArrowRight');
      await page.waitForFunction((index) => window.__agentChart(index)?.xType === 'value', index);
      const afterKeyboard = await snapshot(chart);
      assert.ok(
        Math.abs(afterKeyboard.chart.y - beforeKeyboard.chart.y) <= 2,
        'Keyboard switching must not move the conversation'
      );
      await assertCanvasVisible(chart);
    }
    assert.equal(requests.length, requestCount, 'Chart switching must not send new queries');
    assert.equal(await page.locator('.agent-result tbody tr').count(), rows.length * 3);
    assert.equal(await page.locator('.chart-error').count(), 0);
    assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 2));
    await page.locator('.smoke-navigation button').first().click();
    await page.locator('.smoke-destination').waitFor();
    await page.locator('.smoke-navigation button').last().click();
    await page.locator('.chart-canvas canvas').last().waitFor();
    assert.equal(await page.locator('.agent-result tbody tr').count(), rows.length * 3);
    await page.close();
    console.log(`Chart modes, mouse and keyboard focus, history and navigation passed at ${width}px.`);
  }
  assert.deepEqual(errors, []);
} catch (error) {
  if (activePage && !activePage.isClosed()) await activePage.screenshot({ path: `${output}/failure.png` });
  throw error;
} finally {
  await browser.close();
}
