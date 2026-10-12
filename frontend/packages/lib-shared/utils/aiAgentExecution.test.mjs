import assert from 'node:assert/strict';
import test from 'node:test';

import { formatAiAgentDuration, getAiAgentExecution } from './aiAgentExecution.ts';

const call = (stage, totalTokens, status = 'SUCCESS') => ({
  stage,
  totalTokens,
  status,
  provider: 'primary',
  model: 'test',
  durationMs: 100,
});

test('uses whole-answer duration and excludes the recommended-question task', () => {
  const execution = getAiAgentExecution({
    durationMs: 8720,
    totalTokens: 14001,
    processSteps: [
      { name: 'sql_generation', summary: '生成 SQL', durationMs: 5300, status: 'SUCCESS' },
      { name: 'guess', summary: '推荐问题' },
    ],
    modelCalls: [call('sql_generation', 14001), call('guess', 300)],
  });
  assert.equal(execution.totalTokens, 14001);
  assert.equal(formatAiAgentDuration(execution.durationMs), '8.72 s');
  assert.equal(execution.steps.length, 1);
  assert.equal(execution.steps[0].durationMs, 5300);
  assert.equal(execution.steps[0].totalTokens, 14001);
});

test('legacy calls recover tokens and per-call timing without inventing answer duration', () => {
  const execution = getAiAgentExecution({
    modelCalls: [call('sql_generation', 10), call('chart', 20), call('guess', 100)],
  });
  assert.equal(execution.totalTokens, 30);
  assert.equal(formatAiAgentDuration(execution.durationMs), '—');
  assert.equal(execution.steps[0].durationMs, 100);
  assert.deepEqual(
    execution.steps.map((step) => step.name),
    ['sql_generation', 'chart']
  );
});

test('zero and missing usage are distinct and old untimed steps remain unknown', () => {
  assert.equal(getAiAgentExecution({ totalTokens: 0 }).totalTokens, 0);
  assert.equal(formatAiAgentDuration(0), '0.00 s');
  assert.equal(getAiAgentExecution({ totalTokens: null, modelCalls: [call('chart', 10)] }).totalTokens, null);
  assert.equal(getAiAgentExecution({ modelCalls: [call('chart', undefined)] }).totalTokens, null);
  const legacy = getAiAgentExecution({ processSteps: [{ name: 'knowledge', summary: '旧记录' }] });
  assert.equal(legacy.steps[0].durationMs, null);
  assert.equal(legacy.steps[0].status, null);
});

test('fallback calls retain individual failures while completed stage reports success', () => {
  const execution = getAiAgentExecution({
    modelCalls: [call('sql_generation', undefined, 'FAILED'), call('sql_generation', 5)],
  });
  assert.equal(execution.totalTokens, 5);
  assert.equal(execution.steps[0].status, 'SUCCESS');
  assert.equal(execution.steps[0].modelCalls.length, 2);
  const failed = getAiAgentExecution({
    queryError: 'SQL 错误',
    processSteps: [{ name: 'sql_query', summary: '执行查询', status: 'FAILED' }],
  });
  assert.equal(failed.steps[0].status, 'FAILED');
});

test('query trace is folded into SQLBot seven steps with merged details, timing and failure status', () => {
  const sources = [
    'request',
    'question_context',
    'extract_keywords',
    'table_selection',
    'table_selection_result',
    'knowledge',
    'terminology',
    'sql_examples',
    'sample_data',
    'sql_generation',
    'sql_generated',
    'sql_query',
    'query_result',
    'chart',
    'guess',
  ];
  const execution = getAiAgentExecution({
    processSteps: sources.map((name) => ({
      name,
      summary: name,
      durationMs: 10,
      status: 'SUCCESS',
      details: [{ type: 'text', title: name, content: name }],
    })),
    modelCalls: [call('extract_keywords', 3), call('sql_generation', 7), call('chart', 5), call('guess', 99)],
  });
  assert.deepEqual(
    execution.steps.map((step) => step.title),
    ['提取关键词', '匹配术语', '匹配 SQL 示例', '匹配数据表 (Schema)', '生成 SQL', '执行 SQL', '生成图表结构']
  );
  assert.equal(execution.totalTokens, 15);
  const schema = execution.steps[3];
  assert.equal(schema.durationMs, 30);
  assert.equal(schema.summary, 'table_selection_result');
  assert.deepEqual(
    schema.details.map((detail) => detail.title),
    ['table_selection', 'table_selection_result', 'sample_data']
  );
  const sql = execution.steps[4];
  assert.equal(sql.durationMs, 20);
  assert.equal(sql.totalTokens, 7);
  const failed = getAiAgentExecution({
    processSteps: [
      { name: 'sql_generation', summary: '生成中', durationMs: 100, status: 'SUCCESS' },
      { name: 'sql_generated', summary: '已生成', durationMs: 5, status: 'SUCCESS' },
      { name: 'sql_query', summary: '执行中', durationMs: 20, status: 'FAILED' },
      { name: 'query_failed', summary: '执行失败', durationMs: 0, status: 'FAILED' },
    ],
    queryError: '执行失败',
  });
  assert.deepEqual(
    failed.steps.map((step) => step.name),
    ['sql_generation', 'sql_query']
  );
  assert.equal(failed.steps[1].status, 'FAILED');
});

test('old keyword output is displayed under extraction and expanded keywords remain under table matching', () => {
  const execution = getAiAgentExecution({
    processSteps: [
      { name: 'extract_keywords', summary: '提取查询关键词' },
      {
        name: 'table_selection_result',
        summary: '相关表：customers',
        details: [
          { type: 'text', title: '提取关键词', content: '区域,埃及' },
          { type: 'text', title: '扩展关键词', content: '区域,埃及,客户地区' },
          { type: 'table', title: 'customers', content: '客户表' },
        ],
      },
    ],
    modelCalls: [call('extract_keywords', 365)],
  });
  assert.deepEqual(execution.steps[0].details, [{ type: 'text', title: '提取结果', content: '区域,埃及' }]);
  assert.equal(execution.steps[0].totalTokens, 365);
  assert.deepEqual(
    execution.steps[1].details.map((detail) => detail.title),
    ['扩展关键词', 'customers']
  );
  assert.equal(execution.steps.length, 2);
});

test('new extraction output takes precedence and missing historical output is never invented', () => {
  const execution = getAiAgentExecution({
    processSteps: [
      {
        name: 'extract_keywords',
        summary: '已提取查询关键词',
        details: [{ type: 'text', title: '提取结果', content: '客户,埃及' }],
      },
      {
        name: 'table_selection_result',
        summary: '相关表',
        details: [{ type: 'text', title: '提取关键词', content: '旧值' }],
      },
    ],
  });
  assert.deepEqual(execution.steps[0].details, [{ type: 'text', title: '提取结果', content: '客户,埃及' }]);
  assert.equal(getAiAgentExecution({ modelCalls: [call('extract_keywords', 3)] }).steps[0].details.length, 0);
});
