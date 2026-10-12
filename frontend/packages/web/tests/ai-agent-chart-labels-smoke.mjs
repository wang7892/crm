import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { mkdir } from 'node:fs/promises';
import path from 'node:path';

const require = createRequire(import.meta.url);
const { chromium } = require(process.argv[2] || 'playwright');
const origin = process.env.AGENT_TEST_ORIGIN || 'http://127.0.0.1:5173';
const output = path.resolve('target/ai-agent-chart-labels');
await mkdir(output, { recursive: true });
const products = [
  '100D\u5e73\u7eb9\u56db\u9762\u5f39\u7eb3\u7c73\u9632\u6c34\u67d3\u8272',
  '\u6625\u4e9a\u7eba\u67d3\u8272TPU',
  '\u56db\u9762\u5f39\u67d3\u8272',
  '\u86c7\u8681\u7ed2\u67d3\u8272',
  '\u6625\u590f\u65b0\u6b3e\u8d85\u957f\u54c1\u540d\u6d4b\u8bd5\u9ad8\u5f39\u9632\u6c34\u900f\u6c14\u6d82\u5c42\u590d\u5408\u9762\u6599',
  '100D\u5e73\u7eb9\u56db\u9762\u5f39\u67d3\u8272',
  '\u9ad8\u5f39\u900f\u6c14\u9ed1\u8272\u9762\u6599',
  '\u5236\u670d\u9762\u6599\u6d45\u84dd\u67d3\u8272',
];
const charts = [8, 24, 1].map((count) => ({
  title: `Purchase totals (${count})`,
  type: 'column',
  dimension: '\u54c1\u540d',
  categories: Array.from({ length: count }, (_, index) => `${products[index % products.length]}-${index + 1}`),
  series: [
    { name: '\u91c7\u8d2d\u603b\u91cf', values: Array.from({ length: count }, (_, index) => 100000000 / (index + 1)) },
  ],
  coverage: 'All products are included.',
}));
const messages = charts.map((chart, index) => ({
  id: `answer-${index}`,
  role: 'assistant',
  content: chart.title,
  evidenceJson: JSON.stringify({ response: { answer: chart.title, charts: [chart] } }),
}));

async function assertLabels(page, index) {
  const layout = await page.evaluate((index) => window.__agentChartLayout(index), index);
  const first = Math.ceil(layout.extent[0]);
  const last = Math.floor(layout.extent[1]);
  const expected = charts[index].categories.slice(first, last + 1);
  assert.deepEqual(
    layout.labels.map((label) => label.text),
    expected,
    'Each visible category must have an axis label'
  );
  for (const label of layout.labels) {
    assert.equal(label.hidden, false, 'Category labels must not be hidden to avoid overlaps');
    assert.ok(label.x >= -1 && label.x + label.width <= layout.width + 1, 'Category label must fit the canvas width');
    assert.ok(
      label.y >= -1 && label.y + label.height <= layout.height + 1,
      'Category label must fit the canvas height'
    );
    if (!layout.horizontal && layout.zoom)
      assert.ok(label.y + label.height < layout.height - 32, 'Axis labels must not overlap the zoom slider');
  }
  const labels = [...layout.labels].sort((a, b) => (layout.horizontal ? a.y - b.y : a.x - b.x));
  for (let index = 1; index < labels.length; index++) {
    const previous = labels[index - 1];
    assert.ok(
      layout.horizontal
        ? previous.y + previous.height <= labels[index].y + 1
        : previous.x + previous.width <= labels[index].x + 1,
      'Adjacent category labels must not overlap'
    );
  }
  assert.deepEqual(layout.zoomTexts, [], 'Product names must not appear beside the zoom slider');
  assert.ok(layout.plot.width >= 60 && layout.plot.height >= 100, 'Labels must leave enough space for the data plot');
  return layout;
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
    page.on('pageerror', (error) => errors.push(error.message));
    await page.route('**/ai-agent/**', async (route) => {
      const pathname = new URL(route.request().url()).pathname;
      const data = pathname.endsWith('/sessions')
        ? [{ id: 'history', title: 'Purchase totals' }]
        : pathname.endsWith('/messages')
        ? messages
        : [];
      await route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 200, data }) });
    });
    await page.goto(`${origin}/tests/ai-agent-smoke.html?mode=navigation`, { timeout: 60000 });
    await page.waitForFunction(() => window.__agentSmokeReady, undefined, { timeout: 60000 });
    await page.locator('.chart-canvas canvas').last().waitFor();
    for (const index of [0, 1, 2]) {
      const chart = page.locator('.agent-chart').nth(index);
      await chart.evaluate((element) => element.scrollIntoView({ block: 'center' }));
      await page.waitForTimeout(350);
      await page.screenshot({ path: `${output}/column-${width}-${index}.png` });
      let layout = await assertLabels(page, index);
      const canvas = await chart.locator('.chart-canvas').boundingBox();
      const label = layout.labels[0];
      await page.mouse.move(canvas.x + label.x + label.width / 2, canvas.y + label.y + 8);
      await page.waitForTimeout(200);
      const hovered = await page.evaluate((index) => window.__agentChartLayout(index), index);
      assert.ok(hovered.tooltip?.text.includes(label.text), 'Hovering a category label must show its complete name');
      await page.screenshot({ path: `${output}/tooltip-${width}-${index}.png` });
      if (layout.zoom) {
        const handle = layout.zoomHandles[0];
        await page.mouse.move(canvas.x + handle.x, canvas.y + handle.y);
        await page.waitForTimeout(200);
        await assertLabels(page, index);
        const count = layout.labels.length;
        const endHandle = layout.zoomHandles[1];
        await page.mouse.move(canvas.x + (handle.x + endHandle.x) / 2, canvas.y + handle.y);
        await page.mouse.down();
        await page.mouse.move(canvas.x + canvas.width - 2, canvas.y + handle.y, { steps: 8 });
        await page.mouse.up();
        await page.waitForTimeout(350);
        layout = await assertLabels(page, index);
        assert.equal(layout.labels.length, count, 'Dragging the selected window must preserve its size');
        assert.equal(
          layout.labels.at(-1).text,
          charts[index].categories.at(-1),
          'The slider must reach the final product'
        );
      }
      await chart.locator('label').filter({ hasText: '\u6761\u5f62\u56fe' }).click();
      await page.waitForTimeout(350);
      await assertLabels(page, index);
      await page.screenshot({ path: `${output}/bar-${width}-${index}.png` });
      await chart.locator('label').filter({ hasText: '\u6298\u7ebf\u56fe' }).click();
      await page.waitForTimeout(350);
      await assertLabels(page, index);
      await chart.locator('label').filter({ hasText: '\u67f1\u72b6\u56fe' }).click();
    }
    await page.setViewportSize({ width: width === 1440 ? 390 : 1440, height: 900 });
    await page.waitForTimeout(500);
    for (const index of [0, 1, 2]) await assertLabels(page, index);
    await page.close();
    console.log(`Product labels, zooming and responsive layout passed at ${width}px.`);
  }
  assert.deepEqual(errors, []);
} catch (error) {
  if (activePage && !activePage.isClosed()) await activePage.screenshot({ path: `${output}/failure.png` });
  throw error;
} finally {
  await browser.close();
}
