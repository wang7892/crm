import type { QueryKnowledgeItem, QueryKnowledgeKind, QueryKnowledgeMatches } from './queryKnowledge';
import type { CordysAxios } from '@lib/shared/api/http/Axios';
import {
  AiAgentAttachmentChatUrl,
  AiAgentAudioTranscriptionQueryUrl,
  AiAgentAudioTranscriptionsUrl,
  AiAgentCancelChatUrl,
  AiAgentChatUrl,
  AiAgentDeleteSessionUrl,
  AiAgentFeedbackUrl,
  AiAgentMessagesUrl,
  AiAgentSessionsUrl,
  AiKnowledgeDocumentChunkPageUrl,
  AiKnowledgeDocumentDeleteUrl,
  AiKnowledgeDocumentDetailUrl,
  AiKnowledgeDocumentDisableUrl,
  AiKnowledgeDocumentDownloadUrl,
  AiKnowledgeDocumentEditUrl,
  AiKnowledgeDocumentEnableUrl,
  AiKnowledgeDocumentPageUrl,
  AiKnowledgeDocumentReparseUrl,
  AiKnowledgeDocumentUploadUrl,
  AiKnowledgeDocumentVersionDeleteUrl,
  AiKnowledgeDocumentVersionDetailUrl,
  AiKnowledgeDocumentVersionPageUrl,
  AiKnowledgeDocumentVersionRestoreUrl,
  AiKnowledgeSearchTestUrl,
  AiSemanticRuleBatchReviewUrl,
  AiSemanticRulePageUrl,
  AiSemanticRuleReviewUrl,
  AiSemanticRuleSaveUrl,
  AiSemanticRuleSchemaOptionsUrl,
} from '@lib/shared/api/requrls/aiAgent';
import { getToken } from '@lib/shared/method/auth';
import { getLocalStorage } from '@lib/shared/method/local-storage';
import type { Result } from '@lib/shared/types/axios';
import { type AiAgentStreamEvent, readAiAgentStream } from '@lib/shared/utils/aiAgentStream';

export interface AiAgentChatParams {
  requestId?: string;
  sessionId?: string;
  continueFromMessageId?: string;
  referenceMessageId?: string;
  action?: 'QUERY' | 'ANALYSIS' | 'PREDICT' | 'REGENERATE';
  question: string;
  stream?: boolean;
  limitRows?: boolean;
  timeRange?: string;
  dataScope?: string;
  llmProvider?: string;
  datasourceId?: string;
  context?: Record<string, unknown>;
}

export interface AiAgentToolCall {
  name: string;
  status: string;
  evidenceId?: string;
  summary?: string;
  durationMs?: number;
}

export interface AiAgentCitation {
  type?: string;
  module?: string;
  title?: string;
  recordIds?: string[];
  updatedAt?: string;
}

export interface AiAgentPageInfo {
  total?: number | null;
  offset: number;
  returned: number;
  remaining?: number | null;
  hasMore: boolean;
  continuable: boolean;
  unit: string;
}

export interface AiAgentProcessDetail {
  type: string;
  title: string;
  content: string;
}

export interface AiAgentProcessStep {
  name: string;
  summary: string;
  details?: AiAgentProcessDetail[];
  durationMs?: number | null;
  status?: 'RUNNING' | 'SUCCESS' | 'FAILED' | null;
}

export interface AiAgentChatResult {
  datasourceId?: string;
  datasourceName?: string;
  datasourceType?: string;
  sessionId: string;
  messageId: string;
  answer: string;
  reasoningContent?: string;
  resultTable?: AiAgentResultTable;
  predictionTable?: AiAgentResultTable;
  recommendedQuestions?: string[];
  queryQuestion?: string;
  queryError?: string;
  chartConfig?: Record<string, unknown>;
  modelCalls?: AiAgentModelCall[];
  durationMs?: number | null;
  totalTokens?: number | null;
  intent?: string;
  llmProvider?: string;
  llmModel?: string;
  pageInfo?: AiAgentPageInfo;
  sqlQueries?: string[];
  charts?: AiAgentChart[];
  processSteps?: AiAgentProcessStep[];
  points?: string[];
  evidence?: string[];
  warnings?: string[];
  tools?: AiAgentToolCall[];
  citations?: AiAgentCitation[];
}

