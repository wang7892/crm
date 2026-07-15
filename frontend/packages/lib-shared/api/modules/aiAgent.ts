import type { CordysAxios } from '@lib/shared/api/http/Axios';
import type { Result } from '@lib/shared/types/axios';
import {
  AiAgentAudioTranscriptionQueryUrl,
  AiAgentAudioTranscriptionsUrl,
  AiAgentChatUrl,
  AiAgentDeleteSessionUrl,
  AiAgentFeedbackUrl,
  AiAgentMessagesUrl,
  AiAgentSessionsUrl,
  AiKnowledgeDocumentChunkPageUrl,
  AiKnowledgeDocumentDeleteUrl,
  AiKnowledgeDocumentDisableUrl,
  AiKnowledgeDocumentDownloadUrl,
  AiKnowledgeDocumentEnableUrl,
  AiKnowledgeDocumentPageUrl,
  AiKnowledgeDocumentReparseUrl,
  AiKnowledgeDocumentUploadUrl,
  AiKnowledgeSearchTestUrl,
} from '@lib/shared/api/requrls/aiAgent';

export interface AiAgentChatParams {
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
  remark?: string;
  createTime?: number;
  updateTime?: number;
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
  function chatAiAgent(data: AiAgentChatParams) {
    return CDR.post<AiAgentChatResult>({ url: AiAgentChatUrl, data }, { noErrorTip: true });
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

  function uploadAiKnowledgeDocument(file: File, category?: string, remark?: string) {
    return CDR.uploadFile<AiKnowledgeDocumentItem>(
      { url: AiKnowledgeDocumentUploadUrl, params: { category, remark } },
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

  function testAiKnowledgeSearch(question: string, topK = 8) {
    return CDR.post<AiKnowledgeSearchTestResult>({ url: AiKnowledgeSearchTestUrl, data: { question, topK } });
  }

  return {
    chatAiAgent,
    transcribeAiAgentAudio,
    getAiAgentAudioTranscription,
    deleteAiAgentSession,
    feedbackAiAgent,
    getAiAgentSessions,
    getAiAgentMessages,
    getAiKnowledgeDocumentPage,
    uploadAiKnowledgeDocument,
    getAiKnowledgeChunkPage,
    reparseAiKnowledgeDocument,
    enableAiKnowledgeDocument,
    disableAiKnowledgeDocument,
    deleteAiKnowledgeDocument,
    getAiKnowledgeDocumentDownloadUrl,
    testAiKnowledgeSearch,
  };
}
