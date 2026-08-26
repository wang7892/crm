<template>
  <CrmPageWrapper :title="t('agent.title')" :back-route-name="WorkbenchRouteEnum.WORKBENCH_INDEX">
    <div class="agent-chat-page">
      <header class="agent-toolbar">
        <button type="button" class="agent-toolbar__button" @click="openHistory">
          <van-icon name="bars" />
          <span>{{ t('agent.history') }}</span>
        </button>
        <div class="agent-toolbar__filters">
          <button type="button" class="agent-filter-button" @click="showModelSheet = true">
            <span>{{ selectedLlmProviderLabel }}</span>
            <van-icon name="arrow-down" />
          </button>
          <button type="button" class="agent-filter-button agent-filter-button--time" @click="showTimeSheet = true">
            <van-icon name="clock-o" />
            <span>{{ timeRangeLabel }}</span>
          </button>
        </div>
        <button
          type="button"
          class="agent-toolbar__icon-button"
          :aria-label="t('agent.newChat')"
          :title="t('agent.newChat')"
          @click="createNewChat"
        >
          <van-icon name="plus" />
        </button>
      </header>

      <main ref="messageScrollRef" class="agent-messages">
        <section v-if="currentSession.messages.length === 0" class="agent-welcome">
          <div class="agent-welcome__icon">
            <CrmIcon name="iconicon_bot" width="32px" height="32px" color="var(--primary-8)" />
          </div>
          <h1>{{ t('agent.welcomeTitle') }}</h1>
          <p>{{ t('agent.welcomeSubtitle') }}</p>
          <div class="agent-quick-prompts">
            <button v-for="prompt in quickPrompts" :key="prompt" type="button" @click="askQuestion(prompt)">
              {{ prompt }}
              <van-icon name="arrow" />
            </button>
          </div>
        </section>

        <div v-else class="agent-message-list">
          <article
            v-for="message in currentSession.messages"
            :key="message.id"
            class="agent-message"
            :class="`agent-message--${message.role}`"
          >
            <div v-if="message.role === 'assistant'" class="agent-message__avatar">
              <CrmIcon name="iconicon_bot" width="18px" height="18px" color="var(--primary-8)" />
            </div>
            <div class="agent-message__body">
              <div v-if="message.role === 'assistant'" class="agent-message__name">
                {{ message.llmProviderLabel || selectedLlmProviderLabel }}
              </div>
              <div v-if="message.attachments?.length" class="agent-message__attachments">
                <div v-for="attachment in message.attachments" :key="`${message.id}-${attachment.name}`">
                  <van-icon :name="isImageAttachment(attachment.type) ? 'photo-o' : 'description-o'" />
                  <span>{{ attachment.name }}</span>
                </div>
                <small v-if="message.attachmentMode">{{ attachmentModeText(message.attachmentMode) }}</small>
              </div>
              <div class="agent-message__bubble">
                <p>{{ message.content }}</p>

                <ul v-if="plainPoints(message.points).length" class="agent-message__points">
                  <li v-for="point in plainPoints(message.points)" :key="point">{{ point }}</li>
                </ul>

                <div v-if="detailRows(message.points).length" class="agent-detail-table-wrap">
                  <table class="agent-detail-table">
                    <thead>
                      <tr>
                        <th v-for="column in detailColumns(message.points)" :key="column">{{ column }}</th>
                      </tr>
                    </thead>
                    <tbody>
                      <tr v-for="(row, rowIndex) in detailRows(message.points)" :key="row.title || rowIndex">
                        <td v-for="column in detailColumns(message.points)" :key="column">
                          {{ detailCell(row, column) }}
                        </td>
                      </tr>
                    </tbody>
                  </table>
                </div>
              </div>

              <div v-if="message.evidence?.length" class="agent-evidence-list">
                <button
                  v-for="item in message.evidence"
                  :key="item.key"
                  type="button"
                  :disabled="!item.routeName"
                  @click="openEvidence(item)"
                >
                  <van-icon name="link-o" />
                  <span>{{ item.label }}</span>
                </button>
              </div>
            </div>
          </article>

          <article v-if="loading" class="agent-message agent-message--assistant">
            <div class="agent-message__avatar">
              <CrmIcon name="iconicon_bot" width="18px" height="18px" color="var(--primary-8)" />
            </div>
            <div class="agent-message__body">
              <div class="agent-message__name">{{ selectedLlmProviderLabel }}</div>
              <div class="agent-message__bubble agent-message__bubble--thinking">
                <van-loading size="16" />
                <span>{{ t('agent.thinking') }}</span>
              </div>
            </div>
          </article>
        </div>
      </main>

      <section v-if="selectedAttachmentFiles.length" class="agent-attachment-tray">
        <div class="agent-attachment-tray__heading">
          <span>{{ selectedAttachmentModeText }}</span>
          <small>{{ selectedAttachmentFiles.length }}/5</small>
        </div>
        <div class="agent-attachment-tray__list">
          <div v-for="(file, index) in selectedAttachmentFiles" :key="fileKey(file)" class="agent-attachment-chip">
            <van-icon :name="isImageAttachment(fileExtension(file.name)) ? 'photo-o' : 'description-o'" />
            <span>{{ file.name }}</span>
            <small>{{ formatFileSize(file.size) }}</small>
            <button
              type="button"
              :aria-label="t('agent.removeAttachment', { name: file.name })"
              @click="removeAttachment(index)"
            >
              <van-icon name="cross" />
            </button>
          </div>
        </div>
      </section>

      <footer class="agent-composer-shell">
        <div class="agent-context-line">
          <span>{{ timeRangeLabel }}</span>
          <span>{{ t('agent.dataScope') }}</span>
        </div>
        <div class="agent-composer">
          <input
            ref="attachmentInputRef"
            class="agent-file-input"
            type="file"
            multiple
            accept=".jpg,.jpeg,.png,.webp,.pdf,.docx,.xls,.xlsx,.txt"
            @change="handleAttachmentSelection"
          />
          <button
            type="button"
            class="agent-composer__action"
            :disabled="loading || selectedAttachmentFiles.length >= 5"
            :aria-label="t('agent.addAttachment')"
            :title="t('agent.addAttachment')"
            @click="openAttachmentPicker"
          >
            <van-icon name="plus" />
          </button>
          <van-field
            v-model="questionInput"
            type="textarea"
            rows="1"
            autosize
            maxlength="4000"
            :placeholder="t('agent.inputPlaceholder')"
            class="agent-composer__input"
            @paste="handleComposerPaste"
            @keydown.enter.exact.prevent="askQuestion(questionInput)"
          />
          <button
            v-if="loading"
            type="button"
            class="agent-composer__send agent-composer__send--stop"
            :aria-label="t('agent.stop')"
            :title="t('agent.stop')"
            @click="stopCurrentRequest"
          >
            <van-icon name="pause-circle-o" />
          </button>
          <button
            v-else
            type="button"
            class="agent-composer__send"
            :disabled="!questionInput.trim()"
            :aria-label="t('agent.send')"
            :title="t('agent.send')"
            @click="askQuestion(questionInput)"
          >
            <van-icon name="guide-o" />
          </button>
        </div>
      </footer>
    </div>

    <van-popup v-model:show="showHistory" position="left" class="agent-history-popup">
      <div class="agent-history">
        <header class="agent-history__header">
          <h2>{{ t('agent.history') }}</h2>
          <button type="button" @click="createNewChat">
            <van-icon name="plus" />
            <span>{{ t('agent.newChat') }}</span>
          </button>
        </header>
        <van-search v-model="chatSearchQuery" :placeholder="t('agent.searchHistory')" shape="round" />
        <div class="agent-history__list">
          <div
            v-for="session in filteredSessions"
            :key="session.id"
            class="agent-history__item"
            :class="{ active: activeSessionId === session.id }"
          >
            <button type="button" class="agent-history__select" @click="selectSession(session.id)">
              <strong>{{ session.title }}</strong>
              <span>{{ session.time }}</span>
            </button>
            <button
              type="button"
              class="agent-history__delete"
              :aria-label="t('agent.deleteChat')"
              :title="t('agent.deleteChat')"
              @click="confirmDeleteSession(session)"
            >
              <van-icon name="delete-o" />
            </button>
          </div>
          <van-empty
            v-if="filteredSessions.length === 0"
            image-size="72"
            :description="chatSearchLoading ? t('agent.searching') : t('agent.noHistory')"
          />
        </div>
      </div>
    </van-popup>

    <van-action-sheet
      v-model:show="showModelSheet"
      :title="t('agent.selectModel')"
      :actions="llmProviderActions"
      :cancel-text="t('common.cancel')"
      close-on-click-action
      @select="selectLlmProvider"
    />
    <van-action-sheet
      v-model:show="showTimeSheet"
      :title="t('agent.selectTimeRange')"
      :actions="timeRangeActions"
      :cancel-text="t('common.cancel')"
      close-on-click-action
      @select="selectTimeRange"
    />
  </CrmPageWrapper>