export interface AiAgentResultTable {
  columns: { key: string; label: string; type: string }[];
  rows: Record<string, unknown>[];
}

export interface AiAgentModelCall {
  stage: string;
  provider: string;
  model: string;
  durationMs: number;
  promptTokens?: number;
  completionTokens?: number;
  totalTokens?: number;
  status: string;
}

export interface AiAgentAudioTranscriptionResult {
  taskId?: string;
  status?: 'RUNNING' | 'SUCCESS' | 'FAILED' | string;
  text?: string;
  language?: string;
}

export type AiAgentAudioLanguage = 'zh' | 'en';

export interface AiAgentFeedbackParams {
  messageId: string;
  rating: string;
  comment?: string;
  correctAnswer?: string;
}

export interface AiAgentSessionItem {
  id: string;
  title: string;
  agentName?: string;
  updateTime?: number;
}

export interface AiKnowledgeDocumentPageParams {
  current: number;
  pageSize: number;
  keyword?: string;
  fileType?: string;
  category?: string;
  parseStatus?: string;
  enabled?: number | null;
}

export interface AiAgentPager<T> {
  list: T[];
  total: number;
  pageSize: number;
  current: number;
}

export interface AiKnowledgeDocumentItem {
  id: string;
  name: string;
  originalName: string;
  fileType: string;
  fileSize: number;
  category?: string;
  parseStatus: string;
  parseError?: string;
  chunkCount: number;
  enabled: number;
  semanticStatus?: string;
  ruleStats?: AiSemanticRuleStats;
  remark?: string;
  activeVersionId?: string;
  activeVersion?: number;
  latestVersion?: number;
  knowledgeAvailable?: boolean;
  createTime?: number;
  updateTime?: number;
}

export interface AiKnowledgeDocumentEditResult {
  documentId: string;
  name: string;
  originalName: string;
  fileType: string;
  remark?: string;
  content: string;
  activeVersion: number;
  revision: number;
  parseStatus: string;
}

export interface AiKnowledgeDocumentEditParams {
  name: string;
  remark?: string;
  content: string;
  expectedRevision: number;
}

export interface AiKnowledgeDocumentVersionItem {
  id: string;
  documentId: string;
  version: number;
  name: string;
  remark?: string;
  content?: string;
  status: string;
  source: string;
  sourceVersion?: number;
  errorMessage?: string;
  createUser?: string;
  createUserName?: string;
  createTime?: number;
  updateTime?: number;
}

export interface AiSemanticRuleStats {
  total: number;
  pending: number;
  approved: number;
  rejected: number;
  invalid: number;
}

export interface AiKnowledgeChunkPageParams {
  current: number;
  pageSize: number;
  documentId: string;
}

export interface AiKnowledgeChunkItem {
  id: string;
  documentId: string;
  documentName?: string;
  chunkIndex: number;
  title?: string;
  content: string;
  pageNo?: number;
  sectionPath?: string;
  tokenCount?: number;
  embeddingStatus?: string;
  enabled?: number;
  createTime?: number;
  updateTime?: number;
}

export interface AiKnowledgeSearchMatch {
  documentId: string;
  documentName?: string;
  chunkId: string;
  chunkIndex: number;
  pageNo?: number;
  sectionPath?: string;
  score: number;
  content: string;
}

export interface AiKnowledgeSearchTestResult {
  question: string;
  rewriteQuestion?: string;
  matches: AiKnowledgeSearchMatch[];
  answerPreview?: string;
  retrievalMode?: string;
  matchedRules?: AiSemanticRuleMatch[];
  injectedContextPreview?: AiSemanticInjectedContext | null;
  fallbackReason?: string | null;
}

export type AiKnowledgeSearchMode = 'AUTO' | 'SEMANTIC_RULE' | 'DOCUMENT';

export type AiSemanticRuleReviewStatus = 'PENDING' | 'APPROVED' | 'REJECTED' | 'INVALID';

