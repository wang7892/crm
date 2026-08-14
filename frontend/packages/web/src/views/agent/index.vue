<template>
  <div class="crm-chat-shell">
    <aside class="chat-sidebar">
      <section class="sidebar-top">
        <div class="sidebar-actions">
          <button type="button" class="sidebar-action" @click="createNewChat">
            <CrmIcon type="iconicon_edit" :size="17" />
            <span>新聊天</span>
          </button>
          <div v-if="chatSearchOpen" class="sidebar-search">
            <n-icon :size="18" class="sidebar-search__icon"><Search /></n-icon>
            <input
              ref="chatSearchInputRef"
              v-model="chatSearchQuery"
              class="sidebar-search__input"
              type="search"
              placeholder="搜索聊天标题或问题"
              aria-label="搜索聊天标题或问题"
              @keydown.esc="closeChatSearch"
            />
            <button
              type="button"
              class="sidebar-search__close"
              title="关闭搜索"
              aria-label="关闭搜索"
              @click="closeChatSearch"
            >
              <n-icon :size="17"><CloseOutline /></n-icon>
            </button>
          </div>
          <button v-else type="button" class="sidebar-action" @click="openChatSearch">
            <n-icon :size="18"><Search /></n-icon>
            <span>搜索聊天</span>
          </button>
        </div>

        <div class="feature-list">
          <button
            v-for="feature in features"
            :key="feature.key"
            type="button"
            class="feature-item"
            :class="{ 'feature-item--active': activeFeature === feature.key }"
            @click="selectFeature(feature.key, feature.question)"
          >
            <CrmIcon :type="feature.icon" :size="17" />
            <span>{{ feature.label }}</span>
          </button>
        </div>
      </section>

      <section class="history-panel">
        <div class="history-title">聊天记录</div>
        <n-scrollbar class="history-scroll">
          <div
            v-for="session in filteredSessions"
            :key="session.id"
            class="history-row"
            :class="{ 'history-row--active': activeSessionId === session.id }"
          >
            <button type="button" class="history-item" @click="selectSession(session.id)">
              <span class="history-item__title">{{ session.title }}</span>
              <span class="history-item__time">{{ session.time }}</span>
            </button>
            <n-button
              circle
              quaternary
              size="tiny"
              class="history-delete"
              title="删除聊天"
              @click.stop="deleteSession(session.id)"
            >
              <template #icon>
                <n-icon :size="15"><TrashOutline /></n-icon>
              </template>
            </n-button>
          </div>
          <div v-if="chatSearchQuery.trim() && filteredSessions.length === 0" class="history-empty">
            {{ chatSearchLoading ? '正在搜索...' : '未找到相关聊天' }}
          </div>
        </n-scrollbar>
        <button class="history-more" type="button">展开显示</button>
      </section>

      <div class="sidebar-user">
        <div class="user-avatar">{{ currentUserInitial }}</div>
        <div>
          <div class="user-name">{{ currentUserName }}</div>
          <div class="user-plan">{{ currentUserRoleName }}</div>
        </div>
      </div>
    </aside>

    <section class="chat-main">
      <header class="chat-header">
        <n-dropdown trigger="click" :options="llmProviderOptions" @select="selectLlmProvider">
          <button type="button" class="agent-picker">
            <span>{{ selectedLlmProviderLabel }}</span>
            <CrmIcon type="iconicon_chevron_down" :size="16" />
          </button>
        </n-dropdown>
        <div class="chat-header__tools">
          <n-select v-model:value="timeRange" class="header-select" size="small" :options="timeRangeOptions" />
          <n-button size="small" quaternary circle>
            <template #icon>
              <CrmIcon type="iconicon_refresh" :size="16" />
            </template>
          </n-button>
        </div>
      </header>

      <n-scrollbar class="chat-scroll">
        <div class="chat-space">
          <div v-if="currentSession.messages.length === 0" class="empty-state">
            <div class="empty-title">你好，我是客户经营智能体</div>
            <div class="empty-subtitle">可以问销售专员、客户沟通、外部订单和跟进情况</div>
            <div class="empty-prompts">
              <button
                v-for="prompt in quickPrompts"
                :key="prompt"
                type="button"
                class="empty-prompt"
                @click="askQuestion(prompt)"
              >
                {{ prompt }}
              </button>
            </div>
          </div>

          <div v-else class="message-list">
            <div
              v-for="message in currentSession.messages"
              :key="message.id"
              class="message-row"
              :class="`message-row--${message.role}`"
            >
              <div v-if="message.role === 'assistant'" class="message-avatar">
                <CrmIcon type="iconicon_bot" :size="17" />
              </div>
              <div class="message-content">
                <div v-if="message.role === 'assistant'" class="message-name">
                  {{ message.llmProviderLabel || selectedLlmProviderLabel }}
                </div>
                <div v-if="message.attachments?.length" class="message-attachments">
                  <div
                    v-for="attachment in message.attachments"
                    :key="`${message.id}-${attachment.name}`"
                    class="message-attachment"
                    :title="attachment.name"
                  >
                    <n-icon :size="16">
                      <ImageOutline v-if="isImageAttachment(attachment.type)" />
                      <DocumentOutline v-else />
                    </n-icon>
                    <span>{{ attachment.name }}</span>
                  </div>
                  <span v-if="message.attachmentMode" class="message-attachment-mode">
                    {{ attachmentModeText(message.attachmentMode) }}
                  </span>
                </div>
                <div class="message-text">{{ message.content }}</div>
                <div v-if="message.points?.length" class="message-points">
                  <ul v-if="plainPoints(message.points).length" class="message-point-list">
                    <li v-for="point in plainPoints(message.points)" :key="point" class="message-point">
                      {{ point }}
                    </li>
                  </ul>
                  <div v-if="detailRows(message.points).length" class="message-table-wrap">
                    <div class="message-table-scroll">
                      <table class="message-detail-table">
                        <thead>
                          <tr>
                            <th v-for="column in detailColumns(message.points)" :key="column">
                              {{ column }}
                            </th>
                          </tr>
                        </thead>
                        <tbody>
                          <tr v-for="(row, rowIndex) in detailRows(message.points)" :key="row.title || rowIndex">
                            <td v-for="column in detailColumns(message.points)" :key="column">
                              <span :title="detailCell(row, column)">{{ detailCell(row, column) }}</span>
                            </td>
                          </tr>
                        </tbody>
                      </table>
                    </div>
                  </div>
                </div>
                <div v-if="message.evidence?.length" class="evidence-actions">
                  <n-button
                    v-for="item in message.evidence"
                    :key="item.key"
                    class="evidence-button"
                    size="small"
                    type="primary"
                    secondary
                    strong
                    :disabled="!item.routeName"
                    @click="openEvidence(item)"
                  >
                    {{ item.label }}
                  </n-button>
                </div>
              </div>
            </div>
          </div>
        </div>
      </n-scrollbar>

      <footer class="composer-wrap">
        <div class="context-line">
          <span>时间范围：{{ timeRangeLabel }}</span>
          <span>数据范围：当前组织全部数据</span>
          <span>证据：客户 / 邮件 / 企微 / 跟进 / 外部订单</span>
        </div>
        <div v-if="selectedAttachmentFiles.length" class="attachment-tray">
          <div class="attachment-tray__summary">
            <span>{{ selectedAttachmentModeText }}</span>
            <span>{{ selectedAttachmentFiles.length }}/5</span>
          </div>
          <div class="attachment-tray__list">
            <div
              v-for="(file, index) in selectedAttachmentFiles"
              :key="`${file.name}-${file.size}-${file.lastModified}`"
              class="attachment-chip"
            >
              <n-icon :size="16">
                <ImageOutline v-if="isImageAttachment(fileExtension(file.name))" />
                <DocumentOutline v-else />
              </n-icon>
              <span class="attachment-chip__name" :title="file.name">{{ file.name }}</span>
              <span class="attachment-chip__size">{{ formatFileSize(file.size) }}</span>
              <button
                type="button"
                class="attachment-chip__remove"
                :aria-label="`移除 ${file.name}`"
                :title="`移除 ${file.name}`"
                @click="removeAttachment(index)"
              >
                <n-icon :size="15"><CloseOutline /></n-icon>
              </button>
            </div>
          </div>
        </div>
        <div class="composer">
          <input
            ref="attachmentInputRef"
            class="attachment-input"
            type="file"
            multiple
            accept=".jpg,.jpeg,.png,.webp,.pdf,.docx,.xls,.xlsx,.txt"
            @change="handleAttachmentSelection"
          />
          <n-button
            circle
            quaternary
            title="添加附件"
            :disabled="loading || selectedAttachmentFiles.length >= 5"
            @click="openAttachmentPicker"
          >
            <template #icon>
              <CrmIcon type="iconicon_add" :size="18" />
            </template>
          </n-button>
          <n-input
            v-model:value="questionInput"
            class="composer-input"
            type="textarea"
            :autosize="{ minRows: 1, maxRows: 4 }"
            placeholder="有问题，尽管问"
            @paste="handleComposerPaste"
            @keydown.enter.exact.prevent="askQuestion(questionInput)"
          />
          <n-button circle quaternary>
            <template #icon>
              <CrmIcon type="iconicon_phone" :size="18" />
            </template>
          </n-button>
          <n-button
            v-if="loading"
            circle
            type="primary"
            class="send-button"
            title="停止回答"
            aria-label="停止回答"
            @click="stopCurrentRequest"
          >
            <template #icon>
              <n-icon :size="17"><Stop /></n-icon>
            </template>
          </n-button>
          <n-button v-else circle type="primary" class="send-button" @click="askQuestion(questionInput)">
            <template #icon>
              <CrmIcon type="iconicon_chevron_right" :size="18" />
            </template>
          </n-button>
        </div>
      </footer>
    </section>
  </div>