</template>

<script setup lang="ts">
  import { useRouter } from 'vue-router';
  import { showConfirmDialog, showFailToast, showSuccessToast, showToast } from 'vant';

  import type {
    AiAgentChatResult,
    AiAgentCitation,
    AiAgentMessageItem,
    AiAgentSessionItem,
  } from '@lib/shared/api/modules/aiAgent';
  import { useI18n } from '@lib/shared/hooks/useI18n';

  import CrmIcon from '@/components/pure/crm-icon-font/index.vue';
  import CrmPageWrapper from '@/components/pure/crm-page-wrapper/index.vue';

  import {
    cancelAiAgentChat,
    chatAiAgent,
    chatAiAgentWithAttachments,
    deleteAiAgentSession,
    getAiAgentMessages,
    getAiAgentSessions,
  } from '@/api/modules';

  import { AgentRouteEnum, CustomerRouteEnum, WorkbenchRouteEnum } from '@/enums/routeEnum';

  type MessageRole = 'assistant' | 'user';
  type AttachmentMode = 'CHAT' | 'KNOWLEDGE';

  interface SheetAction {
    name: string;
    value: string;
    color?: string;
  }

  interface EvidenceItem {
    key: string;
    label: string;
    routeName?: string;
    query?: Record<string, string>;
  }

  interface AttachmentItem {
    name: string;
    size: number;
    type: string;
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

  interface ChatSession {
    id: string;
    title: string;
    question: string;
    time: string;
    messages: MessageItem[];
    messagesLoaded: boolean;
  }

  interface DetailTableRow {
    title: string;
    fields: Record<string, string>;
  }

  const MAX_ATTACHMENT_COUNT = 5;
  const MAX_ATTACHMENT_SIZE = 20 * 1024 * 1024;
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

  defineOptions({
    name: AgentRouteEnum.AGENT_CHAT,
  });

  const router = useRouter();
  const { t } = useI18n();
  const messageScrollRef = ref<HTMLElement>();
  const attachmentInputRef = ref<HTMLInputElement>();
  const showHistory = ref(false);
  const showModelSheet = ref(false);
  const showTimeSheet = ref(false);
  const chatSearchQuery = ref('');
  const chatSearchLoading = ref(false);
  const selectedAttachmentFiles = ref<File[]>([]);
  const questionInput = ref('');
  const timeRange = ref('30d');
  const selectedLlmProvider = ref('primary');
  const loading = ref(false);
  let activeRequestController: AbortController | null = null;
  let activeRequestId: string | null = null;

  const llmProviderOptions = [
    { label: 'GPT-5.6 Sol', value: 'primary' },
    { label: 'qwen', value: 'qwen' },
    { label: 'deepseek', value: 'deepseek' },
  ];

  const timeRangeOptions = computed(() => [
    { label: t('agent.timeRange.7d'), value: '7d' },
    { label: t('agent.timeRange.30d'), value: '30d' },
    { label: t('agent.timeRange.quarter'), value: 'quarter' },
    { label: t('agent.timeRange.year'), value: 'year' },
  ]);

  const quickPrompts = computed(() => [
    t('agent.prompt.communication'),
    t('agent.prompt.newOrders'),
    t('agent.prompt.customerOrders'),
    t('agent.prompt.activeOrders'),
    t('agent.prompt.permission'),
  ]);

  function createEmptySession(id = `chat-${Date.now()}`): ChatSession {
    return {
      id,
      title: t('agent.newChat'),
      question: '',
      time: t('agent.justNow'),
      messages: [],
      messagesLoaded: true,
    };
  }

  const sessions = ref<ChatSession[]>([createEmptySession()]);
  const activeSessionId = ref(sessions.value[0].id);

  const currentSession = computed(
    () => sessions.value.find((item) => item.id === activeSessionId.value) || sessions.value[0]
  );
  const selectedLlmProviderLabel = computed(
    () =>
      llmProviderOptions.find((item) => item.value === selectedLlmProvider.value)?.label || llmProviderOptions[0].label
  );
  const timeRangeLabel = computed(
    () => timeRangeOptions.value.find((item) => item.value === timeRange.value)?.label || t('agent.timeRange.30d')
  );
  const llmProviderActions = computed<SheetAction[]>(() =>
    llmProviderOptions.map((item) => ({
      name: item.label,
      value: item.value,
      color: selectedLlmProvider.value === item.value ? 'var(--primary-8)' : undefined,
    }))
  );
  const timeRangeActions = computed<SheetAction[]>(() =>
    timeRangeOptions.value.map((item) => ({
      name: item.label,
      value: item.value,
      color: timeRange.value === item.value ? 'var(--primary-8)' : undefined,
    }))
  );
  const filteredSessions = computed(() => {
    const keyword = chatSearchQuery.value.trim().toLocaleLowerCase();
    if (!keyword) return sessions.value;
    return sessions.value.filter(
      (session) =>
        session.title.toLocaleLowerCase().includes(keyword) ||
        session.question.toLocaleLowerCase().includes(keyword) ||
        session.messages.some(
          (message) => message.role === 'user' && message.content.toLocaleLowerCase().includes(keyword)
        )
    );
  });

  const selectedAttachmentMode = computed<AttachmentMode>(() =>
    isKnowledgeIntent(questionInput.value) ? 'KNOWLEDGE' : 'CHAT'
  );
  const selectedAttachmentModeText = computed(() => attachmentModeText(selectedAttachmentMode.value));

  function selectLlmProvider(action: SheetAction) {
    selectedLlmProvider.value = action.value;
  }

  function selectTimeRange(action: SheetAction) {
    timeRange.value = action.value;
  }

  function formatTime(value?: number) {
    if (!value) return t('agent.justNow');
    const diff = Date.now() - value;
    if (diff < 60_000) return t('agent.justNow');
    if (diff < 3_600_000) return t('agent.minutesAgo', { count: Math.floor(diff / 60_000) });
    if (diff < 86_400_000) return t('agent.hoursAgo', { count: Math.floor(diff / 3_600_000) });
    return t('agent.daysAgo', { count: Math.floor(diff / 86_400_000) });
  }

  function fileExtension(fileName: string) {
    return fileName.split('.').pop()?.toLocaleLowerCase() || '';
  }

  function isImageAttachment(fileType: string) {
    return ['jpg', 'jpeg', 'png', 'webp'].includes(fileType.toLocaleLowerCase());
  }

  function formatFileSize(size: number) {
    if (size < 1024 * 1024) return `${Math.max(1, Math.round(size / 1024))} KB`;
    return `${(size / 1024 / 1024).toFixed(1)} MB`;
  }

  function fileKey(file: File) {
    return `${file.name}-${file.size}-${file.lastModified}`;
  }

  function isKnowledgeIntent(question: string) {
    const text = question.replace(/\s+/g, '');
    const normalized = question.toLocaleLowerCase();
    if (
      knowledgeNegativePhrases.some((phrase) => text.includes(phrase)) ||
      knowledgeNegativePattern.test(text) ||
      /(?:do not|don't|dont|never).{0,20}(?:knowledge base|company knowledge)/i.test(normalized)
    ) {
      return false;
    }
    if (
      knowledgeQuestionPhrases.some((phrase) => text.includes(phrase)) ||
      knowledgeQuestionPattern.test(text) ||
      /[?？]/.test(text)
    ) {
      return false;
    }
    return (
      knowledgeIntentPhrases.some((phrase) => text.includes(phrase)) ||
      knowledgeIntentPattern.test(text) ||
      /(?:add|save|upload|store).{0,24}(?:knowledge base|company knowledge)/i.test(normalized)
    );
  }

  function attachmentModeText(mode: AttachmentMode) {
    return t(mode === 'KNOWLEDGE' ? 'agent.attachmentMode.knowledge' : 'agent.attachmentMode.chat');
  }

  function openAttachmentPicker() {
    attachmentInputRef.value?.click();
  }

  function addAttachmentFiles(incomingFiles: File[]) {
    const nextFiles = [...selectedAttachmentFiles.value];
    let maxCountWarned = false;
    incomingFiles.forEach((file) => {
      if (nextFiles.length >= MAX_ATTACHMENT_COUNT) {
        if (!maxCountWarned) {
          showFailToast(t('agent.attachmentTooMany'));
          maxCountWarned = true;
        }
        return;
      }
      const type = fileExtension(file.name);
      if (!allowedAttachmentTypes.has(type)) {
        showFailToast(t('agent.attachmentUnsupported', { name: file.name }));
        return;
      }
      if (file.size > MAX_ATTACHMENT_SIZE) {
        showFailToast(t('agent.attachmentTooLarge', { name: file.name }));
        return;
      }
      const duplicated = nextFiles.some(
        (item) => item.name === file.name && item.size === file.size && item.lastModified === file.lastModified
      );
      if (!duplicated) nextFiles.push(file);
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
    if (!clipboardFiles.length) return;
    event.preventDefault();
    addAttachmentFiles(clipboardFiles);
  }

  function removeAttachment(index: number) {
    selectedAttachmentFiles.value.splice(index, 1);
  }

  function clearSelectedAttachments() {
    selectedAttachmentFiles.value = [];
    if (attachmentInputRef.value) attachmentInputRef.value.value = '';
  }

  function attachmentRequestError(error: unknown) {
    if (typeof error === 'string') return error;
    const requestError = error as { message?: string; response?: { data?: { message?: string } } };
    return requestError.response?.data?.message || requestError.message || t('agent.attachmentFailed');
  }

  function isDetailPoint(point: string) {
    return point.includes('\n');
  }

  function plainPoints(points: string[] = []) {
    return points.filter((point) => !isDetailPoint(point));
  }

  function detailRows(points: string[] = []): DetailTableRow[] {
    return points
      .filter(isDetailPoint)
      .map((point) => {
        const [title = '', ...fieldLines] = point.split('\n').filter(Boolean);
        const fields = fieldLines.reduce<Record<string, string>>((result, line) => {
          const separatorIndex = line.indexOf('：');
          if (separatorIndex > 0) result[line.slice(0, separatorIndex)] = line.slice(separatorIndex + 1) || '-';
          return result;
        }, {});
        return { title, fields };
      })
      .filter((row) => row.title || Object.keys(row.fields).length > 0);
  }

  function detailColumns(points: string[] = []) {
    const columns = [t('agent.sequence')];
    detailRows(points).forEach((row) => {
      Object.keys(row.fields).forEach((label) => {
        if (!columns.includes(label)) columns.push(label);
      });
    });
    return columns;
  }

  function detailCell(row: DetailTableRow, column: string) {
    return column === t('agent.sequence') ? row.title || '-' : row.fields[column] || '-';
  }

  const evidenceLabelKeyMap: Record<string, string> = {
    'customer': 'agent.evidence.customer',
    'email_webhook_event': 'agent.evidence.email',
    'follow_up_record': 'agent.evidence.followRecord',
    'contract_info': 'agent.evidence.contract',
    'mls_agent_data.contract_info': 'agent.evidence.contract',
  };
  const hiddenEvidenceSources = new Set(['wecom_ingestion_message', 'email_webhook_event']);

  function findCitationForEvidence(source: string, citations: AiAgentCitation[]) {
    return citations.find((item) => item.module === source || item.type === source || item.title === source);
  }

  function buildEvidenceItem(source: string, citation?: AiAgentCitation): EvidenceItem {
    const recordIds = citation?.recordIds?.filter(Boolean) || [];
    const isCustomer = source === 'customer' || citation?.module === 'customer';
    const query: Record<string, string> = { source: 'aiAgent', evidence: source };
    if (recordIds.length === 1 && isCustomer) query.id = recordIds[0];
    if (recordIds.length) query.recordIds = recordIds.join(',');
    return {
      key: source,
      label: evidenceLabelKeyMap[source] ? t(evidenceLabelKeyMap[source]) : source,
      routeName: isCustomer ? CustomerRouteEnum.CUSTOMER_INDEX : undefined,
      query,
    };
  }

  function buildEvidenceItems(sources: string[], citations: AiAgentCitation[] = []) {
    const seen = new Set<string>();
    return sources
      .map((source) => source.trim())
      .filter((source) => {
        if (!source || hiddenEvidenceSources.has(source) || seen.has(source)) return false;
        seen.add(source);
        return true;
      })
      .map((source) => buildEvidenceItem(source, findCitationForEvidence(source, citations)));
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
    if (!response) return [];
    return [
      ...toStringArray(response.points),
      ...toStringArray(response.warnings)
        .filter(shouldRenderWarning)
        .map((item) => t('agent.warning', { message: item })),
    ];
  }

  function buildAssistantEvidence(response?: Partial<AiAgentChatResult>) {
    if (!response) return [];
    const citations = Array.isArray(response.citations) ? response.citations : [];
    const evidence = buildEvidenceItems(toStringArray(response.evidence), citations);
    if (evidence.length) return evidence;
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
    if (!evidenceJson) return undefined;
    try {
      const snapshot = JSON.parse(evidenceJson) as
        | ({ response?: Partial<AiAgentChatResult> } & Partial<AiAgentChatResult>)
        | null;
      return snapshot && typeof snapshot === 'object' ? snapshot.response || snapshot : undefined;
    } catch {
      return undefined;
    }
  }

  function parseStoredAttachmentSnapshot(evidenceJson?: string) {
    if (!evidenceJson) return undefined;
    try {
      const snapshot = JSON.parse(evidenceJson) as {
        attachmentMode?: AttachmentMode;
        attachments?: Array<Partial<AttachmentItem>>;
      };
      const attachments = Array.isArray(snapshot.attachments)
        ? snapshot.attachments.filter(
            (item): item is AttachmentItem =>
              typeof item.name === 'string' && typeof item.size === 'number' && typeof item.type === 'string'
          )
        : [];
      if (!attachments.length) return undefined;
      return {
        attachments,
        attachmentMode: snapshot.attachmentMode === 'KNOWLEDGE' ? 'KNOWLEDGE' : 'CHAT',
      } as const;
    } catch {
      return undefined;
    }
  }

  function toSession(item: AiAgentSessionItem): ChatSession {
    return {
      id: item.id,
      title: item.title || t('agent.newChat'),
      question: item.title || '',
      time: formatTime(item.updateTime),
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

  async function scrollToBottom() {
    await nextTick();
    const element = messageScrollRef.value;
    if (element) element.scrollTop = element.scrollHeight;
  }

  async function loadMessages(sessionId: string) {
    const session = sessions.value.find((item) => item.id === sessionId);
    if (!session || session.messagesLoaded) return;
    try {
      const remoteMessages = await getAiAgentMessages(sessionId);
      session.messages = (remoteMessages || []).map(toMessage);
      session.question = session.messages.find((message) => message.role === 'user')?.content || session.question;
      session.messagesLoaded = true;
      if (activeSessionId.value === sessionId) await scrollToBottom();
    } catch {
      session.messagesLoaded = true;
    }
  }

  async function loadSearchableMessages() {
    if (chatSearchLoading.value) return;
    const unloadedSessions = sessions.value.filter((session) => !session.messagesLoaded);
    if (!unloadedSessions.length) return;
    chatSearchLoading.value = true;
    try {
      await Promise.all(unloadedSessions.map((session) => loadMessages(session.id)));
    } finally {
      chatSearchLoading.value = false;
    }
  }

  async function loadSessions() {
    try {
      const remoteSessions = await getAiAgentSessions();
      if (!remoteSessions?.length) return;
      sessions.value = remoteSessions.map(toSession);
      activeSessionId.value = sessions.value[0].id;
      await loadMessages(activeSessionId.value);
    } catch {
      // The local blank session remains usable if history is temporarily unavailable.
    }
  }

  function openHistory() {
    showHistory.value = true;
    loadSearchableMessages();
  }

  async function selectSession(sessionId: string) {
    clearSelectedAttachments();
    activeSessionId.value = sessionId;
    showHistory.value = false;
    await loadMessages(sessionId);
    await scrollToBottom();
  }

  function isLocalSession(sessionId: string) {
    return sessionId.startsWith('chat-');
  }

  function createNewChat() {
    if (loading.value) {
      showToast(t('agent.finishCurrentAnswer'));
      return;
    }
    clearSelectedAttachments();
    const session = createEmptySession();
    sessions.value.unshift(session);
    activeSessionId.value = session.id;
    questionInput.value = '';
    chatSearchQuery.value = '';
    showHistory.value = false;
  }

  async function confirmDeleteSession(session: ChatSession) {
    if (loading.value && activeSessionId.value === session.id) {
      showToast(t('agent.finishCurrentAnswer'));
      return;
    }
    try {
      await showConfirmDialog({
        title: t('agent.deleteChat'),
        message: t('agent.deleteChatConfirm', { title: session.title }),
      });
    } catch {
      return;
    }
    await deleteSession(session.id);
  }

  async function deleteSession(sessionId: string) {
    const index = sessions.value.findIndex((item) => item.id === sessionId);
    if (index < 0) return;
    const session = sessions.value[index];
    if (!isLocalSession(session.id)) {
      try {
        await deleteAiAgentSession(session.id);
      } catch {
        showFailToast(t('agent.deleteFailed'));
        return;
      }
    }
    const wasActive = activeSessionId.value === sessionId;
    sessions.value.splice(index, 1);
    if (!sessions.value.length) {
      const nextSession = createEmptySession();
      sessions.value.push(nextSession);
      activeSessionId.value = nextSession.id;
    } else if (wasActive) {
      const nextSession = sessions.value[Math.min(index, sessions.value.length - 1)];
      activeSessionId.value = nextSession.id;
      await loadMessages(nextSession.id);
    }
    showSuccessToast(t('agent.deleted'));
  }

  function openEvidence(item: EvidenceItem) {
    if (!item.routeName) return;
    router.push({ name: item.routeName, query: item.query });
  }

  function createChatRequestId() {
    if (typeof window.crypto?.randomUUID === 'function') return window.crypto.randomUUID();
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
    if (!controller || controller.signal.aborted) return;
    const cancellationRequest = requestId ? cancelAiAgentChat(requestId) : null;
    controller.abort();
    if (cancellationRequest) {
      try {
        await cancellationRequest;
      } catch {
        // The local request has already stopped.
      }
    }
  }

  function buildFallbackAnswer(): MessageItem {
    return {
      id: `${Date.now()}-error`,
      role: 'assistant',
      content: t('agent.requestFailed'),
      points: [t('agent.requestFailedTip')],
    };
  }

  async function askQuestion(question: string) {
    const text = question.trim();
    if (!text || loading.value) return;
    const session = currentSession.value;
    const attachmentFiles = [...selectedAttachmentFiles.value];
    const attachmentMode = selectedAttachmentMode.value;
    if (!session.messages.length) {
      session.title = text.length > 14 ? `${text.slice(0, 14)}...` : text;
      session.question = text;
      session.time = t('agent.justNow');
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
    await scrollToBottom();

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
        context: { pageModule: 'agent', client: 'mobile' },
      };
      const response = attachmentFiles.length
        ? await chatAiAgentWithAttachments(request, attachmentFiles, requestController.signal)
        : await chatAiAgent(request, requestController.signal);
      if (!response?.answer?.trim()) throw new Error('EMPTY_AGENT_RESPONSE');
      session.id = response.sessionId || session.id;
      activeSessionId.value = session.id;
      session.messages.push(toAssistantMessage(response, llmProviderLabel));
      clearSelectedAttachments();
    } catch (error) {
      if (isCanceledRequest(error, requestController)) {
        showToast(t('agent.stopped'));
        return;
      }
      if (attachmentFiles.length) {
        const errorText = attachmentRequestError(error);
        showFailToast(errorText);
        session.messages.push({
          id: `${Date.now()}-attachment-error`,
          role: 'assistant',
          content: t('agent.attachmentErrorMessage', { message: errorText }),
          points: [t('agent.attachmentRetryTip')],
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
      await scrollToBottom();
    }
  }

  watch(chatSearchQuery, (value) => {
    if (value.trim()) loadSearchableMessages();
  });

  onMounted(() => {
    loadSessions();
  });

  onBeforeUnmount(() => {
    const requestId = activeRequestId;
    activeRequestController?.abort();
    if (requestId) cancelAiAgentChat(requestId).catch(() => undefined);
  });
</script>

<style lang="less" scoped>
  :deep(.crm-page-content) {
    overflow: hidden !important;
  }
  .agent-chat-page {
    display: flex;
    overflow: hidden;
    height: 100%;
    min-height: 0;
    background: var(--text-n9);
    flex-direction: column;
  }
  .agent-toolbar {
    display: grid;
    align-items: center;
    padding: 8px 12px;
    min-height: 52px;
    border-bottom: 1px solid var(--text-n8);
    background: var(--text-n10);
    grid-template-columns: auto minmax(0, 1fr) 40px;
    gap: 8px;
    flex-shrink: 0;
  }
  .agent-toolbar__button,
  .agent-toolbar__icon-button,
  .agent-filter-button {
    display: flex;
    justify-content: center;
    align-items: center;
    padding: 0 10px;
    height: 36px;
    font-size: 13px;
    border: 1px solid var(--text-n7);
    border-radius: 6px;
    white-space: nowrap;
    color: var(--text-n2);
    background: var(--text-n10);
    gap: 5px;
  }
  .agent-toolbar__icon-button {
    padding: 0;
    width: 40px;
    height: 40px;
    font-size: 19px;
    color: var(--primary-8);
  }
  .agent-toolbar__filters {
    display: flex;
    overflow: hidden;
    min-width: 0;
    gap: 6px;
  }
  .agent-filter-button {
    overflow: hidden;
    min-width: 0;
    max-width: 132px;
  }
  .agent-filter-button span {
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .agent-filter-button--time {
    max-width: 100px;
  }
  .agent-messages {
    overflow-y: auto;
    padding: 18px 12px 24px;
    min-height: 0;
    overscroll-behavior: contain;
    flex: 1;
    -webkit-overflow-scrolling: touch;
  }
  .agent-welcome {
    display: flex;
    align-items: center;
    margin: 7vh auto 0;
    max-width: 520px;
    text-align: center;
    flex-direction: column;
  }
  .agent-welcome__icon {
    display: flex;
    justify-content: center;
    align-items: center;
    width: 60px;
    height: 60px;
    border: 1px solid var(--primary-6);
    border-radius: 8px;
    background: var(--primary-7);
  }
  .agent-welcome h1 {
    margin: 16px 0 0;
    font-size: 20px;
    font-weight: 600;
    line-height: 28px;
    color: var(--text-n1);
  }
  .agent-welcome > p {
    margin: 8px 10px 0;
    font-size: 13px;
    line-height: 21px;
    color: var(--text-n4);
  }
  .agent-quick-prompts {
    display: flex;
    margin-top: 22px;
    width: 100%;
    flex-direction: column;
    gap: 8px;
  }
  .agent-quick-prompts button {
    display: flex;
    justify-content: space-between;
    align-items: center;
    padding: 11px 12px;
    min-height: 44px;
    font-size: 13px;
    border: 1px solid var(--text-n8);
    border-radius: 6px;
    text-align: left;
    color: var(--text-n2);
    background: var(--text-n10);
    gap: 10px;
  }
  .agent-message-list {
    display: flex;
    margin: 0 auto;
    width: 100%;
    max-width: 760px;
    flex-direction: column;
    gap: 18px;
  }
  .agent-message {
    display: flex;
    align-items: flex-start;
    min-width: 0;
    gap: 8px;
  }
  .agent-message--user {
    justify-content: flex-end;
  }
  .agent-message__avatar {
    display: flex;
    justify-content: center;
    align-items: center;
    width: 34px;
    height: 34px;
    border: 1px solid var(--primary-6);
    border-radius: 6px;
    background: var(--primary-7);
    flex-shrink: 0;
  }
  .agent-message__body {
    min-width: 0;
    max-width: calc(100% - 42px);
  }
  .agent-message--user .agent-message__body {
    max-width: 86%;
  }
  .agent-message__name {
    margin-bottom: 5px;
    font-size: 12px;
    line-height: 18px;
    color: var(--text-n4);
  }
  .agent-message__bubble {
    padding: 11px 12px;
    font-size: 14px;
    border: 1px solid var(--text-n8);
    border-radius: 7px;
    color: var(--text-n1);
    background: var(--text-n10);
    line-height: 22px;
    overflow-wrap: anywhere;
  }
  .agent-message--user .agent-message__bubble {
    border-color: var(--primary-6);
    background: var(--primary-7);
  }
  .agent-message__bubble p {
    margin: 0;
    white-space: pre-wrap;
  }
  .agent-message__bubble--thinking {
    display: flex;
    align-items: center;
    color: var(--text-n3);
    gap: 8px;
  }
  .agent-message__points {
    margin: 10px 0 0;
    padding: 10px 0 0 18px;
    border-top: 1px solid var(--text-n8);
  }
  .agent-message__points li + li {
    margin-top: 5px;
  }
  .agent-message__attachments {
    display: flex;
    margin-bottom: 6px;
    flex-direction: column;
    gap: 5px;
  }
  .agent-message__attachments > div {
    display: flex;
    align-items: center;
    padding: 6px 8px;
    min-width: 0;
    font-size: 12px;
    border: 1px solid var(--text-n8);
    border-radius: 5px;
    color: var(--text-n3);
    background: var(--text-n10);
    gap: 6px;
  }
  .agent-message__attachments > div span {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .agent-message__attachments small {
    font-size: 11px;
    color: var(--text-n4);
  }
  .agent-evidence-list {
    display: flex;
    margin-top: 7px;
    flex-wrap: wrap;
    gap: 6px;
  }
  .agent-evidence-list button {
    display: flex;
    align-items: center;
    padding: 0 9px;
    min-height: 32px;
    font-size: 12px;
    border: 1px solid var(--primary-6);
    border-radius: 5px;
    color: var(--primary-8);
    background: var(--primary-7);
    gap: 4px;
  }
  .agent-evidence-list button:disabled {
    border-color: var(--text-n8);
    color: var(--text-n4);
    background: var(--text-n9);
  }
  .agent-detail-table-wrap {
    overflow-x: auto;
    margin-top: 10px;
    width: 100%;
    border: 1px solid var(--text-n8);
    border-radius: 5px;
  }
  .agent-detail-table {
    min-width: 420px;
    font-size: 12px;
    border-collapse: collapse;
    background: var(--text-n10);
  }
  .agent-detail-table th,
  .agent-detail-table td {
    padding: 8px;
    border-right: 1px solid var(--text-n8);
    border-bottom: 1px solid var(--text-n8);
    text-align: left;
    white-space: nowrap;
  }
  .agent-detail-table th {
    color: var(--text-n3);
    background: var(--text-n9);
  }
  .agent-attachment-tray {
    padding: 8px 12px 0;
    border-top: 1px solid var(--text-n8);
    background: var(--text-n10);
    flex-shrink: 0;
  }
  .agent-attachment-tray__heading {
    display: flex;
    justify-content: space-between;
    align-items: center;
    margin-bottom: 6px;
    font-size: 11px;
    color: var(--text-n4);
  }
  .agent-attachment-tray__list {
    display: flex;
    overflow-x: auto;
    gap: 6px;
  }
  .agent-attachment-chip {
    display: flex;
    align-items: center;
    padding: 6px 7px;
    min-width: 190px;
    max-width: 260px;
    font-size: 12px;
    border: 1px solid var(--text-n8);
    border-radius: 5px;
    color: var(--text-n3);
    background: var(--text-n9);
    gap: 6px;
  }
  .agent-attachment-chip > span {
    overflow: hidden;
    min-width: 0;
    text-overflow: ellipsis;
    white-space: nowrap;
    flex: 1;
  }
  .agent-attachment-chip small {
    white-space: nowrap;
    color: var(--text-n4);
  }
  .agent-attachment-chip button {
    padding: 4px;
    border: 0;
    color: var(--text-n3);
    background: transparent;
  }
  .agent-composer-shell {
    padding: 6px 10px max(8px, env(safe-area-inset-bottom));
    border-top: 1px solid var(--text-n8);
    background: var(--text-n10);
    flex-shrink: 0;
  }
  .agent-context-line {
    display: flex;
    overflow: hidden;
    margin-bottom: 5px;
    padding: 0 4px;
    font-size: 10px;
    text-overflow: ellipsis;
    white-space: nowrap;
    color: var(--text-n4);
    gap: 10px;
  }
  .agent-composer {
    display: flex;
    align-items: flex-end;
    padding: 4px;
    min-height: 46px;
    border: 1px solid var(--text-n7);
    border-radius: 7px;
    background: var(--text-n10);
    gap: 3px;
  }
  .agent-composer:focus-within {
    border-color: var(--primary-8);
  }
  .agent-file-input {
    position: absolute;
    width: 1px;
    height: 1px;
    opacity: 0;
  }
  .agent-composer__action,
  .agent-composer__send {
    display: flex;
    justify-content: center;
    align-items: center;
    width: 38px;
    min-width: 38px;
    height: 38px;
    font-size: 20px;
    border: 0;
    border-radius: 6px;
    color: var(--text-n3);
    background: transparent;
  }
  .agent-composer__action:disabled,
  .agent-composer__send:disabled {
    opacity: 0.4;
  }
  .agent-composer__send {
    color: #ffffff;
    background: var(--primary-8);
  }
  .agent-composer__send--stop {
    background: var(--error-1);
  }
  .agent-composer__input {
    padding: 6px 4px;
    min-width: 0;
    background: transparent;
    flex: 1;
  }
  .agent-composer__input :deep(.van-field__control) {
    overflow-y: auto;
    max-height: 92px;
    font-size: 14px;
    line-height: 21px;
  }
  :global(.agent-history-popup) {
    width: min(86vw, 360px);
    height: 100%;
  }
  .agent-history {
    display: flex;
    overflow: hidden;
    height: 100%;
    background: var(--text-n10);
    flex-direction: column;
  }
  .agent-history__header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    padding: 0 14px;
    height: 54px;
    border-bottom: 1px solid var(--text-n8);
    flex-shrink: 0;
  }
  .agent-history__header h2 {
    margin: 0;
    font-size: 17px;
    font-weight: 600;
    color: var(--text-n1);
  }
  .agent-history__header button {
    display: flex;
    align-items: center;
    padding: 0 8px;
    height: 36px;
    font-size: 13px;
    border: 0;
    border-radius: 5px;
    color: var(--primary-8);
    background: var(--primary-7);
    gap: 4px;
  }
  .agent-history__list {
    overflow-y: auto;
    padding: 4px 10px 18px;
    min-height: 0;
    flex: 1;
  }
  .agent-history__item {
    display: flex;
    align-items: center;
    margin-top: 5px;
    padding: 4px 4px 4px 10px;
    min-height: 54px;
    border-radius: 6px;
    gap: 4px;
  }
  .agent-history__item.active {
    background: var(--primary-7);
  }
  .agent-history__select {
    display: flex;
    overflow: hidden;
    padding: 5px 0;
    min-width: 0;
    border: 0;
    text-align: left;
    background: transparent;
    flex-direction: column;
    gap: 3px;
    flex: 1;
  }
  .agent-history__select strong {
    overflow: hidden;
    width: 100%;
    font-size: 14px;
    font-weight: 500;
    text-overflow: ellipsis;
    white-space: nowrap;
    color: var(--text-n1);
  }
  .agent-history__select span {
    font-size: 11px;
    color: var(--text-n4);
  }
  .agent-history__delete {
    display: flex;
    justify-content: center;
    align-items: center;
    width: 38px;
    min-width: 38px;
    height: 38px;
    font-size: 17px;
    border: 0;
    border-radius: 5px;
    color: var(--text-n4);
    background: transparent;
  }
</style>