export interface AiSemanticRuleMapping {
  entity: string;
  field: string;
  dataSource?: string;
}

export interface AiSemanticForbiddenMapping {
  entity: string;
  field?: string | null;
  reason?: string | null;
}

export interface AiSemanticRuleExample {
  question: string;
  expectedEntity: string;
  expectedField: string;
}

export interface AiSemanticRuleSource {
  documentId?: string;
  pageNo?: number | null;
  sectionPath?: string | null;
  quote: string;
}

export interface AiSemanticRuleExtraction {
  confidence?: number | null;
  model?: string | null;
}

export interface AiSemanticRuleReview {
  status: AiSemanticRuleReviewStatus;
  reviewerId?: string | null;
  reviewedAt?: number | null;
  comment?: string | null;
}

export interface AiSemanticRule {
  schemaVersion?: string;
  ruleId: string;
  version: number;
  type?: string;
  canonicalTerm: string;
  aliases: string[];
  definition?: string | null;
  instruction?: string | null;
  scope?: string;
  mapping: AiSemanticRuleMapping;
  forbiddenMappings: AiSemanticForbiddenMapping[];
  requiredFilters?: AiSemanticFilterConstraint[];
  forbiddenFilters?: AiSemanticFilterConstraint[];
  examples: AiSemanticRuleExample[];
  priority: number;
  effectiveFrom?: number | null;
  effectiveTo?: number | null;
  source: AiSemanticRuleSource;
  extraction?: AiSemanticRuleExtraction;
  review: AiSemanticRuleReview;
  validationErrors?: string[];
}

export interface AiSemanticRuleItem {
  chunkId: string;
  documentId: string;
  documentName?: string;
  chunkIndex?: number;
  enabled?: number;
  createTime?: number;
  updateTime: number;
  rule: AiSemanticRule;
}

export interface AiSemanticRulePageParams {
  documentId: string;
  current: number;
  pageSize: number;
  keyword?: string;
  reviewStatus?: AiSemanticRuleReviewStatus;
}

export interface AiSemanticRuleSaveParams {
  canonicalTerm: string;
  aliases: string[];
  definition?: string | null;
  mapping: Pick<AiSemanticRuleMapping, 'entity' | 'field'>;
  forbiddenMappings: AiSemanticForbiddenMapping[];
  examples: AiSemanticRuleExample[];
  priority: number;
  effectiveFrom?: number | null;
  effectiveTo?: number | null;
  expectedUpdateTime: number;
}

export interface AiSemanticRuleReviewParams {
  status: Extract<AiSemanticRuleReviewStatus, 'APPROVED' | 'REJECTED'>;
  comment?: string;
  expectedUpdateTime: number;
}

export interface AiSemanticRuleBatchReviewItem {
  chunkId: string;
  status: Extract<AiSemanticRuleReviewStatus, 'APPROVED' | 'REJECTED'>;
  comment?: string;
  expectedUpdateTime: number;
}

export interface AiSemanticRuleBatchReviewParams {
  items: AiSemanticRuleBatchReviewItem[];
}

export interface AiSemanticSchemaFieldOption {
  key: string;
  label: string;
  aliases?: string[];
  selectable?: boolean;
  filterable?: boolean;
  sortable?: boolean;
  aggregatable?: boolean;
}

export interface AiSemanticSchemaEntityOption {
  key: string;
  label: string;
  dataSource: string;
  fields: AiSemanticSchemaFieldOption[];
}

export interface AiSemanticSchemaOptionsResult {
  entities: AiSemanticSchemaEntityOption[];
}

export interface AiSemanticRuleMatch {
  ruleId: string;
  version: number;
  term: string;
  matchedBy: string;
  score: number;
  target: Pick<AiSemanticRuleMapping, 'entity' | 'field'>;
  documentId: string;
  chunkId: string;
  documentName?: string;
  pageNo?: number | null;
  sectionPath?: string | null;
}

