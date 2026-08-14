import type { CordysAxios } from '@lib/shared/api/http/Axios';
import type { Result } from '@lib/shared/types/axios';
import {
  AiAgentAudioTranscriptionQueryUrl,
  AiAgentAudioTranscriptionsUrl,
  AiAgentAttachmentChatUrl,
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
  AiKnowledgeDocumentEnableUrl,
  AiKnowledgeDocumentPageUrl,
  AiKnowledgeDocumentReparseUrl,
  AiKnowledgeDocumentUploadUrl,
  AiKnowledgeSearchTestUrl,
  AiSemanticRuleBatchReviewUrl,
  AiSemanticRulePageUrl,
  AiSemanticRuleReviewUrl,
  AiSemanticRuleSaveUrl,
  AiSemanticRuleSchemaOptionsUrl,
} from '@lib/shared/api/requrls/aiAgent';

export interface AiAgentChatParams {
  requestId?: string;
  sessionId?: string;
  question: string;
  stream?: boolean;
  timeRange?: string;
  dataScope?: string;
  llmProvider?: string;
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

export interface AiAgentChatResult {
  sessionId: string;
  messageId: string;
  answer: string;
  intent?: string;
  points?: string[];
  evidence?: string[];
  warnings?: string[];
  tools?: AiAgentToolCall[];
  citations?: AiAgentCitation[];
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

export default function useAiAgentApi(CDR: CordysAxios) {
  function chatAiAgent(data: AiAgentChatParams, signal?: AbortSignal) {
    return CDR.post<AiAgentChatResult>({ url: AiAgentChatUrl, data, signal }, { noErrorTip: true });
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
    chatAiAgent,
    chatAiAgentWithAttachments,
    cancelAiAgentChat,
    transcribeAiAgentAudio,
    getAiAgentAudioTranscription,
    deleteAiAgentSession,
    feedbackAiAgent,
    getAiAgentSessions,
    getAiAgentMessages,
    getAiKnowledgeDocumentPage,
    getAiKnowledgeDocumentDetail,
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
