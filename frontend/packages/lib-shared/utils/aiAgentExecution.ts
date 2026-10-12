import type { AiAgentChatResult, AiAgentModelCall, AiAgentProcessStep } from '../api/modules/aiAgent';

export const aiAgentStageTitles: Record<string, string> = {
  request: '准备问答',
  attachment: '解析附件并回答',
  question_context: '查询问题',
  extract_keywords: '提取关键词',
  knowledge: '检索问数知识',
  terminology: '业务术语',
  analysis_terminology: '分析参考术语',
  sql_examples: 'SQL 示例',
  knowledge_limit: '知识上下文范围',
  table_selection: '选择数据表',
  table_selection_result: '选表结果与字段',
  sample_data: '读取样例数据',
  sql_generation: '生成 SQL',
  sql_generated: 'SQL 与查询说明',
  sql_repair: '修正 SQL',
  sql_repaired: '修正后的 SQL',
  sql_rejected: '校验或执行未通过',
  sql_generation_failed: '查询生成未完成',
  query_clarification: '待确认条件',
  sql_query: '校验与执行',
  query_result: '执行结果',
  query_failed: '查询失败',
  analysis: '分析查询结果',
  predict: '预测查询数据',
  answer: '生成回答',
  chart: '生成图表',
  guess: '推荐追问',
  llm: '模型调用',
};

export type AiAgentExecutionResult = Pick<
  AiAgentChatResult,
  'durationMs' | 'totalTokens' | 'processSteps' | 'modelCalls' | 'queryError' | 'datasourceType'
>;

export function formatAiAgentDuration(durationMs?: number | null) {
  return durationMs == null ? '—' : `${(Math.max(0, durationMs) / 1000).toFixed(2)} s`;
}

function sumTokens(calls: AiAgentModelCall[]) {
  const successful = calls.filter((call) => call.status === 'SUCCESS');
  return successful.some((call) => call.totalTokens == null)
    ? null
    : successful.reduce((total, call) => total + (call.totalTokens ?? 0), 0);
}

const executionStages = [
  { name: 'extract_keywords', title: '提取关键词', sources: ['extract_keywords'] },
  {
    name: 'terminology',
    title: '匹配术语',
    sources: ['knowledge', 'terminology', 'knowledge_limit'],
    result: 'terminology',
  },
  { name: 'sql_examples', title: '匹配 SQL 示例', sources: ['sql_examples'] },
  {
    name: 'table_selection',
    title: '匹配数据表 (Schema)',
    sources: ['table_selection', 'table_selection_result', 'sample_data'],
    result: 'table_selection_result',
  },
  {
    name: 'sql_generation',
    title: '生成 SQL',
    sources: [
      'sql_generation',
      'sql_generated',
      'sql_repair',
      'sql_repaired',
      'sql_generation_failed',
      'query_clarification',
    ],
    result: 'sql_generated',
  },
  {
    name: 'sql_query',
    title: '执行 SQL',
    sources: ['sql_query', 'query_result', 'query_failed', 'sql_rejected'],
    result: 'query_result',
  },
  { name: 'chart', title: '生成图表结构', sources: ['chart'] },
  // Analysis, prediction and attachments have their own operations rather than SQL query steps.
  { name: 'analysis', title: '分析查询结果', sources: ['analysis', 'analysis_terminology'], result: 'analysis' },
  { name: 'predict', title: '预测查询数据', sources: ['predict'] },
  { name: 'attachment', title: '解析附件并回答', sources: ['attachment'] },
  { name: 'answer', title: '生成回答', sources: ['answer'] },
  { name: 'llm', title: '模型调用', sources: ['llm'] },
];

export function getAiAgentExecution(result: AiAgentExecutionResult) {
  // SQLBot's answer log excludes recommended questions, which are a separate task.
  const calls = (result.modelCalls || []).filter((call) => call.stage !== 'guess');
  let { totalTokens } = result;
  if (totalTokens === undefined) totalTokens = calls.length ? sumTokens(calls) : null;
  const sourceSteps = (result.processSteps || []).filter((step) => step.name !== 'guess');
  // Older snapshots stored the original extraction output under the table-selection result.
  const legacyKeywords = sourceSteps
    .filter((step) => step.name === 'table_selection_result')
    .flatMap((step) => step.details || [])
    .filter((detail) => detail.type === 'text' && detail.title === '提取关键词');
  const lastStage = sourceSteps.at(-1)?.name;
  const steps = executionStages.flatMap((stage) => {
    const members = sourceSteps.filter((step) => stage.sources.includes(step.name));
    const modelCalls = calls.filter((call) => stage.sources.includes(call.stage || 'llm'));
    if (!members.length && !modelCalls.length && !(stage.name === 'extract_keywords' && legacyKeywords.length))
      return [];
    let status: AiAgentProcessStep['status'] = null;
    const failed =
      members.some((step) => step.status === 'FAILED') ||
      (result.queryError && lastStage && stage.sources.includes(lastStage));
    if (failed) status = 'FAILED';
    else if (members.length && members.every((step) => step.status === 'SUCCESS')) status = 'SUCCESS';
    else if (modelCalls.length) status = modelCalls.some((call) => call.status === 'SUCCESS') ? 'SUCCESS' : 'FAILED';

    // Each recorded interval belongs to one group; model-call time only fills an untimed interval.
    const durations = stage.sources
      .map((name) => {
        const recorded = members.filter((step) => step.name === name && step.durationMs != null);
        if (recorded.length) return recorded.reduce((total, step) => total + (step.durationMs ?? 0), 0);
        const stageCalls = modelCalls.filter((call) => (call.stage || 'llm') === name);
        return stageCalls.length ? stageCalls.reduce((total, call) => total + call.durationMs, 0) : null;
      })
      .filter((duration) => duration != null);
    const summaryStep =
      members.findLast((step) => step.status === 'FAILED') ||
      members.find((step) => step.name === stage.result) ||
      members.at(-1);
    let detailEntries = members.flatMap((step) => step.details || []);
    if (stage.name === 'extract_keywords' && !detailEntries.length) {
      detailEntries = legacyKeywords.map((detail) => ({ ...detail, title: '提取结果' }));
    }
    if (stage.name === 'table_selection') {
      detailEntries = detailEntries.filter((detail) => !(detail.type === 'text' && detail.title === '提取关键词'));
    }
    const details = detailEntries.filter(
      (detail, index, all) =>
        all.findIndex(
          (entry) => entry.type === detail.type && entry.title === detail.title && entry.content === detail.content
        ) === index
    );
    return [
      {
        name: stage.name,
        title: stage.title,
        summary: summaryStep?.summary || '',
        details,
        status,
        totalTokens: modelCalls.length ? sumTokens(modelCalls) : null,
        durationMs: durations.length ? durations.reduce((total, duration) => total + duration, 0) : null,
        modelCalls,
      },
    ];
  });
  return {
    totalTokens,
    durationMs: result.durationMs,
    steps,
  };
}