export interface AiSemanticInjectedRule {
  ruleId: string;
  version: number;
  ruleType?: string;
  canonicalTerm: string;
  aliases?: string[];
  instruction?: string | null;
  target: Pick<AiSemanticRuleMapping, 'entity' | 'field'>;
  forbiddenTargets?: Array<Pick<AiSemanticForbiddenMapping, 'entity' | 'field'>>;
  requiredFilters?: AiSemanticFilterConstraint[];
  forbiddenFilters?: AiSemanticFilterConstraint[];
}

export interface AiSemanticFilterConstraint {
  entity: string;
  field: string;
  operator: string;
  value?: unknown;
}

export interface AiSemanticInjectedContext {
  rules: AiSemanticInjectedRule[];
}

export interface AiAgentMessageItem {
  id: string;
  role: 'assistant' | 'user';
  content: string;
  intent?: string;
  evidenceJson?: string;
  createTime?: number;
}

export type AiAgentDatasourceType = 'MYSQL' | 'POSTGRESQL' | 'ORACLE' | 'SQLSERVER' | 'FILE';

export interface AiAgentDatasourceTable {
  name: string;
  comment?: string;
  columns: { name: string; type: string; comment?: string }[];
  relations?: AiAgentDatasourceRelation[];
}

export interface AiAgentChart {
  title: string;
  type: 'column' | 'bar' | 'line' | 'pie';
  predictionStart?: number;
  dimension: string;
  categories: string[];
  series: { name: string; values: Array<number | null> }[];
  partial: boolean;
  coverage: string;
}

export interface AiAgentDatasourceRelation {
  sourceTable: string;
  sourceColumn: string;
  targetTable: string;
  targetColumn: string;
}

export interface AiAgentDatasourceConfig {
  id?: string;
  name: string;
  type: AiAgentDatasourceType;
  host?: string;
  port?: number | null;
  database?: string;
  schema?: string;
  username?: string;
  password?: string;
  ssl?: boolean;
  selectedTables: string[];
  revision?: number;
  metadata?: AiAgentDatasourceTable[];
  relations?: AiAgentDatasourceRelation[];
}

export interface AiAgentDatasourceItem extends AiAgentDatasourceConfig {
  id: string;
  revision: number;
  updateTime: number;
  passwordConfigured: boolean;
  tableCount: number;
  tables: AiAgentDatasourceTable[];
  relations?: AiAgentDatasourceRelation[];
}