</template>

<script setup lang="ts">
  import { useRouter } from 'vue-router';
  import { NButton, NDropdown, NIcon, NInput, NScrollbar, NSelect, useMessage } from 'naive-ui';
  import { CloseOutline, DocumentOutline, ImageOutline, Search, Stop, TrashOutline } from '@vicons/ionicons5';

  import type {
    AiAgentChatResult,
    AiAgentCitation,
    AiAgentMessageItem,
    AiAgentSessionItem,
  } from '@lib/shared/api/modules/aiAgent';

  import CrmIcon from '@/components/pure/crm-icon-font/index.vue';

  import {
    cancelAiAgentChat,
    chatAiAgent,
    chatAiAgentWithAttachments,
    deleteAiAgentSession,
    getAiAgentMessages,
    getAiAgentSessions,
  } from '@/api/modules';
  import useUserStore from '@/store/modules/user';

  import { ContractRouteEnum, CustomerRouteEnum } from '@/enums/routeEnum';

  type MessageRole = 'assistant' | 'user';
  type FeatureKey = 'config';
  type CurrentUserRole = string | { name?: string };

  interface EvidenceItem {
    key: string;
    label: string;
    routeName?: string;
    query?: Record<string, string>;
  }

  interface MessageItem {
    id: number | string;
    role: MessageRole;
    content: string;
    points?: string[];
    evidence?: EvidenceItem[];
    llmProviderLabel?: string;
    attachments?: AttachmentItem[];
    attachmentMode?: AttachmentMode;
  }

  interface AttachmentItem {
    name: string;
    size: number;
    type: string;
  }

  type AttachmentMode = 'CHAT' | 'KNOWLEDGE';

  interface DetailTableRow {
    title: string;
    fields: Record<string, string>;
  }

  interface ChatSession {
    id: string;
    title: string;
    question: string;
    owner: string;
    time: string;
    agentName: string;
    messages: MessageItem[];
    messagesLoaded: boolean;
  }

  interface FeatureItem {
    key: FeatureKey;
    label: string;
    icon: string;
    question: string;
  }

  const router = useRouter();
  const messageApi = useMessage();
  const userStore = useUserStore();
  const activeFeature = ref<FeatureKey | null>(null);
  const activeSessionId = ref('default');
  const chatSearchOpen = ref(false);
  const chatSearchQuery = ref('');
  const chatSearchLoading = ref(false);
  const chatSearchInputRef = ref<HTMLInputElement | null>(null);
  const attachmentInputRef = ref<HTMLInputElement | null>(null);
  const selectedAttachmentFiles = ref<File[]>([]);
  const questionInput = ref('');
  const timeRange = ref('30d');
  const loading = ref(false);
  const selectedLlmProvider = ref('primary');
  let activeRequestController: AbortController | null = null;
  let activeRequestId: string | null = null;

  const allowedAttachmentTypes = new Set(['jpg', 'jpeg', 'png', 'webp', 'pdf', 'docx', 'xls', 'xlsx', 'txt']);
  const knowledgeIntentPhrases = [
    '加入知识库',
    '添加到知识库',
    '保存到知识库',
    '存入知识库',
    '放进知识库',
    '上传到知识库',
    '加入公司知识库',
    '保存为公司知识',
    '沉淀为知识',
    '沉淀到知识库',
  ];
  const knowledgeQuestionPhrases = [
    '是否加入知识库',
    '适合加入知识库',
    '能否加入知识库',
    '能不能加入知识库',
    '可以加入知识库吗',
  ];
  const knowledgeNegativePhrases = [
    '不要加入知识库',
    '不用加入知识库',
    '别加入知识库',
    '不要保存到知识库',
    '仅当前聊天',
    '只在当前聊天',
  ];
  const knowledgeNegativePattern = /(?:不|别|勿|无需|无须|暂不|禁止).{0,8}(?:知识库|公司知识)/;
  const knowledgeQuestionPattern =
    /(?:(?:是否|能否|可否|要不要|需不需要|该不该|能不能|可以不可以).{0,10}(?:知识库|公司知识)|(?:知识库|公司知识).{0,6}(?:吗|么|呢)[？?]?)/;
  const knowledgeIntentPattern = /(?:加入|添加|保存|存入|存到|放进|放入|上传|沉淀).{0,16}(?:公司)?知识库(?:中|里|内)?/;

  const currentUserName = computed(() => userStore.userInfo.name || '当前用户');
  const currentUserInitial = computed(() => currentUserName.value.trim().charAt(0) || '用');
  const currentUserRoleName = computed(() => {
    if (userStore.isAdmin) {
      return '管理员';
    }
    const currentRole = userStore.userInfo.roles[0] as CurrentUserRole | undefined;
    return typeof currentRole === 'string' ? currentRole : currentRole?.name || '联系专员';
  });

  const llmProviderOptions = [
    { label: 'GPT-5.6 Sol', key: 'primary' },
    { label: 'qwen', key: 'qwen' },
    { label: 'deepseek', key: 'deepseek' },
  ];

  const selectedLlmProviderLabel = computed(
    () =>
      llmProviderOptions.find((item) => item.key === selectedLlmProvider.value)?.label || llmProviderOptions[0].label
  );

  function selectLlmProvider(key: string | number) {
    selectedLlmProvider.value = String(key);
  }

  const evidenceRouteMap: Record<string, { routeName: string; query?: Record<string, string> }> = {
    'customer': { routeName: CustomerRouteEnum.CUSTOMER_INDEX },
    'wecom_ingestion_message': { routeName: CustomerRouteEnum.CUSTOMER_WECOM },
    'email_webhook_event': {
      routeName: CustomerRouteEnum.CUSTOMER_FOLLOW_RECORD_INDEX,
      query: { monitorSource: 'MAIL' },
    },
    'follow_up_record': { routeName: CustomerRouteEnum.CUSTOMER_FOLLOW_RECORD_INDEX },
    'contract_info': { routeName: ContractRouteEnum.CONTRACT_INDEX },
    'mls_agent_data.contract_info': { routeName: ContractRouteEnum.CONTRACT_INDEX },
  };
  const hiddenEvidenceSources = new Set(['wecom_ingestion_message', 'email_webhook_event']);
  const evidenceLabelMap: Record<string, string> = {
    'customer': '客户',
    'email_webhook_event': '邮件',
    'follow_up_record': '跟进记录',
    'contract_info': '合同订单',
    'mls_agent_data.contract_info': '合同订单',
  };

  function findCitationForEvidence(source: string, citations: AiAgentCitation[]) {
    return citations.find((item) => item.module === source || item.type === source || item.title === source);
  }

  function buildEvidenceItem(source: string, citation?: AiAgentCitation): EvidenceItem {
    const route = evidenceRouteMap[source] || (citation?.module ? evidenceRouteMap[citation.module] : undefined);
    const recordIds = citation?.recordIds?.filter(Boolean) || [];
    const query: Record<string, string> = {
      ...(route?.query || {}),
      source: 'aiAgent',
      evidence: source,
    };
    const [recordId] = recordIds;
    if (recordIds.length === 1 && (source === 'customer' || citation?.module === 'customer')) {
      query.id = recordId;
    }
    if (recordIds.length) {
      query.recordIds = recordIds.join(',');
    }
    return {
      key: source,
      label: evidenceLabelMap[source] || source,
      routeName: route?.routeName,
      query,
    };
  }

  function buildEvidenceItems(sources: string[], citations: AiAgentCitation[] = []): EvidenceItem[] {
    const seen = new Set<string>();
    return sources
      .map((source) => source.trim())
      .filter((source) => {
        if (!source || hiddenEvidenceSources.has(source) || seen.has(source)) {
          return false;
        }
        seen.add(source);
        return true;
      })
      .map((source) => buildEvidenceItem(source, findCitationForEvidence(source, citations)));
  }

  const timeRangeOptions = [
    { label: '近 7 天', value: '7d' },
    { label: '近 30 天', value: '30d' },
    { label: '本季度', value: 'quarter' },
    { label: '本年度', value: 'year' },
  ];

  const features: FeatureItem[] = [
    {
      key: 'config',
      label: '配置',
      icon: 'iconicon_set_up',
      question: '我没有权限查看的销售、客户或订单能不能查？',
    },
  ];

  const quickPrompts = [
    '张三这个月和客户沟通的情况怎么样？',
    '张三负责的客户这个月有没有新的订单？',
    '某客户最近有没有新订单？',
    '张三负责的客户最近有哪些订单正在操作？',
  ];

  const sessions = ref<ChatSession[]>([
    {
      id: 'default',
      title: '修改CRM智能体方案',
      question: '今天哪些客户最值得关注？',
      owner: '老板视角',
      time: '2天',
      agentName: '客户经营智能体',
      messagesLoaded: true,
      messages: [
        {
          id: 1,
          role: 'user',
          content: '今天哪些客户最值得关注？',
        },
        {
          id: 2,
          role: 'assistant',
          content: '你可以直接询问某个销售专员与客户的沟通情况，也可以查询可见客户在外部订单表中的订单状态。',
          points: [
            '不会展示聊天内容或邮件正文，只展示统计和客户名单。',
            '订单/合同进度来自外部 mls_agent_data.contract_info。',
            '无权限客户、销售或订单会拒绝返回明细。',
          ],
          evidence: buildEvidenceItems([
            'customer',
            'email_webhook_event',
            'wecom_ingestion_message',
            'follow_up_record',
            'contract_info',
          ]),
        },
      ],
    },
    {
      id: 'risk',
      title: '配置CRM外网访问',
      question: '本月哪些客户可能回款延期？',
      owner: '销售总监',
      time: '3天',
      agentName: '客户经营智能体',
      messagesLoaded: true,
      messages: [],
    },
    {
      id: 'competitor',
      title: '查看邮箱与企微ID匹配逻辑',
      question: '最近有没有客户提到竞品？',
      owner: '运营负责人',
      time: '3天',
      agentName: '客户经营智能体',
      messagesLoaded: true,
      messages: [],
    },
    {
      id: 'wecom',
      title: '改为每分钟自动同步消息',
      question: '高价值客户里谁最近互动变少？',
      owner: '老板视角',
      time: '3天',
      agentName: '客户经营智能体',
      messagesLoaded: true,
      messages: [],
    },
    {
      id: 'scrollbar',
      title: '优化企微跟进记录滚动条',
      question: '优化企微跟进记录滚动条',
      owner: '产品设计',
      time: '3天',
      agentName: '客户经营智能体',
      messagesLoaded: true,
      messages: [],
    },
  ]);

  const timeRangeLabel = computed(
    () => timeRangeOptions.find((item) => item.value === timeRange.value)?.label || '近 30 天'
  );
  const currentSession = computed(
    () => sessions.value.find((item) => item.id === activeSessionId.value) || sessions.value[0]
  );
  const filteredSessions = computed(() => {
    const keyword = chatSearchQuery.value.trim().toLocaleLowerCase();
    if (!keyword) {
      return sessions.value;
    }
    return sessions.value.filter((session) => {
      if (session.title.toLocaleLowerCase().includes(keyword)) {
        return true;
      }
      if (session.question.toLocaleLowerCase().includes(keyword)) {
        return true;
      }
      return session.messages.some(
        (message) => message.role === 'user' && message.content.toLocaleLowerCase().includes(keyword)
      );
    });
  });

  function buildFallbackAnswer(): MessageItem {
    return {
      id: Date.now() + 1,
      role: 'assistant',
      content: '后端智能体接口暂时不可用，请确认 CRM 后端已启动并已应用 ai_agent 相关表结构。',
      points: ['接口：POST /ai-agent/chat。', '需要当前账号已开通智能体功能。', '外部订单查询需要配置只读数据源。'],
      evidence: buildEvidenceItems(['ai_agent_session', 'ai_agent_message']),
    };
  }

  function toStringArray(value: unknown) {
    return Array.isArray(value) ? value.filter((item): item is string => typeof item === 'string' && !!item) : [];
  }

  function shouldRenderWarning(warning: string) {
    const text = warning.trim();
    return !(
      text.includes('未返回聊天内容') ||
      text.includes('不展示聊天内容') ||
      (text.includes('聊天内容') && text.includes('邮件正文'))
    );
  }

  function buildAssistantPoints(response?: Partial<AiAgentChatResult>) {
    if (!response) {
      return [];
    }
    return [
      ...toStringArray(response.points),
      ...toStringArray(response.warnings)
        .filter(shouldRenderWarning)
        .map((item) => `提示：${item}`),
    ];
  }

  function buildAssistantEvidence(response?: Partial<AiAgentChatResult>): EvidenceItem[] {
    if (!response) {
      return [];
    }
    const citations = Array.isArray(response.citations) ? response.citations : [];
    const evidence = buildEvidenceItems(toStringArray(response.evidence), citations);
    if (evidence.length) {
      return evidence;
    }
    return buildEvidenceItems(
      citations.map((item) => item.module || item.type || item.title || '').filter(Boolean),
      citations
    );
  }

  function toAssistantMessage(response: AiAgentChatResult, llmProviderLabel?: string): MessageItem {
    return {
      id: response.messageId || `${Date.now()}-assistant`,
      role: 'assistant',
      content: response.answer,
      points: buildAssistantPoints(response),
      evidence: buildAssistantEvidence(response),
      llmProviderLabel,
    };
  }

  function parseStoredAssistantResponse(evidenceJson?: string) {
    if (!evidenceJson) {
      return undefined;
    }
    try {
      const snapshot = JSON.parse(evidenceJson) as
        | ({ response?: Partial<AiAgentChatResult> } & Partial<AiAgentChatResult>)
        | null;
      if (!snapshot || typeof snapshot !== 'object') {
        return undefined;
      }
      return snapshot.response || snapshot;
    } catch {
      return undefined;
    }
  }

  function parseStoredAttachmentSnapshot(evidenceJson?: string) {
    if (!evidenceJson) {
      return undefined;
    }
    try {
      const snapshot = JSON.parse(evidenceJson) as {
        attachmentMode?: AttachmentMode;
        attachments?: Array<Partial<AttachmentItem>>;
      };
      const attachments = Array.isArray(snapshot.attachments)
        ? snapshot.attachments
            .filter((item): item is AttachmentItem =>
              Boolean(
                item && typeof item.name === 'string' && typeof item.size === 'number' && typeof item.type === 'string'
              )
            )
            .map((item) => ({ name: item.name, size: item.size, type: item.type }))
        : [];
      if (!attachments.length) {
        return undefined;
      }
      return {
        attachments,
        attachmentMode: snapshot.attachmentMode === 'KNOWLEDGE' ? 'KNOWLEDGE' : 'CHAT',
      } as const;
    } catch {
      return undefined;
    }
  }

  function formatTime(value?: number) {
    if (!value) {
      return '刚刚';
    }
    const diff = Date.now() - value;
    if (diff < 60_000) {
      return '刚刚';
    }
    if (diff < 3_600_000) {
      return `${Math.floor(diff / 60_000)}分钟前`;
    }
    if (diff < 86_400_000) {
      return `${Math.floor(diff / 3_600_000)}小时前`;
    }
    return `${Math.floor(diff / 86_400_000)}天前`;
  }

  function toSession(item: AiAgentSessionItem): ChatSession {
    return {
      id: item.id,
      title: item.title || '新聊天',
      question: item.title || '',
      owner: '当前用户',
      time: formatTime(item.updateTime),
      agentName: item.agentName || '客户经营智能体',
      messages: [],
      messagesLoaded: false,
    };
  }

  function toMessage(item: AiAgentMessageItem): MessageItem {
    const storedResponse = item.role === 'assistant' ? parseStoredAssistantResponse(item.evidenceJson) : undefined;
    const attachmentSnapshot = item.role === 'user' ? parseStoredAttachmentSnapshot(item.evidenceJson) : undefined;
    return {
      id: item.id,
      role: item.role,
      content: item.content || storedResponse?.answer || '',
      points: buildAssistantPoints(storedResponse),
      evidence: buildAssistantEvidence(storedResponse),
      attachments: attachmentSnapshot?.attachments,
      attachmentMode: attachmentSnapshot?.attachmentMode,
    };
  }

  function fileExtension(fileName: string) {
    return fileName.split('.').pop()?.toLocaleLowerCase() || '';
  }

  function isImageAttachment(fileType: string) {
    return ['jpg', 'jpeg', 'png', 'webp'].includes(fileType.toLocaleLowerCase());
  }

  function formatFileSize(size: number) {
    if (size < 1024 * 1024) {
      return `${Math.max(1, Math.round(size / 1024))} KB`;
    }
    return `${(size / 1024 / 1024).toFixed(1)} MB`;
  }

  function isKnowledgeIntent(question: string) {
    const text = question.replace(/\s+/g, '');
    if (knowledgeNegativePhrases.some((phrase) => text.includes(phrase)) || knowledgeNegativePattern.test(text)) {
      return false;
    }
    if (
      knowledgeQuestionPhrases.some((phrase) => text.includes(phrase)) ||
      knowledgeQuestionPattern.test(text) ||
      /[?？]/.test(text)
    ) {
      return false;
    }
    return knowledgeIntentPhrases.some((phrase) => text.includes(phrase)) || knowledgeIntentPattern.test(text);
  }

  function attachmentModeText(mode: AttachmentMode) {
    return mode === 'KNOWLEDGE' ? '发送后加入公司知识库' : '用于当前聊天';
  }

  const selectedAttachmentMode = computed<AttachmentMode>(() =>
    isKnowledgeIntent(questionInput.value) ? 'KNOWLEDGE' : 'CHAT'
  );
  const selectedAttachmentModeText = computed(() => attachmentModeText(selectedAttachmentMode.value));

  function openAttachmentPicker() {
    attachmentInputRef.value?.click();
  }

  function addAttachmentFiles(incomingFiles: File[]) {
    const nextFiles = [...selectedAttachmentFiles.value];
    let maxCountWarned = false;
    incomingFiles.forEach((file) => {
      if (nextFiles.length >= 5) {
        if (!maxCountWarned) {
          messageApi.warning('一次最多添加 5 个附件');
          maxCountWarned = true;
        }
        return;
      }
      const type = fileExtension(file.name);
      if (!allowedAttachmentTypes.has(type)) {
        messageApi.warning(`${file.name} 的格式不受支持`);
        return;
      }
      if (file.size > 20 * 1024 * 1024) {
        messageApi.warning(`${file.name} 超过 20MB`);
        return;
      }
      const duplicated = nextFiles.some(
        (item) => item.name === file.name && item.size === file.size && item.lastModified === file.lastModified
      );
      if (!duplicated) {
        nextFiles.push(file);
      }
    });
    selectedAttachmentFiles.value = nextFiles;
  }

  function handleAttachmentSelection(event: Event) {
    const input = event.target as HTMLInputElement;
    addAttachmentFiles(Array.from(input.files || []));
    input.value = '';
  }

  function handleComposerPaste(event: ClipboardEvent) {
    const clipboardFiles = Array.from(event.clipboardData?.files || []);
    if (!clipboardFiles.length) {
      return;
    }
    event.preventDefault();
    addAttachmentFiles(clipboardFiles);
  }

  function removeAttachment(index: number) {
    selectedAttachmentFiles.value.splice(index, 1);
  }

  function clearSelectedAttachments() {
    selectedAttachmentFiles.value = [];
    if (attachmentInputRef.value) {
      attachmentInputRef.value.value = '';
    }
  }

  function attachmentRequestError(error: unknown) {
    const requestError = error as {
      message?: string;
      response?: { data?: { message?: string } };
    };
    return requestError.response?.data?.message || requestError.message || '附件处理失败，请稍后重试';
  }

  function isDetailPoint(point: string) {
    return point.includes('\n');
  }

  function plainPoints(points: string[] = []) {
    return points.filter((point) => !isDetailPoint(point));
  }

  function detailPoints(points: string[] = []) {
    return points.filter(isDetailPoint);
  }

  function pointLines(point: string) {
    return point.split('\n').filter(Boolean);
  }

  function splitPointLine(line: string) {
    const separatorIndex = line.indexOf('：');
    if (separatorIndex <= 0) {
      return { label: '', value: line };
    }
    return {
      label: line.slice(0, separatorIndex),
      value: line.slice(separatorIndex + 1),
    };
  }

  function detailRows(points: string[] = []): DetailTableRow[] {
    return detailPoints(points)
      .map((point) => {
        const [title = '', ...fieldLines] = pointLines(point);
        const fields = fieldLines.reduce<Record<string, string>>((result, line) => {
          const { label, value } = splitPointLine(line);
          if (label) {
            result[label] = value || '-';
          }
          return result;
        }, {});
        return { title, fields };
      })
      .filter((row) => row.title || Object.keys(row.fields).length > 0);
  }

  function detailColumns(points: string[] = []) {
    const columns = ['序号'];
    detailRows(points).forEach((row) => {
      Object.keys(row.fields).forEach((label) => {
        if (!columns.includes(label)) {
          columns.push(label);
        }
      });
    });
    return columns;
  }

  function detailCell(row: DetailTableRow, column: string) {
    return column === '序号' ? row.title || '-' : row.fields[column] || '-';
  }

  async function loadMessages(sessionId: string) {
    try {
      const session = sessions.value.find((item) => item.id === sessionId);
      if (!session || session.messagesLoaded) {
        return;
      }
      const remoteMessages = await getAiAgentMessages(sessionId);
      session.messages = (remoteMessages || []).map(toMessage);
      session.question = session.messages.find((message) => message.role === 'user')?.content || session.question;
      session.messagesLoaded = true;
    } catch {
      // History loading is best-effort; asking new questions still works if chat API is available.
    }
  }

  async function loadSearchableMessages() {
    if (chatSearchLoading.value) {
      return;
    }
    const unloadedSessions = sessions.value.filter((session) => !session.messagesLoaded);
    if (!unloadedSessions.length) {
      return;
    }
    chatSearchLoading.value = true;
    try {
      await Promise.all(unloadedSessions.map((session) => loadMessages(session.id)));
    } finally {
      chatSearchLoading.value = false;
    }
  }

  function openChatSearch() {
    chatSearchOpen.value = true;
    nextTick(() => chatSearchInputRef.value?.focus());
    loadSearchableMessages();
  }

  function closeChatSearch() {
    chatSearchOpen.value = false;
    chatSearchQuery.value = '';
  }

  async function loadSessions() {
    try {
      const remoteSessions = await getAiAgentSessions();
      if (!remoteSessions?.length) {
        return;
      }
      sessions.value = remoteSessions.map(toSession);
      activeSessionId.value = sessions.value[0].id;
      await loadMessages(activeSessionId.value);
      if (chatSearchOpen.value) {
        await loadSearchableMessages();
      }
    } catch {
      // Keep local starter content when the backend is not ready yet.
    }
  }

  async function selectSession(sessionId: string) {
    clearSelectedAttachments();
    activeSessionId.value = sessionId;
    const session = currentSession.value;
    if (!session.messagesLoaded) {
      await loadMessages(sessionId);
    }
  }

  function openEvidence(item: EvidenceItem) {
    if (!item.routeName) {
      return;
    }
    router.push({
      name: item.routeName,
      query: item.query,
    });
  }

  function isLocalSession(sessionId: string) {
    return sessionId.startsWith('chat-') || sessionId === 'default';
  }

  function createChatRequestId() {
    if (typeof window.crypto?.randomUUID === 'function') {
      return window.crypto.randomUUID();
    }
    return `${Date.now()}-${Math.random().toString(36).slice(2)}`;
  }

  function isCanceledRequest(error: unknown, controller: AbortController) {
    const requestError = error as { code?: string; name?: string };
    return (
      controller.signal.aborted ||
      requestError.code === 'ERR_CANCELED' ||
      requestError.name === 'CanceledError' ||
      requestError.name === 'AbortError'
    );
  }

  async function stopCurrentRequest() {
    const controller = activeRequestController;
    const requestId = activeRequestId;
    if (!controller || controller.signal.aborted) {
      return;
    }
    const cancellationRequest = requestId ? cancelAiAgentChat(requestId) : null;
    controller.abort();
    if (cancellationRequest) {
      try {
        await cancellationRequest;
      } catch {
        // The local request is already stopped even if the server has gone away.
      }
    }
  }

  async function askQuestion(question: string) {
    const text = question.trim();
    if (!text) {
      return;
    }
    if (loading.value) {
      return;
    }
    const session = currentSession.value;
    const attachmentFiles = [...selectedAttachmentFiles.value];
    const attachmentMode = selectedAttachmentMode.value;
    if (session.messages.length === 0) {
      session.title = text.length > 14 ? `${text.slice(0, 14)}...` : text;
      session.question = text;
      session.time = '刚刚';
    }
    session.messages.push({
      id: Date.now(),
      role: 'user',
      content: text,
      attachments: attachmentFiles.map((file) => ({
        name: file.name,
        size: file.size,
        type: fileExtension(file.name),
      })),
      attachmentMode: attachmentFiles.length ? attachmentMode : undefined,
    });
    questionInput.value = '';
    loading.value = true;
    const requestController = new AbortController();
    const requestId = createChatRequestId();
    activeRequestController = requestController;
    activeRequestId = requestId;
    const llmProvider = selectedLlmProvider.value;
    const llmProviderLabel = selectedLlmProviderLabel.value;
    try {
      const request = {
        requestId,
        sessionId: isLocalSession(session.id) ? undefined : session.id,
        question: text,
        stream: false,
        timeRange: timeRange.value,
        dataScope: 'all',
        llmProvider,
        context: {
          pageModule: 'agent',
        },
      };
      const response = attachmentFiles.length
        ? await chatAiAgentWithAttachments(request, attachmentFiles, requestController.signal)
        : await chatAiAgent(request, requestController.signal);
      if (!response?.answer?.trim()) {
        throw new Error('智能体未返回可显示内容');
      }
      session.id = response.sessionId || session.id;
      activeSessionId.value = session.id;
      session.messages.push(toAssistantMessage(response, llmProviderLabel));
      clearSelectedAttachments();
    } catch (error) {
      if (isCanceledRequest(error, requestController)) {
        messageApi.info('已停止回答');
        return;
      }
      if (attachmentFiles.length) {
        const errorText = attachmentRequestError(error);
        messageApi.error(errorText);
        session.messages.push({
          id: `${Date.now()}-attachment-error`,
          role: 'assistant',
          content: `附件处理失败：${errorText}`,
          points: ['已选附件仍保留在输入区，可以调整问题后重新发送。'],
        });
      } else {
        session.messages.push(buildFallbackAnswer());
      }
    } finally {
      if (activeRequestController === requestController) {
        activeRequestController = null;
        activeRequestId = null;
        loading.value = false;
      }
    }
  }

  function createNewChat() {
    clearSelectedAttachments();
    const id = `chat-${Date.now()}`;
    sessions.value.unshift({
      id,
      title: '新聊天',
      question: '开始新的客户经营分析',
      owner: '当前用户',
      time: '刚刚',
      agentName: '客户经营智能体',
      messagesLoaded: true,
      messages: [],
    });
    activeSessionId.value = id;
    questionInput.value = '';
  }

  async function deleteSession(sessionId: string) {
    const index = sessions.value.findIndex((item) => item.id === sessionId);
    if (index < 0) {
      return;
    }

    const session = sessions.value[index];
    if (!isLocalSession(session.id)) {
      try {
        await deleteAiAgentSession(session.id);
      } catch {
        return;
      }
    }

    const wasActive = activeSessionId.value === sessionId;
    sessions.value.splice(index, 1);
    if (sessions.value.length === 0) {
      createNewChat();
      return;
    }
    if (wasActive) {
      clearSelectedAttachments();
      const nextSession = sessions.value[Math.min(index, sessions.value.length - 1)];
      activeSessionId.value = nextSession.id;
      if (!nextSession.messagesLoaded && !isLocalSession(nextSession.id)) {
        await loadMessages(nextSession.id);
      }
    }
  }

  function selectFeature(key: FeatureKey, question: string) {
    activeFeature.value = key;
    askQuestion(question);
  }

  onMounted(() => {
    loadSessions();
  });
