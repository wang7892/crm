import assert from 'node:assert/strict';
import test from 'node:test';

import getAiAgentSqlQueries, { formatAiAgentSql, highlightAiAgentSql } from './aiAgentSql.ts';

test('single-line MySQL becomes indented clauses and columns', () => {
  const sql =
    "select c.name as customer_name, sum(o.amount) as total from customer c left join sales_order o on o.customer_id = c.id where c.name = 'Alice' group by c.name order by total desc limit 1000";
  const formatted = formatAiAgentSql(sql);
  assert.match(formatted, /^SELECT\n  c\.name AS customer_name,\n  sum\(o\.amount\) AS total/i);
  for (const clause of ['FROM', 'LEFT JOIN', 'WHERE', 'GROUP BY', 'ORDER BY', 'LIMIT']) {
    assert.match(formatted, new RegExp(`\\n[ \\t]*${clause}\\b`), clause);
  }
  assert.match(formatted, /'Alice'/);
});

test('formatting preserves string literals, backticks and JSON paths', () => {
  const sql =
    "SELECT 'FROM WHERE  a,b' AS note, `c`.`name` AS customer_name, JSON_EXTRACT(payload, '$.items[0]') AS item FROM customer c";
  const formatted = formatAiAgentSql(sql);
  assert.ok(formatted.includes("'FROM WHERE  a,b'"));
  assert.ok(formatted.includes('`c`.`name`'));
  assert.ok(formatted.includes("'$.items[0]'"));
});

test('CTEs and nested queries can be formatted', () => {
  const sql =
    'WITH totals AS (SELECT customer_id, SUM(amount) AS total FROM sales_order GROUP BY customer_id) SELECT c.name, t.total FROM customer c JOIN totals t ON t.customer_id=c.id';
  const formatted = formatAiAgentSql(sql);
  assert.match(formatted, /^WITH\n  totals AS \(\n/);
  assert.ok(formatted.split('\n').length > 10);
});

test('unsupported SQL falls back to the original text', () => {
  const sql = "SELECT 'unterminated";
  assert.equal(formatAiAgentSql(sql), sql);
});

test('datasource dialects preserve quoted identifiers and dialect syntax', () => {
  for (const [type, sql] of [
    ['POSTGRESQL', 'SELECT DATE_TRUNC(\'month\', "created_at") AS month FROM "products"'],
    ['ORACLE', 'SELECT amount AS amount FROM products FETCH FIRST 3 ROWS ONLY'],
    ['SQLSERVER', 'SELECT TOP 3 [amount] AS [amount] FROM [dbo].[products] ORDER BY [amount] DESC'],
    ['FILE', 'SELECT SUM("col_1") AS total FROM "table_1"'],
  ]) {
    const formatted = formatAiAgentSql(sql, type);
    assert.ok(formatted.includes('\n'), type);
    assert.match(formatted, /^SELECT\b/, type);
  }
});

test('highlighting colors SQL without interpreting embedded HTML', () => {
  const html = highlightAiAgentSql("SELECT '<img src=x onerror=alert(1)>' AS note LIMIT 1000");
  assert.match(html, /class="hljs-keyword"/);
  assert.match(html, /class="hljs-number"/);
  assert.ok(html.includes('&lt;img'));
  assert.ok(!html.includes('<img'));
});

test('display formatting does not alter stored model SQL', () => {
  const response = { sqlQueries: ['select id, name from customer', 'select id, name from customer'] };
  const original = [...response.sqlQueries];
  const displayed = getAiAgentSqlQueries(response).map(formatAiAgentSql);
  assert.equal(displayed.length, 1);
  assert.ok(displayed[0].includes('\n'));
  assert.deepEqual(response.sqlQueries, original);
});