export default function useAiAgentApi(CDR: CordysAxios) {
  const queryKnowledgeUrl = '/ai-agent/query-knowledge';
  function getQueryKnowledge(kind: QueryKnowledgeKind) {
    return CDR.get<QueryKnowledgeItem[]>({ url: queryKnowledgeUrl, params: { kind } });
  }
  function saveQueryKnowledge(data: QueryKnowledgeItem) {
    return CDR.post<QueryKnowledgeItem>({ url: queryKnowledgeUrl, data });
  }
  function enableQueryKnowledge(item: QueryKnowledgeItem, enabled: boolean) {
    return CDR.post<QueryKnowledgeItem>({
      url: `${queryKnowledgeUrl}/${item.id}/enabled`,
      data: { revision: item.revision, enabled },
    });
  }
  function deleteQueryKnowledge(items: QueryKnowledgeItem[]) {
    return CDR.post<void>({
      url: `${queryKnowledgeUrl}/delete`,
      data: items.map((i) => ({ id: i.id, revision: i.revision })),
    });
  }
  function testQueryKnowledge(question: string, datasourceId: string) {
    return CDR.post<QueryKnowledgeMatches>({ url: `${queryKnowledgeUrl}/search`, data: { question, datasourceId } });
  }
  function exportQueryKnowledge(kind: QueryKnowledgeKind, ids: string[], template = false) {
    return CDR.get<{ data: Blob }>(
      {
        url: `${queryKnowledgeUrl}/export`,
        params: { kind, ...(ids.length ? { ids: ids.join(',') } : {}), template },
        responseType: 'blob',
      },
      { isTransformResponse: false, isReturnNativeResponse: true }
    );
  }
  async function importQueryKnowledge(kind: QueryKnowledgeKind, file: File) {
    const response = await CDR.uploadFile<Result<{ imported: number }>>(
      { url: `${queryKnowledgeUrl}/import`, params: { kind } },
      { fileList: [file] },
      'file'
    );
    if (!response?.data) throw new Error(response?.message || '导入接口未返回有效结果');
    return response.data;
  }
  const datasourceUrl = '/ai-agent/datasources';
  function getAiAgentDatasources() {
    return CDR.get<AiAgentDatasourceItem[]>({ url: datasourceUrl }, { noErrorTip: true });
  }
  function saveAiAgentDatasource(data: AiAgentDatasourceConfig) {
    return CDR.post<AiAgentDatasourceItem>({ url: datasourceUrl, data });
  }
  function testAiAgentDatasource(data: AiAgentDatasourceConfig) {
    return CDR.post<void>({ url: `${datasourceUrl}/test`, data });
  }
  function getAiAgentDatasourceTables(data: AiAgentDatasourceConfig) {
    return CDR.post<AiAgentDatasourceTable[]>({ url: `${datasourceUrl}/tables`, data });
  }
  function deleteAiAgentDatasource(id: string) {
    return CDR.delete<void>({ url: `${datasourceUrl}/${id}` });
  }
  async function uploadAiAgentDatasource(name: string, file: File, encoding: string) {
    const response = await CDR.uploadFile<Result<AiAgentDatasourceItem>>(
      { url: `${datasourceUrl}/upload`, params: { name, encoding } },
      { fileList: [file] },
      'file'
    );
    if (!response?.data) throw new Error(response?.message || '文件接口未返回有效数据源');
    return response.data;
  }
  function chatAiAgent(data: AiAgentChatParams, signal?: AbortSignal) {
    return CDR.post<AiAgentChatResult>({ url: AiAgentChatUrl, data, signal }, { noErrorTip: true });
  }

  async function streamAiAgent(
    data: AiAgentChatParams,
    onEvent: (event: AiAgentStreamEvent) => void,
    signal?: AbortSignal
  ): Promise<AiAgentChatResult> {
    const token = getToken();
    const app = getLocalStorage<{ orgId?: string }>('app', true);
    const headers = new Headers({ 'Content-Type': 'application/json', 'Accept': 'text/event-stream' });
    headers.set('Accept-Language', localStorage.getItem('CRM-locale') || 'zh-CN');
    if (token?.sessionId) headers.set('X-AUTH-TOKEN', token.sessionId);
    if (token?.csrfToken) headers.set('CSRF-TOKEN', token.csrfToken);
    if (app?.orgId) headers.set('Organization-Id', app.orgId);
    const response = await fetch(CDR.axiosInstance.getUri({ url: `${AiAgentChatUrl}/stream` }), {
      method: 'POST',
      headers,
      credentials: 'same-origin',
      signal,
      body: JSON.stringify({ ...data, stream: true }),
    });
    // Only unsupported endpoints can be retried without risking duplicate stored turns or queries.
    if ([404, 405, 406, 415].includes(response.status)) return chatAiAgent({ ...data, stream: false }, signal);
    if (!response.ok) throw new Error(`问答请求失败（HTTP ${response.status}）`);
    if (!response.headers.get('Content-Type')?.includes('text/event-stream') || !response.body)
      throw new Error('流式接口未返回有效数据，请重新登录或检查后端版本');
    return readAiAgentStream<AiAgentChatResult>(response.body, onEvent);
  }

  async function chatAiAgentWithAttachments(data: AiAgentChatParams, files: File[], signal?: AbortSignal) {
    const response = await CDR.uploadFile<Result<AiAgentChatResult>>(
      { url: AiAgentAttachmentChatUrl, noErrorTip: true, signal },
      { fileList: files, request: data },
      'files'
    );
    if (!response?.data) {
      throw new Error(response?.message || '附件接口未返回有效结果');
    }
    return response.data;
  }

  function cancelAiAgentChat(requestId: string) {
    return CDR.post<void>({ url: `${AiAgentCancelChatUrl}/${requestId}` }, { noErrorTip: true });
  }

  function exportAiAgentResult(sessionId: string, messageId: string, datasourceId?: string) {
    return CDR.post<Blob>(
      {
        url: '/ai-agent/result/export',
        data: { sessionId, referenceMessageId: messageId, question: '导出查询结果', datasourceId },
        responseType: 'blob',
      },
      { isTransformResponse: false }
    );
  }

  function transcribeAiAgentAudio(file: File, language?: AiAgentAudioLanguage) {
    return CDR.uploadFile<Result<AiAgentAudioTranscriptionResult>>(
      { url: AiAgentAudioTranscriptionsUrl, params: language ? { language } : undefined },
      { fileList: [file] },
      'file'
    );
  }

  function getAiAgentAudioTranscription(taskId: string, language?: AiAgentAudioLanguage) {
    return CDR.get<AiAgentAudioTranscriptionResult>({
      url: `${AiAgentAudioTranscriptionQueryUrl}/${taskId}`,
      params: language ? { language } : undefined,
    });
  }

  function feedbackAiAgent(data: AiAgentFeedbackParams) {
    return CDR.post<void>({ url: AiAgentFeedbackUrl, data });
  }

  function getAiAgentSessions() {
    return CDR.get<AiAgentSessionItem[]>({ url: AiAgentSessionsUrl }, { noErrorTip: true });
  }

  function getAiAgentMessages(sessionId: string) {
    return CDR.get<AiAgentMessageItem[]>({ url: `${AiAgentMessagesUrl}/${sessionId}/messages` }, { noErrorTip: true });
  }

  function deleteAiAgentSession(sessionId: string) {
    return CDR.delete<void>({ url: `${AiAgentDeleteSessionUrl}/${sessionId}` }, { noErrorTip: true });
  }

  function getAiKnowledgeDocumentPage(data: AiKnowledgeDocumentPageParams) {
    return CDR.post<AiAgentPager<AiKnowledgeDocumentItem>>({ url: AiKnowledgeDocumentPageUrl, data });
  }

  function getAiKnowledgeDocumentDetail(id: string) {
    return CDR.get<AiKnowledgeDocumentItem>({ url: `${AiKnowledgeDocumentDetailUrl}/${id}` }, { noErrorTip: true });
  }

  function getAiKnowledgeDocumentEditable(id: string) {
    return CDR.get<AiKnowledgeDocumentEditResult>({ url: `${AiKnowledgeDocumentEditUrl}/${id}` });
  }

  function editAiKnowledgeDocument(id: string, data: AiKnowledgeDocumentEditParams) {
    return CDR.post<AiKnowledgeDocumentItem>({ url: `${AiKnowledgeDocumentEditUrl}/${id}`, data });
  }

  function getAiKnowledgeDocumentVersions(data: { documentId: string; current: number; pageSize: number }) {
    return CDR.post<AiAgentPager<AiKnowledgeDocumentVersionItem>>({
      url: AiKnowledgeDocumentVersionPageUrl,
      data,
    });
  }

  function getAiKnowledgeDocumentVersionDetail(id: string) {
    return CDR.get<AiKnowledgeDocumentVersionItem>({ url: `${AiKnowledgeDocumentVersionDetailUrl}/${id}` });
  }

  function restoreAiKnowledgeDocumentVersion(id: string, expectedRevision: number) {
    return CDR.post<AiKnowledgeDocumentItem>({
      url: `${AiKnowledgeDocumentVersionRestoreUrl}/${id}`,
      data: { expectedRevision },
    });
  }

  function deleteAiKnowledgeDocumentVersion(id: string) {
    return CDR.post<void>({ url: `${AiKnowledgeDocumentVersionDeleteUrl}/${id}` });
  }

  function uploadAiKnowledgeDocument(file: File, remark?: string) {
    return CDR.uploadFile<AiKnowledgeDocumentItem>(
      { url: AiKnowledgeDocumentUploadUrl, params: { remark } },
      { fileList: [file] },
      'file'
    );
  }

  function getAiKnowledgeChunkPage(data: AiKnowledgeChunkPageParams) {
    return CDR.post<AiAgentPager<AiKnowledgeChunkItem>>({ url: AiKnowledgeDocumentChunkPageUrl, data });
  }

  function reparseAiKnowledgeDocument(id: string) {
    return CDR.post<void>({ url: `${AiKnowledgeDocumentReparseUrl}/${id}` });
  }

  function enableAiKnowledgeDocument(id: string) {
    return CDR.post<void>({ url: `${AiKnowledgeDocumentEnableUrl}/${id}` });
  }

  function disableAiKnowledgeDocument(id: string) {
    return CDR.post<void>({ url: `${AiKnowledgeDocumentDisableUrl}/${id}` });
  }

  function deleteAiKnowledgeDocument(id: string) {
    return CDR.post<void>({ url: `${AiKnowledgeDocumentDeleteUrl}/${id}` });
  }

  function getAiKnowledgeDocumentDownloadUrl(id: string) {
    return `${AiKnowledgeDocumentDownloadUrl}/${id}`;
  }

  function getAiSemanticRulePage(data: AiSemanticRulePageParams) {
    return CDR.post<AiAgentPager<AiSemanticRuleItem>>({ url: AiSemanticRulePageUrl, data });
  }

  function saveAiSemanticRule(chunkId: string, data: AiSemanticRuleSaveParams) {
    return CDR.post<AiSemanticRuleItem>({ url: `${AiSemanticRuleSaveUrl}/${chunkId}`, data });
  }

  function reviewAiSemanticRule(chunkId: string, data: AiSemanticRuleReviewParams) {
    return CDR.post<AiSemanticRuleItem>({ url: `${AiSemanticRuleReviewUrl}/${chunkId}`, data });
  }

  function batchReviewAiSemanticRules(data: AiSemanticRuleBatchReviewParams) {
    return CDR.post<AiSemanticRuleItem[]>({ url: AiSemanticRuleBatchReviewUrl, data });
  }

  function getAiSemanticRuleSchemaOptions() {
    return CDR.get<AiSemanticSchemaOptionsResult>({ url: AiSemanticRuleSchemaOptionsUrl });
  }

  function testAiKnowledgeSearch(question: string, topK = 8, mode: AiKnowledgeSearchMode = 'AUTO') {
    return CDR.post<AiKnowledgeSearchTestResult>({ url: AiKnowledgeSearchTestUrl, data: { question, topK, mode } });
  }

  return {
    getQueryKnowledge,
    saveQueryKnowledge,
    enableQueryKnowledge,
    deleteQueryKnowledge,
    testQueryKnowledge,
    exportQueryKnowledge,
    importQueryKnowledge,
    getAiAgentDatasources,
    saveAiAgentDatasource,
    testAiAgentDatasource,
    getAiAgentDatasourceTables,
    deleteAiAgentDatasource,
    uploadAiAgentDatasource,
    chatAiAgent,
    streamAiAgent,
    chatAiAgentWithAttachments,
    cancelAiAgentChat,
    exportAiAgentResult,
    transcribeAiAgentAudio,
    getAiAgentAudioTranscription,
    deleteAiAgentSession,
    feedbackAiAgent,
    getAiAgentSessions,
    getAiAgentMessages,
    getAiKnowledgeDocumentPage,
    getAiKnowledgeDocumentDetail,
    getAiKnowledgeDocumentEditable,
    editAiKnowledgeDocument,
    getAiKnowledgeDocumentVersions,
    getAiKnowledgeDocumentVersionDetail,
    restoreAiKnowledgeDocumentVersion,
    deleteAiKnowledgeDocumentVersion,
    uploadAiKnowledgeDocument,
    getAiKnowledgeChunkPage,
    reparseAiKnowledgeDocument,
    enableAiKnowledgeDocument,
    disableAiKnowledgeDocument,
    deleteAiKnowledgeDocument,
    getAiKnowledgeDocumentDownloadUrl,
    getAiSemanticRulePage,
    saveAiSemanticRule,
    reviewAiSemanticRule,
    batchReviewAiSemanticRules,
    getAiSemanticRuleSchemaOptions,
    testAiKnowledgeSearch,
  };
}
