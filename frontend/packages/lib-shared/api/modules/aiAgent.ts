import type { CordysAxios } from '@lib/shared/api/http/Axios';
import {
  AiAgentChatUrl,
  AiAgentDeleteSessionUrl,
  AiAgentFeedbackUrl,
  AiAgentMessagesUrl,
  AiAgentSessionsUrl,
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

  return {
    chatAiAgent,
    deleteAiAgentSession,
    feedbackAiAgent,
    getAiAgentSessions,
    getAiAgentMessages,
  };
}