</script>

<style lang="less" scoped>
  .crm-chat-shell {
    display: grid;
    overflow: hidden;
    height: 100%;
    min-height: 0;
    background: var(--text-n9);
    grid-template-columns: 316px minmax(0, 1fr);
  }
  .chat-sidebar {
    display: flex;
    min-height: 0;
    flex-direction: column;
    border-right: 1px solid var(--text-n8);
    background: var(--text-n10);
  }
  .sidebar-top {
    display: flex;
    flex: 0 0 auto;
    flex-direction: column;
    gap: 12px;
    padding: 12px;
    border-bottom: 1px solid var(--text-n8);
  }
  .sidebar-actions,
  .feature-list {
    display: flex;
    flex-direction: column;
    gap: 4px;
  }
  .sidebar-action,
  .feature-item,
  .history-item,
  .metric-card,
  .empty-prompt,
  .agent-picker {
    border: 0;
    background: transparent;
    cursor: pointer;
  }
  .sidebar-action,
  .feature-item {
    display: flex;
    align-items: center;
    padding: 0 10px;
    height: 36px;
    font-size: 14px;
    border-radius: 8px;
    color: var(--text-n1);
    gap: 10px;
  }
  .sidebar-search {
    display: grid;
    align-items: center;
    padding: 0 8px 0 10px;
    height: 36px;
    border: 1px solid var(--primary-5);
    border-radius: 8px;
    background: var(--text-n10);
    grid-template-columns: 18px minmax(0, 1fr) 28px;
    gap: 8px;
  }
  .sidebar-search__icon {
    color: var(--text-n3);
  }
  .sidebar-search__input {
    padding: 0;
    width: 100%;
    min-width: 0;
    height: 34px;
    font-size: 14px;
    border: 0;
    color: var(--text-n1);
    background: transparent;
    outline: 0;
  }
  .sidebar-search__input::placeholder {
    color: var(--text-n4);
  }
  .sidebar-search__input::-webkit-search-cancel-button {
    display: none;
  }
  .sidebar-search__close {
    display: flex;
    justify-content: center;
    align-items: center;
    padding: 0;
    width: 28px;
    height: 28px;
    border: 0;
    border-radius: 6px;
    color: var(--text-n3);
    background: transparent;
    cursor: pointer;
  }
  .sidebar-search__close:hover {
    color: var(--text-n1);
    background: var(--text-n9);
  }
  .sidebar-action:hover,
  .feature-item:hover,
  .feature-item--active {
    background: var(--text-n9);
  }
  .history-panel {
    display: flex;
    padding: 12px;
    min-height: 0;
    flex: 1;
    flex-direction: column;
    gap: 10px;
  }
  .history-title {
    font-size: 13px;
    color: var(--text-n3);
    line-height: 20px;
  }
  .history-scroll {
    min-height: 0;
    flex: 1;
  }
  .history-row {
    display: grid;
    align-items: center;
    width: 100%;
    min-height: 34px;
    border-radius: 8px;
    grid-template-columns: minmax(0, 1fr) 28px;
    gap: 2px;
  }
  .history-row:hover,
  .history-row--active {
    background: var(--text-n9);
  }
  .history-row:hover .history-delete,
  .history-row--active .history-delete {
    opacity: 1;
  }
  .history-item {
    display: grid;
    align-items: center;
    padding: 0 0 0 10px;
    width: 100%;
    min-width: 0;
    min-height: 34px;
    text-align: left;
    grid-template-columns: minmax(0, 1fr) auto;
    gap: 10px;
  }
  .history-delete {
    opacity: 0;
    transition: opacity 0.15s ease;
  }
  .history-item__title {
    overflow: hidden;
    font-size: 14px;
    text-overflow: ellipsis;
    white-space: nowrap;
    color: var(--text-n1);
    line-height: 34px;
  }
  .history-item__time {
    font-size: 13px;
    color: var(--text-n3);
    flex: 0 0 auto;
    line-height: 34px;
  }
  .history-more {
    padding: 4px 10px 0;
    width: 100%;
    font-size: 13px;
    border: 0;
    text-align: left;
    color: var(--text-n3);
    background: transparent;
    line-height: 22px;
    cursor: pointer;
  }
  .history-more:hover {
    color: var(--primary-8);
  }
  .history-empty {
    padding: 28px 10px;
    font-size: 13px;
    text-align: center;
    color: var(--text-n3);
    line-height: 20px;
  }
  .sidebar-user {
    display: flex;
    flex: 0 0 auto;
    align-items: center;
    gap: 10px;
    margin: 8px 12px 12px;
    padding: 10px;
    border-radius: 8px;
    background: var(--text-n9);
  }
  .user-avatar {
    display: flex;
    justify-content: center;
    align-items: center;
    width: 38px;
    height: 38px;
    font-weight: 600;
    border-radius: 50%;
    color: var(--text-n10);
    background: var(--primary-8);
  }
  .user-name {
    font-size: 14px;
    color: var(--text-n1);
    line-height: 20px;
  }
  .user-plan {
    font-size: 12px;
    color: var(--text-n3);
    line-height: 18px;
  }
  .chat-main {
    display: flex;
    min-width: 0;
    min-height: 0;
    flex-direction: column;
    background: var(--text-n10);
  }
  .chat-header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    padding: 0 28px;
    height: 58px;
    border-bottom: 1px solid var(--text-n8);
    flex: 0 0 auto;
    gap: 16px;
  }
  .agent-picker {
    display: flex;
    align-items: center;
    font-size: 18px;
    font-weight: 600;
    color: var(--text-n1);
    gap: 4px;
  }
  .chat-header__tools {
    display: flex;
    align-items: center;
    gap: 8px;
  }
  .header-select {
    width: 128px;
  }
  .chat-scroll {
    min-height: 0;
    flex: 1;
  }
  .chat-space {
    padding: 28px;
    min-height: calc(100vh - 250px);
  }
  .empty-state {
    display: flex;
    justify-content: center;
    align-items: center;
    min-height: 430px;
    text-align: center;
    flex-direction: column;
  }
  .empty-title {
    font-size: 30px;
    font-weight: 600;
    color: var(--text-n1);
    line-height: 42px;
  }
  .empty-subtitle {
    margin-top: 8px;
    font-size: 14px;
    color: var(--text-n3);
    line-height: 22px;
  }
  .empty-prompts {
    display: grid;
    margin-top: 28px;
    width: min(720px, 100%);
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 10px;
  }
  .empty-prompt {
    padding: 0 14px;
    min-height: 44px;
    border: 1px solid var(--text-n8);
    border-radius: 8px;
    text-align: left;
    color: var(--text-n1);
  }
  .empty-prompt:hover {
    border-color: var(--primary-5);
    background: var(--primary-1);
  }
  .message-list {
    display: flex;
    margin: 0 auto;
    width: min(920px, 100%);
    flex-direction: column;
    gap: 22px;
  }
  .message-row {
    display: flex;
    align-items: flex-start;
    gap: 12px;
  }
  .message-row--user {
    justify-content: flex-end;
  }
  .message-row--assistant {
    justify-content: flex-start;
  }
  .message-avatar {
    display: flex;
    justify-content: center;
    align-items: center;
    width: 34px;
    height: 34px;
    font-size: 13px;
    font-weight: 600;
    border-radius: 8px;
    color: var(--primary-8);
    background: var(--primary-1);
  }
  .message-content {
    padding-top: 4px;
    min-width: 0;
  }
  .message-row--assistant .message-content {
    max-width: calc(100% - 46px);
  }
  .message-row--user .message-content {
    padding: 12px 16px;
    max-width: min(710px, 78%);
    border-radius: 18px;
    color: var(--text-n1);
    background: var(--text-n9);
  }
  .message-name {
    font-size: 12px;
    color: var(--text-n3);
    line-height: 18px;
  }
  .message-text,
  .message-points {
    font-size: 14px;
    color: var(--text-n1);
    line-height: 24px;
  }
  .message-text {
    margin-top: 4px;
  }
  .message-row--user .message-text {
    margin-top: 0;
    font-size: 15px;
    line-height: 26px;
  }
  .message-attachments {
    display: flex;
    flex-wrap: wrap;
    gap: 6px;
    margin-bottom: 8px;
  }
  .message-attachment {
    display: flex;
    align-items: center;
    padding: 4px 8px;
    max-width: 260px;
    border: 1px solid var(--text-n7);
    border-radius: 6px;
    color: var(--text-n2);
    background: var(--text-n10);
    gap: 6px;
  }
  .message-attachment span {
    overflow: hidden;
    font-size: 12px;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .message-attachment-mode {
    align-self: center;
    font-size: 12px;
    color: var(--text-n3);
  }
  .message-points {
    display: flex;
    margin: 8px 0 0;
    max-width: 100%;
    flex-direction: column;
    gap: 8px;
  }
  .message-point-list {
    display: flex;
    margin: 0;
    padding-left: 18px;
    max-width: 100%;
    flex-direction: column;
    gap: 6px;
  }
  .message-point {
    min-width: 0;
    overflow-wrap: anywhere;
  }
  .message-table-wrap {
    width: min(100%, 980px);
    min-width: 0;
  }
  .message-table-scroll {
    overflow-x: auto;
    width: 100%;
    border: 1px solid var(--text-n8);
    border-radius: 8px;
    background: var(--text-n10);
  }
  .message-table-scroll::-webkit-scrollbar {
    height: 8px;
  }
  .message-table-scroll::-webkit-scrollbar-thumb {
    border-radius: 999px;
    background: var(--text-n6);
  }
  .message-detail-table {
    min-width: max-content;
    border-collapse: collapse;
    font-size: 13px;
    line-height: 22px;
  }
  .message-detail-table th,
  .message-detail-table td {
    padding: 7px 12px;
    min-width: 116px;
    border-right: 1px solid var(--text-n8);
    border-bottom: 1px solid var(--text-n8);
    text-align: left;
    white-space: nowrap;
  }
  .message-detail-table th {
    font-weight: 500;
    color: var(--text-n3);
    background: var(--text-n9);
  }
  .message-detail-table td {
    color: var(--text-n1);
  }
  .message-detail-table th:first-child,
  .message-detail-table td:first-child {
    position: sticky;
    left: 0;
    z-index: 1;
    min-width: 96px;
    background: var(--text-n10);
  }
  .message-detail-table th:first-child {
    z-index: 2;
    background: var(--text-n9);
  }
  .message-detail-table tr:last-child td {
    border-bottom: 0;
  }
  .message-detail-table th:last-child,
  .message-detail-table td:last-child {
    border-right: 0;
  }
  .message-detail-table td span {
    display: block;
  }
  .evidence-actions {
    display: flex;
    flex-wrap: wrap;
    gap: 6px;
    margin-top: 10px;
  }
  .evidence-button {
    border-radius: 6px;
  }
  .composer-wrap {
    flex: 0 0 auto;
    padding: 12px 28px 24px;
  }
  .context-line {
    display: flex;
    margin: 0 auto 8px;
    width: min(960px, 100%);
    font-size: 12px;
    color: var(--text-n3);
    flex-wrap: wrap;
    gap: 10px;
  }
  .attachment-tray {
    margin: 0 auto 8px;
    width: min(960px, 100%);
  }
  .attachment-tray__summary {
    display: flex;
    justify-content: space-between;
    align-items: center;
    margin-bottom: 6px;
    font-size: 12px;
    color: var(--text-n3);
    line-height: 18px;
  }
  .attachment-tray__list {
    display: flex;
    overflow-x: auto;
    padding-bottom: 2px;
    gap: 6px;
  }
  .attachment-chip {
    display: grid;
    align-items: center;
    padding: 5px 5px 5px 8px;
    min-width: 0;
    max-width: 310px;
    height: 36px;
    border: 1px solid var(--text-n8);
    border-radius: 7px;
    color: var(--text-n2);
    background: var(--text-n10);
    grid-template-columns: 16px minmax(72px, 1fr) auto 26px;
    gap: 6px;
  }
  .attachment-chip__name {
    overflow: hidden;
    font-size: 13px;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .attachment-chip__size {
    font-size: 11px;
    white-space: nowrap;
    color: var(--text-n4);
  }
  .attachment-chip__remove {
    display: flex;
    justify-content: center;
    align-items: center;
    padding: 0;
    width: 26px;
    height: 26px;
    border: 0;
    border-radius: 5px;
    color: var(--text-n3);
    background: transparent;
    cursor: pointer;
  }
  .attachment-chip__remove:hover {
    color: var(--text-n1);
    background: var(--text-n9);
  }
  .attachment-input {
    position: absolute;
    overflow: hidden;
    width: 1px;
    height: 1px;
    clip: rect(0 0 0 0);
    clip-path: inset(50%);
    white-space: nowrap;
  }
  .composer {
    display: flex;
    align-items: flex-end;
    margin: 0 auto;
    padding: 8px;
    width: min(960px, 100%);
    min-height: 56px;
    border: 1px solid var(--text-n8);
    border-radius: 28px;
    background: var(--text-n10);
    box-shadow: 0 8px 24px rgb(0 0 0 / 8%);
    gap: 8px;
  }
  .composer-input {
    flex: 1;
  }
  .composer-input :deep(.n-input) {
    --n-border: 0 !important;
    --n-border-hover: 0 !important;
    --n-border-focus: 0 !important;
    --n-box-shadow-focus: none !important;
  }
  .send-button {
    flex: 0 0 auto;
  }

  @media (max-width: 1200px) {
    .crm-chat-shell {
      grid-template-columns: 292px minmax(0, 1fr);
    }
    .empty-prompts {
      grid-template-columns: 1fr;
    }
  }
</style>
