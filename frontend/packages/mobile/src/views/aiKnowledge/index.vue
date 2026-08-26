<template>
  <CrmPageWrapper :title="t('aiKnowledge.title')" :back-route-name="WorkbenchRouteEnum.WORKBENCH_INDEX">
    <main class="knowledge-page">
      <section class="knowledge-summary">
        <div>
          <h1>{{ t('aiKnowledge.title') }}</h1>
          <p>{{ t('aiKnowledge.description') }}</p>
        </div>
        <button type="button" class="primary-command" @click="openUploadPopup">
          <van-icon name="plus" />
          <span>{{ t('aiKnowledge.uploadDocument') }}</span>
        </button>
      </section>

      <section class="knowledge-section document-section">
        <div class="document-search-row">
          <van-search
            v-model="query.keyword"
            shape="round"
            clearable
            :placeholder="t('aiKnowledge.searchPlaceholder')"
            @search="applyDocumentFilters"
            @clear="applyDocumentFilters"
          />
          <button type="button" class="search-command" @click="applyDocumentFilters">
            {{ t('aiKnowledge.search') }}
          </button>
        </div>

        <div class="filter-strip">
          <button type="button" @click="showFileTypeSheet = true">
            <span>{{ selectedFileTypeLabel }}</span>
            <van-icon name="arrow-down" />
          </button>
          <button type="button" @click="showParseStatusSheet = true">
            <span>{{ selectedParseStatusLabel }}</span>
            <van-icon name="arrow-down" />
          </button>
          <button type="button" @click="showEnabledSheet = true">
            <span>{{ selectedEnabledLabel }}</span>
            <van-icon name="arrow-down" />
          </button>
        </div>

        <div v-if="documentLoading && documentRows.length === 0" class="document-loading">
          <van-skeleton v-for="index in 3" :key="index" title :row="3" />
        </div>
        <van-empty v-else-if="documentRows.length === 0" :description="t('aiKnowledge.noDocuments')" />

        <div v-else class="document-list">
          <article v-for="document in documentRows" :key="document.id" class="document-card">
            <header class="document-card__header">
              <div class="document-file-icon">
                <van-icon name="description-o" />
              </div>
              <div class="document-title">
                <strong>{{ document.name }}</strong>
                <span>{{ displayFileType(document.fileType) }} · {{ formatFileSize(document.fileSize) }}</span>
              </div>
            </header>

            <div class="document-stats">
              <div>
                <span>{{ t('aiKnowledge.parseStatus') }}</span>
                <strong class="status-text" :class="`status-text--${parseStatusTone(document.parseStatus)}`">
                  {{ parseStatusText(document) }}
                </strong>
              </div>
              <div>
                <span>{{ t('aiKnowledge.knowledgeChunks') }}</span>
                <strong>{{ knowledgeChunkCount(document) }} {{ t('aiKnowledge.items') }}</strong>
              </div>
              <div>
                <span>{{ t('aiKnowledge.effectiveStatus') }}</span>
                <strong
                  class="status-text"
                  :class="`status-text--${semanticStatusTone(semanticDocumentStatus(document))}`"
                >
                  {{ semanticStatusText(semanticDocumentStatus(document)) }}
                </strong>
              </div>
            </div>

            <p v-if="document.remark" class="document-remark">{{ document.remark }}</p>

            <footer class="document-actions">
              <button type="button" @click="openChunks(document)">
                <van-icon name="eye-o" />
                <span>{{ t('aiKnowledge.viewKnowledge') }}</span>
              </button>
              <button type="button" :disabled="reparsingDocumentId === document.id" @click="reparse(document)">
                <van-loading v-if="reparsingDocumentId === document.id" size="14" />
                <van-icon v-else name="replay" />
                <span>{{ t('aiKnowledge.reparse') }}</span>
              </button>
              <button type="button" @click="downloadDocument(document)">
                <van-icon name="down" />
                <span>{{ t('aiKnowledge.download') }}</span>
              </button>
              <button v-if="canDelete" type="button" class="danger-action" @click="confirmDelete(document)">
                <van-icon name="delete-o" />
                <span>{{ t('common.delete') }}</span>
              </button>
            </footer>
          </article>
        </div>

        <div v-if="documentTotal > 0" class="pagination-row">
          <van-pagination
            v-model="query.current"
            :total-items="documentTotal"
            :items-per-page="query.pageSize"
            :show-page-size="3"
            force-ellipses
            @change="loadDocuments"
          />
          <button type="button" class="page-size-button" @click="showDocumentPageSizeSheet = true">
            {{ query.pageSize }} / {{ t('aiKnowledge.page') }}
            <van-icon name="arrow-down" />
          </button>
        </div>
      </section>

      <section class="knowledge-section search-test-section">
        <header class="section-heading">
          <h2>{{ t('aiKnowledge.searchTest') }}</h2>
          <div class="test-mode-switch" role="group" :aria-label="t('aiKnowledge.searchMode')">
            <button
              v-for="mode in testModeOptions"
              :key="mode.value"
              type="button"
              :class="{ active: testMode === mode.value }"
              @click="selectTestMode(mode.value)"
            >
              {{ mode.label }}
            </button>
          </div>
        </header>

        <van-field
          v-model="testQuestion"
          type="textarea"
          rows="3"
          autosize
          maxlength="1000"
          show-word-limit
          :placeholder="t('aiKnowledge.testPlaceholder')"
          class="test-question"
        />
        <button type="button" class="test-command" :disabled="testLoading" @click="runSearchTest">
          <van-loading v-if="testLoading" size="16" />
          <van-icon v-else name="search" />
          <span>{{ t('aiKnowledge.runTest') }}</span>
        </button>

        <div v-if="testResult" class="test-results">
          <div class="result-alert result-alert--info">
            {{ testResult.answerPreview || t('aiKnowledge.testCompleted') }}
          </div>
          <div v-if="testResult.fallbackReason" class="result-alert result-alert--warning">
            {{ testResult.fallbackReason }}
          </div>

          <section v-if="testResult.matchedRules?.length" class="result-section">
            <div class="result-section__heading">
              <h3>{{ t('aiKnowledge.matchedRules') }}</h3>
              <span>{{ testResult.retrievalMode || 'SEMANTIC_EXACT' }}</span>
            </div>
            <article v-for="rule in testResult.matchedRules" :key="`${rule.ruleId}-${rule.version}`" class="match-card">
              <strong>{{ rule.term }}</strong>
              <div class="match-meta">
                <span>{{ matchedByText(rule.matchedBy) }}</span>
                <span>{{ t('aiKnowledge.relevance', { score: formatScore(rule.score) }) }}</span>
              </div>
              <p>
                {{ rule.target.entity }}.{{ rule.target.field }}
                <template v-if="rule.documentName"> · {{ rule.documentName }}</template>
                <template v-if="rule.pageNo"> · {{ t('aiKnowledge.pageNumber', { page: rule.pageNo }) }}</template>
                <template v-if="rule.sectionPath"> · {{ rule.sectionPath }}</template>
              </p>
            </article>
          </section>

          <section v-if="testResult.injectedContextPreview" class="result-section">
            <h3>{{ t('aiKnowledge.controlledContext') }}</h3>
            <pre>{{ formatInjectedContext(testResult.injectedContextPreview) }}</pre>
          </section>

          <van-empty
            v-if="testMode !== 'DOCUMENT' && !testResult.matchedRules?.length && !testResult.matches?.length"
            image-size="72"
            :description="t('aiKnowledge.noMatch')"
          />

          <section v-if="testResult.matches?.length" class="result-section">
            <h3>{{ t('aiKnowledge.documentMatches') }}</h3>
            <article v-for="match in testResult.matches" :key="match.chunkId" class="match-card">
              <strong>{{ match.documentName || t('aiKnowledge.unnamedDocument') }}</strong>
              <div class="match-meta">
                <span v-if="match.pageNo">{{ t('aiKnowledge.pageNumber', { page: match.pageNo }) }}</span>
                <span>{{ t('aiKnowledge.relevance', { score: formatScore(match.score) }) }}</span>
              </div>
              <p>{{ match.content }}</p>
            </article>
          </section>
        </div>
      </section>
    </main>

    <van-popup v-model:show="uploadPopupVisible" position="bottom" round closeable class="knowledge-popup">
      <div class="popup-content upload-popup">
        <h2>{{ t('aiKnowledge.uploadKnowledgeDocument') }}</h2>
        <label class="field-label" for="knowledge-remark">{{ t('aiKnowledge.remark') }}</label>
        <van-field
          id="knowledge-remark"
          v-model="uploadRemark"
          type="textarea"
          rows="2"
          autosize
          maxlength="500"
          :placeholder="t('aiKnowledge.remarkPlaceholder')"
          class="popup-field"
        />
        <input
          ref="uploadInputRef"
          type="file"
          class="hidden-file-input"
          accept=".jpg,.jpeg,.png,.webp,.pdf,.docx,.xls,.xlsx,.txt,.md"
          @change="handleUploadSelection"
        />
        <button type="button" class="file-picker" :disabled="uploading" @click="openUploadFilePicker">
          <van-icon :name="selectedUploadFile ? 'description-o' : 'plus'" />
          <span>{{ selectedUploadFile?.name || t('aiKnowledge.chooseFile') }}</span>
          <small v-if="selectedUploadFile">{{ formatFileSize(selectedUploadFile.size) }}</small>
        </button>
        <p class="upload-helper">{{ t('aiKnowledge.uploadHelper') }}</p>
        <van-progress v-if="uploading" :percentage="uploadProgress" stroke-width="6" />
        <button
          type="button"
          class="popup-primary-button"
          :disabled="!selectedUploadFile || uploading"
          @click="submitUpload"
        >
          <van-loading v-if="uploading" size="16" />
          <van-icon v-else name="upgrade" />
          <span>{{ uploading ? t('aiKnowledge.uploading') : t('aiKnowledge.upload') }}</span>
        </button>
      </div>
    </van-popup>

    <van-popup v-model:show="chunkPopupVisible" position="right" class="chunk-popup">
      <div class="chunk-page">
        <header class="chunk-page__header">
          <button type="button" :aria-label="t('common.back')" @click="chunkPopupVisible = false">
            <van-icon name="arrow-left" />
          </button>
          <div>
            <h2>{{ t('aiKnowledge.extractedKnowledge') }}</h2>
            <span>{{ currentDocument?.name }}</span>
          </div>
        </header>
        <div class="chunk-list-wrap">
          <div v-if="chunkLoading && chunkRows.length === 0" class="document-loading">
            <van-skeleton v-for="index in 3" :key="index" title :row="3" />
          </div>
          <van-empty v-else-if="chunkRows.length === 0" :description="t('aiKnowledge.noChunks')" />
          <div v-else class="chunk-list">
            <article v-for="chunk in chunkRows" :key="chunk.id" class="chunk-card">
              <header>
                <strong>{{ chunk.title || t('aiKnowledge.chunkTitle', { index: chunk.chunkIndex }) }}</strong>
                <span v-if="chunk.pageNo">{{ t('aiKnowledge.pageNumber', { page: chunk.pageNo }) }}</span>
              </header>
              <p>{{ chunk.content }}</p>
            </article>
          </div>
        </div>
        <footer v-if="chunkTotal > 0" class="chunk-pagination">
          <van-pagination
            v-model="chunkQuery.current"
            :total-items="chunkTotal"
            :items-per-page="chunkQuery.pageSize"
            :show-page-size="3"
            force-ellipses
            @change="loadChunks"
          />
          <button type="button" class="page-size-button" @click="showChunkPageSizeSheet = true">
            {{ chunkQuery.pageSize }} / {{ t('aiKnowledge.page') }}
            <van-icon name="arrow-down" />
          </button>
        </footer>
      </div>
    </van-popup>

    <van-action-sheet
      v-model:show="showFileTypeSheet"
      :title="t('aiKnowledge.fileType')"
      :actions="fileTypeActions"
      :cancel-text="t('common.cancel')"
      close-on-click-action
      @select="selectFileType"
    />
    <van-action-sheet
      v-model:show="showParseStatusSheet"
      :title="t('aiKnowledge.parseStatus')"
      :actions="parseStatusActions"
      :cancel-text="t('common.cancel')"
      close-on-click-action
      @select="selectParseStatus"
    />
    <van-action-sheet
      v-model:show="showEnabledSheet"
      :title="t('aiKnowledge.effectiveStatus')"
      :actions="enabledActions"
      :cancel-text="t('common.cancel')"
      close-on-click-action
      @select="selectEnabled"
    />
    <van-action-sheet
      v-model:show="showDocumentPageSizeSheet"
      :title="t('aiKnowledge.pageSize')"
      :actions="documentPageSizeActions"
      :cancel-text="t('common.cancel')"
      close-on-click-action
      @select="selectDocumentPageSize"
    />
    <van-action-sheet
      v-model:show="showChunkPageSizeSheet"
      :title="t('aiKnowledge.pageSize')"
      :actions="chunkPageSizeActions"
      :cancel-text="t('common.cancel')"
      close-on-click-action
      @select="selectChunkPageSize"
    />
  </CrmPageWrapper>
</template>

<script setup lang="ts">
  import { showConfirmDialog, showFailToast, showSuccessToast, showToast } from 'vant';

  import type {
    AiKnowledgeChunkItem,
    AiKnowledgeDocumentItem,
    AiKnowledgeSearchMode,
    AiKnowledgeSearchTestResult,
    AiSemanticInjectedContext,
  } from '@lib/shared/api/modules/aiAgent';
  import { useI18n } from '@lib/shared/hooks/useI18n';

  import CrmPageWrapper from '@/components/pure/crm-page-wrapper/index.vue';

  import {
    deleteAiKnowledgeDocument,
    getAiKnowledgeChunkPage,
    getAiKnowledgeDocumentDetail,
    getAiKnowledgeDocumentDownloadUrl,
    getAiKnowledgeDocumentPage,
    reparseAiKnowledgeDocument,
    testAiKnowledgeSearch,
    uploadAiKnowledgeDocument,
  } from '@/api/modules';
  import { hasAnyPermission } from '@/utils/permission';

  import { AiKnowledgeRouteEnum, WorkbenchRouteEnum } from '@/enums/routeEnum';

  interface SheetAction {
    name: string;
    value: string;
    color?: string;
  }

  const MAX_UPLOAD_SIZE = 50 * 1024 * 1024;
  const allowedExtensions = new Set(['jpg', 'jpeg', 'png', 'webp', 'pdf', 'docx', 'xls', 'xlsx', 'txt', 'md']);

  defineOptions({
    name: AiKnowledgeRouteEnum.AI_KNOWLEDGE_INDEX,
  });

  const { t } = useI18n();
  const query = reactive({
    current: 1,
    pageSize: 20,
    keyword: '',
    fileType: null as string | null,
    parseStatus: null as string | null,
    enabled: null as number | null,
  });
  const documentRows = ref<AiKnowledgeDocumentItem[]>([]);
  const documentLoading = ref(false);
  const documentTotal = ref(0);
  const reparsingDocumentId = ref<string | null>(null);

  const showFileTypeSheet = ref(false);
  const showParseStatusSheet = ref(false);
  const showEnabledSheet = ref(false);
  const showDocumentPageSizeSheet = ref(false);
  const showChunkPageSizeSheet = ref(false);

  const uploadPopupVisible = ref(false);
  const uploadInputRef = ref<HTMLInputElement>();
  const selectedUploadFile = ref<File | null>(null);
  const uploadRemark = ref('');
  const uploading = ref(false);
  const uploadProgress = ref(0);

  const chunkPopupVisible = ref(false);
  const currentDocument = ref<AiKnowledgeDocumentItem | null>(null);
  const chunkRows = ref<AiKnowledgeChunkItem[]>([]);
  const chunkLoading = ref(false);
  const chunkTotal = ref(0);
  const chunkQuery = reactive({ current: 1, pageSize: 10 });

  const testQuestion = ref('');
  const testMode = ref<AiKnowledgeSearchMode>('AUTO');
  const testLoading = ref(false);
  const testResult = ref<AiKnowledgeSearchTestResult | null>(null);

  const documentPollTimers = new Map<string, ReturnType<typeof setTimeout>>();
  const documentPollFailures = new Map<string, number>();
  const pollingDocumentIds = new Set<string>();
  let pageUnmounted = false;

  const canDelete = computed(() => hasAnyPermission(['AGENT:DELETE']));
  const fileTypeOptions = computed(() => [
    { label: t('aiKnowledge.allFileTypes'), value: '' },
    { label: 'JPG / JPEG', value: 'jpg' },
    { label: 'PNG', value: 'png' },
    { label: 'WebP', value: 'webp' },
    { label: 'PDF', value: 'pdf' },
    { label: 'Word', value: 'docx' },
    { label: 'Excel 97-2003', value: 'xls' },
    { label: 'Excel', value: 'xlsx' },
    { label: 'TXT', value: 'txt' },
    { label: 'Markdown', value: 'md' },
  ]);
  const parseStatusOptions = computed(() => [
    { label: t('aiKnowledge.allParseStatuses'), value: '' },
    { label: t('aiKnowledge.status.uploaded'), value: 'UPLOADED' },
    { label: t('aiKnowledge.status.parsing'), value: 'PARSING' },
    { label: t('aiKnowledge.status.parsed'), value: 'PARSED' },
    { label: t('aiKnowledge.status.failed'), value: 'FAILED' },
  ]);
  const enabledOptions = computed(() => [
    { label: t('aiKnowledge.allEffectiveStatuses'), value: '' },
    { label: t('aiKnowledge.status.active'), value: '1' },
    { label: t('aiKnowledge.status.inactive'), value: '0' },
  ]);
  const testModeOptions = computed<Array<{ label: string; value: AiKnowledgeSearchMode }>>(() => [
    { label: t('aiKnowledge.mode.auto'), value: 'AUTO' },
    { label: t('aiKnowledge.mode.semantic'), value: 'SEMANTIC_RULE' },
    { label: t('aiKnowledge.mode.document'), value: 'DOCUMENT' },
  ]);
  const fileTypeActions = computed<SheetAction[]>(() =>
    fileTypeOptions.value.map((item) => actionWithSelection(item, query.fileType || ''))
  );
  const parseStatusActions = computed<SheetAction[]>(() =>
    parseStatusOptions.value.map((item) => actionWithSelection(item, query.parseStatus || ''))
  );
  const enabledActions = computed<SheetAction[]>(() =>
    enabledOptions.value.map((item) => actionWithSelection(item, query.enabled === null ? '' : String(query.enabled)))
  );
  const documentPageSizeActions = computed<SheetAction[]>(() =>
    [10, 20, 50, 100].map((value) =>
      actionWithSelection(
        { label: `${value} / ${t('aiKnowledge.page')}`, value: String(value) },
        String(query.pageSize)
      )
    )
  );
  const chunkPageSizeActions = computed<SheetAction[]>(() =>
    [10, 20, 50].map((value) =>
      actionWithSelection(
        { label: `${value} / ${t('aiKnowledge.page')}`, value: String(value) },
        String(chunkQuery.pageSize)
      )
    )
  );
  const selectedFileTypeLabel = computed(
    () =>
      fileTypeOptions.value.find((item) => item.value === (query.fileType || ''))?.label || t('aiKnowledge.fileType')
  );
  const selectedParseStatusLabel = computed(
    () =>
      parseStatusOptions.value.find((item) => item.value === (query.parseStatus || ''))?.label ||
      t('aiKnowledge.parseStatus')
  );
  const selectedEnabledLabel = computed(
    () =>
      enabledOptions.value.find((item) => item.value === (query.enabled === null ? '' : String(query.enabled)))
        ?.label || t('aiKnowledge.effectiveStatus')
  );

  function actionWithSelection(item: { label: string; value: string }, selectedValue: string): SheetAction {
    return {
      name: item.label,
      value: item.value,
      color: item.value === selectedValue ? 'var(--primary-8)' : undefined,
    };
  }

  async function loadDocuments() {
    documentLoading.value = true;
    try {
      const result = await getAiKnowledgeDocumentPage({
        current: query.current,
        pageSize: query.pageSize,
        keyword: query.keyword.trim() || undefined,
        fileType: query.fileType || undefined,
        parseStatus: query.parseStatus || undefined,
        enabled: query.enabled,
      });
      documentRows.value = result.list || [];
      documentTotal.value = result.total || 0;
      documentRows.value
        .filter((document) => isParsePending(document.parseStatus))
        .forEach((document) => scheduleDocumentPoll(document.id));
    } finally {
      documentLoading.value = false;
    }
  }

  function applyDocumentFilters() {
    query.current = 1;
    loadDocuments();
  }

  function selectFileType(action: SheetAction) {
    query.fileType = action.value || null;
    applyDocumentFilters();
  }

  function selectParseStatus(action: SheetAction) {
    query.parseStatus = action.value || null;
    applyDocumentFilters();
  }

  function selectEnabled(action: SheetAction) {
    query.enabled = action.value === '' ? null : Number(action.value);
    applyDocumentFilters();
  }

  function selectDocumentPageSize(action: SheetAction) {
    query.pageSize = Number(action.value);
    query.current = 1;
    loadDocuments();
  }

  function selectChunkPageSize(action: SheetAction) {
    chunkQuery.pageSize = Number(action.value);
    chunkQuery.current = 1;
    loadChunks();
  }

  function openUploadPopup() {
    selectedUploadFile.value = null;
    uploadRemark.value = '';
    uploadProgress.value = 0;
    uploadPopupVisible.value = true;
  }

  function openUploadFilePicker() {
    if (!uploadInputRef.value) return;
    uploadInputRef.value.value = '';
    uploadInputRef.value.click();
  }

  function handleUploadSelection(event: Event) {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    if (!file) return;
    const extension = fileExtension(file.name);
    if (!allowedExtensions.has(extension)) {
      showToast(t('aiKnowledge.unsupportedFile'));
      return;
    }
    if (file.size > MAX_UPLOAD_SIZE) {
      showToast(t('aiKnowledge.fileTooLarge'));
      return;
    }
    selectedUploadFile.value = file;
  }

  async function submitUpload() {
    const file = selectedUploadFile.value;
    if (!file || uploading.value) return;
    uploading.value = true;
    uploadProgress.value = 30;
    try {
      const uploadedDocument = await uploadAiKnowledgeDocument(file, uploadRemark.value.trim() || undefined);
      uploadProgress.value = 100;
      showSuccessToast(t('aiKnowledge.uploadSuccess'));
      uploadPopupVisible.value = false;
      await loadDocuments();
      if (uploadedDocument?.id && isParsePending(uploadedDocument.parseStatus)) {
        scheduleDocumentPoll(uploadedDocument.id, 500);
      }
    } catch {
      showFailToast(t('aiKnowledge.uploadFailed'));
    } finally {
      uploading.value = false;
    }
  }

  async function openChunks(document: AiKnowledgeDocumentItem) {
    currentDocument.value = document;
    chunkRows.value = [];
    chunkQuery.current = 1;
    chunkPopupVisible.value = true;
    await loadChunks();
  }

  async function loadChunks() {
    if (!currentDocument.value) return;
    chunkLoading.value = true;
    try {
      const result = await getAiKnowledgeChunkPage({
        current: chunkQuery.current,
        pageSize: chunkQuery.pageSize,
        documentId: currentDocument.value.id,
      });
      chunkRows.value = result.list || [];
      chunkTotal.value = result.total || 0;
    } finally {
      chunkLoading.value = false;
    }
  }

  async function reparse(document: AiKnowledgeDocumentItem) {
    if (reparsingDocumentId.value) return;
    reparsingDocumentId.value = document.id;
    try {
      await reparseAiKnowledgeDocument(document.id);
      showSuccessToast(t('aiKnowledge.reparseStarted'));
      scheduleDocumentPoll(document.id, 500);
      await loadDocuments();
    } finally {
      reparsingDocumentId.value = null;
    }
  }

  function downloadDocument(document: AiKnowledgeDocumentItem) {
    window.open(getAiKnowledgeDocumentDownloadUrl(document.id), '_blank');
  }

  async function confirmDelete(document: AiKnowledgeDocumentItem) {
    try {
      await showConfirmDialog({
        title: t('aiKnowledge.deleteDocument'),
        message: t('aiKnowledge.deleteConfirm', { name: document.name }),
        confirmButtonText: t('common.delete'),
        confirmButtonColor: 'var(--error-1)',
      });
      await deleteAiKnowledgeDocument(document.id);
      showSuccessToast(t('aiKnowledge.deleteSuccess'));
      if (documentRows.value.length === 1 && query.current > 1) query.current -= 1;
      await loadDocuments();
    } catch {
      // Canceling the confirmation does not require feedback.
    }
  }

  function selectTestMode(mode: AiKnowledgeSearchMode) {
    testMode.value = mode;
    testResult.value = null;
  }

  async function runSearchTest() {
    const question = testQuestion.value.trim();
    if (!question) {
      showToast(t('aiKnowledge.enterQuestion'));
      return;
    }
    testLoading.value = true;
    try {
      testResult.value = await testAiKnowledgeSearch(question, 5, testMode.value);
    } finally {
      testLoading.value = false;
    }
  }

  function fileExtension(fileName: string) {
    return fileName.split('.').pop()?.toLocaleLowerCase() || '';
  }

  function displayFileType(fileType: string) {
    return fileType?.toLocaleUpperCase() || '-';
  }

  function formatFileSize(size?: number) {
    const value = size || 0;
    if (value < 1024) return `${value} B`;
    if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KB`;
    return `${(value / 1024 / 1024).toFixed(1)} MB`;
  }

  function parseStatusText(document: AiKnowledgeDocumentItem) {
    return (
      parseStatusOptions.value.find((item) => item.value === document.parseStatus)?.label || document.parseStatus || '-'
    );
  }

  function parseStatusTone(status: string) {
    if (status === 'PARSED') return 'success';
    if (status === 'FAILED') return 'danger';
    if (status === 'PARSING') return 'warning';
    return 'neutral';
  }

  function normalizedRuleStats(document: AiKnowledgeDocumentItem) {
    return (
      document.ruleStats || {
        total: document.chunkCount || 0,
        pending: 0,
        approved: 0,
        rejected: 0,
        invalid: 0,
      }
    );
  }

  function knowledgeChunkCount(document: AiKnowledgeDocumentItem) {
    const stats = normalizedRuleStats(document);
    return stats.approved || document.chunkCount || 0;
  }

  function semanticDocumentStatus(document: AiKnowledgeDocumentItem) {
    if (isParsePending(document.parseStatus)) return 'PARSING';
    if (document.parseStatus === 'FAILED') return 'FAILED';
    if (document.enabled) return 'ACTIVE';
    return document.semanticStatus || 'INACTIVE';
  }

  function semanticStatusText(status: string) {
    const labels: Record<string, string> = {
      PARSING: t('aiKnowledge.status.extracting'),
      FAILED: t('aiKnowledge.status.failedShort'),
      ACTIVE: t('aiKnowledge.status.active'),
      INACTIVE: t('aiKnowledge.status.inactive'),
    };
    return labels[status] || status;
  }

  function semanticStatusTone(status: string) {
    if (status === 'ACTIVE') return 'success';
    if (status === 'PARSING') return 'warning';
    if (status === 'FAILED') return 'danger';
    return 'neutral';
  }

  function isParsePending(status: string) {
    return status === 'UPLOADED' || status === 'PARSING';
  }

  function scheduleDocumentPoll(documentId: string, delay = 2500) {
    if (pageUnmounted || documentPollTimers.has(documentId) || pollingDocumentIds.has(documentId)) return;
    const timer = setTimeout(() => {
      documentPollTimers.delete(documentId);
      pollDocumentDetail(documentId);
    }, delay);
    documentPollTimers.set(documentId, timer);
  }

  async function pollDocumentDetail(documentId: string) {
    if (pageUnmounted || pollingDocumentIds.has(documentId)) return;
    pollingDocumentIds.add(documentId);
    let continuePolling = false;
    try {
      const document = await getAiKnowledgeDocumentDetail(documentId);
      documentPollFailures.delete(documentId);
      updateDocumentRow(document);
      continuePolling = isParsePending(document.parseStatus);
      if (!continuePolling) await loadDocuments();
    } catch {
      const failures = (documentPollFailures.get(documentId) || 0) + 1;
      documentPollFailures.set(documentId, failures);
      continuePolling = failures < 3;
      if (!continuePolling) showToast(t('aiKnowledge.refreshFailed'));
    } finally {
      pollingDocumentIds.delete(documentId);
      if (continuePolling) scheduleDocumentPoll(documentId);
    }
  }

  function updateDocumentRow(document: AiKnowledgeDocumentItem) {
    const index = documentRows.value.findIndex((item) => item.id === document.id);
    if (index >= 0) documentRows.value.splice(index, 1, document);
  }

  function clearDocumentPolls() {
    pageUnmounted = true;
    documentPollTimers.forEach((timer) => clearTimeout(timer));
    documentPollTimers.clear();
    documentPollFailures.clear();
    pollingDocumentIds.clear();
  }

  function matchedByText(matchedBy: string) {
    if (matchedBy === 'CANONICAL_TERM') return t('aiKnowledge.canonicalMatch');
    if (matchedBy === 'ALIAS') return t('aiKnowledge.aliasMatch');
    return matchedBy;
  }

  function formatScore(score: number) {
    return Number(score || 0).toFixed(1);
  }

  function formatInjectedContext(context: AiSemanticInjectedContext) {
    return JSON.stringify(context, null, 2);
  }

  onMounted(loadDocuments);
  onBeforeUnmount(clearDocumentPolls);
</script>

<style lang="less" scoped>
  .knowledge-page {
    padding: 12px;
    min-height: 100%;
    background: var(--text-n9);
  }
  .knowledge-summary,
  .knowledge-section {
    background: var(--text-n10);
  }
  .knowledge-summary {
    display: flex;
    justify-content: space-between;
    align-items: flex-start;
    padding: 14px;
    border-radius: 7px;
    gap: 12px;
  }
  .knowledge-summary > div {
    min-width: 0;
    flex: 1;
  }
  .knowledge-summary h1,
  .knowledge-summary p,
  .section-heading h2,
  .popup-content h2,
  .result-section h3 {
    margin: 0;
  }
  .knowledge-summary h1 {
    font-size: 18px;
    font-weight: 600;
    line-height: 26px;
    color: var(--text-n1);
  }
  .knowledge-summary p {
    margin-top: 5px;
    font-size: 12px;
    line-height: 19px;
    color: var(--text-n4);
  }
  .primary-command,
  .search-command,
  .test-command,
  .popup-primary-button {
    display: inline-flex;
    justify-content: center;
    align-items: center;
    border: 0;
    border-radius: 5px;
    color: #ffffff;
    background: var(--primary-8);
    gap: 5px;
  }
  .primary-command {
    padding: 0 10px;
    min-width: 92px;
    height: 38px;
    font-size: 13px;
    flex-shrink: 0;
  }
  .knowledge-section {
    margin-top: 12px;
    padding: 12px;
    border-radius: 7px;
  }
  .document-search-row {
    display: grid;
    align-items: center;
    grid-template-columns: minmax(0, 1fr) 58px;
    gap: 7px;
  }
  .document-search-row :deep(.van-search) {
    padding: 0;
  }
  .search-command {
    height: 38px;
    font-size: 13px;
  }
  .filter-strip {
    display: grid;
    margin-top: 9px;
    grid-template-columns: repeat(3, minmax(0, 1fr));
    gap: 6px;
  }
  .filter-strip button,
  .page-size-button {
    display: flex;
    justify-content: center;
    align-items: center;
    overflow: hidden;
    min-width: 0;
    height: 36px;
    font-size: 12px;
    border: 1px solid var(--text-n7);
    border-radius: 5px;
    color: var(--text-n3);
    background: var(--text-n10);
    gap: 4px;
  }
  .filter-strip button span {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .document-loading {
    display: flex;
    margin-top: 12px;
    flex-direction: column;
    gap: 12px;
  }
  .document-list {
    display: flex;
    margin-top: 12px;
    flex-direction: column;
    gap: 10px;
  }
  .document-card {
    overflow: hidden;
    border: 1px solid var(--text-n8);
    border-radius: 6px;
  }
  .document-card__header {
    display: flex;
    align-items: center;
    padding: 11px 11px 9px;
    min-width: 0;
    gap: 9px;
  }
  .document-file-icon {
    display: flex;
    justify-content: center;
    align-items: center;
    width: 36px;
    height: 36px;
    font-size: 19px;
    border-radius: 5px;
    color: var(--primary-8);
    background: var(--primary-7);
    flex-shrink: 0;
  }
  .document-title {
    display: flex;
    overflow: hidden;
    min-width: 0;
    flex-direction: column;
    gap: 3px;
  }
  .document-title strong {
    overflow: hidden;
    font-size: 14px;
    font-weight: 500;
    text-overflow: ellipsis;
    white-space: nowrap;
    color: var(--text-n1);
  }
  .document-title span,
  .document-stats span {
    font-size: 11px;
    color: var(--text-n4);
  }
  .document-stats {
    display: grid;
    padding: 9px 11px;
    border-top: 1px solid var(--text-n8);
    border-bottom: 1px solid var(--text-n8);
    background: var(--text-n9);
    grid-template-columns: repeat(3, minmax(0, 1fr));
    gap: 8px;
  }
  .document-stats > div {
    display: flex;
    min-width: 0;
    flex-direction: column;
    gap: 4px;
  }
  .document-stats strong {
    overflow: hidden;
    font-size: 12px;
    font-weight: 500;
    text-overflow: ellipsis;
    white-space: nowrap;
    color: var(--text-n2);
  }
  .status-text--success {
    color: var(--success-1) !important;
  }
  .status-text--warning {
    color: var(--warning-1) !important;
  }
  .status-text--danger {
    color: var(--error-1) !important;
  }
  .status-text--neutral {
    color: var(--text-n3) !important;
  }
  .document-remark {
    margin: 0;
    padding: 9px 11px 0;
    font-size: 12px;
    line-height: 18px;
    color: var(--text-n3);
    overflow-wrap: anywhere;
  }
  .document-actions {
    display: grid;
    padding: 7px;
    grid-template-columns: repeat(4, minmax(0, 1fr));
    gap: 4px;
  }
  .document-actions button {
    display: flex;
    justify-content: center;
    align-items: center;
    padding: 0 3px;
    min-width: 0;
    height: 34px;
    font-size: 11px;
    border: 0;
    border-radius: 4px;
    white-space: nowrap;
    color: var(--primary-8);
    background: transparent;
    gap: 3px;
  }
  .document-actions button:disabled {
    opacity: 0.5;
  }
  .document-actions .danger-action {
    color: var(--error-1);
  }
  .pagination-row,
  .chunk-pagination {
    display: flex;
    align-items: center;
    margin-top: 12px;
    gap: 8px;
  }
  .pagination-row :deep(.van-pagination),
  .chunk-pagination :deep(.van-pagination) {
    min-width: 0;
    flex: 1;
  }
  .page-size-button {
    padding: 0 8px;
    flex-shrink: 0;
  }
  .section-heading {
    display: flex;
    justify-content: space-between;
    align-items: center;
    gap: 10px;
  }
  .section-heading h2 {
    font-size: 16px;
    font-weight: 600;
    color: var(--text-n1);
  }
  .test-mode-switch {
    display: grid;
    overflow: hidden;
    border: 1px solid var(--text-n7);
    border-radius: 5px;
    grid-template-columns: repeat(3, auto);
  }
  .test-mode-switch button {
    padding: 0 7px;
    height: 30px;
    font-size: 11px;
    border: 0;
    border-right: 1px solid var(--text-n7);
    white-space: nowrap;
    color: var(--text-n3);
    background: var(--text-n10);
  }
  .test-mode-switch button:last-child {
    border-right: 0;
  }
  .test-mode-switch button.active {
    color: #ffffff;
    background: var(--primary-8);
  }
  .test-question {
    margin-top: 11px;
    border: 1px solid var(--text-n7);
    border-radius: 5px;
  }
  .test-command {
    margin-top: 9px;
    width: 100%;
    height: 40px;
    font-size: 13px;
  }
  .test-command:disabled,
  .popup-primary-button:disabled {
    opacity: 0.5;
  }
  .test-results {
    display: flex;
    margin-top: 12px;
    flex-direction: column;
    gap: 10px;
  }
  .result-alert {
    padding: 10px;
    font-size: 12px;
    border-radius: 5px;
    line-height: 19px;
    overflow-wrap: anywhere;
  }
  .result-alert--info {
    color: var(--primary-8);
    background: var(--primary-7);
  }
  .result-alert--warning {
    color: var(--warning-1);
    background: var(--warning-5);
  }
  .result-section {
    display: flex;
    flex-direction: column;
    gap: 8px;
  }
  .result-section h3 {
    font-size: 14px;
    font-weight: 600;
    color: var(--text-n1);
  }
  .result-section__heading {
    display: flex;
    justify-content: space-between;
    align-items: center;
    gap: 8px;
  }
  .result-section__heading > span {
    padding: 3px 6px;
    font-size: 10px;
    border-radius: 4px;
    color: var(--success-1);
    background: var(--success-5);
  }
  .match-card {
    padding: 10px;
    border: 1px solid var(--text-n8);
    border-radius: 5px;
  }
  .match-card > strong {
    font-size: 13px;
    font-weight: 500;
    color: var(--text-n1);
  }
  .match-meta {
    display: flex;
    margin-top: 4px;
    flex-wrap: wrap;
    font-size: 11px;
    color: var(--text-n4);
    gap: 8px;
  }
  .match-card p {
    margin: 7px 0 0;
    font-size: 12px;
    line-height: 19px;
    color: var(--text-n2);
    overflow-wrap: anywhere;
  }
  .result-section pre {
    overflow: auto;
    margin: 0;
    padding: 10px;
    max-height: 260px;
    font-size: 11px;
    font-family: Consolas, Monaco, monospace;
    border: 1px solid var(--text-n8);
    border-radius: 5px;
    white-space: pre-wrap;
    color: var(--text-n2);
    background: var(--text-n9);
    line-height: 18px;
    overflow-wrap: anywhere;
  }
  :global(.knowledge-popup) {
    max-height: 88vh;
  }
  .popup-content {
    padding: 18px 16px max(18px, env(safe-area-inset-bottom));
  }
  .popup-content h2 {
    padding-right: 30px;
    font-size: 17px;
    font-weight: 600;
    color: var(--text-n1);
  }
  .field-label {
    display: block;
    margin: 18px 0 7px;
    font-size: 13px;
    color: var(--text-n2);
  }
  .popup-field {
    border: 1px solid var(--text-n7);
    border-radius: 5px;
  }
  .hidden-file-input {
    position: absolute;
    width: 1px;
    height: 1px;
    opacity: 0;
  }
  .file-picker {
    display: flex;
    justify-content: center;
    align-items: center;
    margin-top: 12px;
    padding: 12px;
    width: 100%;
    min-height: 62px;
    border: 1px dashed var(--primary-8);
    border-radius: 6px;
    color: var(--primary-8);
    background: var(--primary-7);
    flex-direction: column;
    gap: 5px;
  }
  .file-picker > span {
    overflow: hidden;
    max-width: 100%;
    font-size: 13px;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .file-picker small {
    color: var(--text-n4);
  }
  .upload-helper {
    margin: 8px 0 12px;
    font-size: 11px;
    line-height: 18px;
    color: var(--text-n4);
  }
  .popup-primary-button {
    margin-top: 14px;
    width: 100%;
    height: 42px;
    font-size: 14px;
  }
  :global(.chunk-popup) {
    width: 100%;
    height: 100%;
  }
  .chunk-page {
    display: flex;
    overflow: hidden;
    height: 100%;
    background: var(--text-n9);
    flex-direction: column;
  }
  .chunk-page__header {
    display: flex;
    align-items: center;
    padding: 7px 12px;
    min-height: 54px;
    border-bottom: 1px solid var(--text-n8);
    background: var(--text-n10);
    gap: 9px;
    flex-shrink: 0;
  }
  .chunk-page__header button {
    display: flex;
    justify-content: center;
    align-items: center;
    width: 38px;
    height: 38px;
    font-size: 18px;
    border: 0;
    color: var(--text-n1);
    background: transparent;
  }
  .chunk-page__header > div {
    display: flex;
    overflow: hidden;
    min-width: 0;
    flex-direction: column;
    gap: 2px;
  }
  .chunk-page__header h2 {
    margin: 0;
    font-size: 16px;
    font-weight: 600;
    color: var(--text-n1);
  }
  .chunk-page__header span {
    overflow: hidden;
    font-size: 11px;
    text-overflow: ellipsis;
    white-space: nowrap;
    color: var(--text-n4);
  }
  .chunk-list-wrap {
    overflow-y: auto;
    padding: 12px;
    min-height: 0;
    flex: 1;
  }
  .chunk-list {
    display: flex;
    flex-direction: column;
    gap: 9px;
  }
  .chunk-card {
    padding: 11px;
    border: 1px solid var(--text-n8);
    border-radius: 6px;
    background: var(--text-n10);
  }
  .chunk-card header {
    display: flex;
    justify-content: space-between;
    align-items: flex-start;
    gap: 8px;
  }
  .chunk-card strong {
    font-size: 13px;
    font-weight: 500;
    color: var(--text-n1);
  }
  .chunk-card header span {
    font-size: 11px;
    white-space: nowrap;
    color: var(--text-n4);
  }
  .chunk-card p {
    margin: 8px 0 0;
    font-size: 12px;
    white-space: pre-wrap;
    color: var(--text-n2);
    line-height: 19px;
    overflow-wrap: anywhere;
  }
  .chunk-pagination {
    margin: 0;
    padding: 8px 10px max(8px, env(safe-area-inset-bottom));
    border-top: 1px solid var(--text-n8);
    background: var(--text-n10);
    flex-shrink: 0;
  }

  @media (max-width: 340px) {
    .knowledge-summary {
      flex-direction: column;
    }
    .primary-command {
      width: 100%;
    }
    .document-actions {
      grid-template-columns: repeat(2, minmax(0, 1fr));
    }
    .section-heading {
      align-items: flex-start;
      flex-direction: column;
    }
  }
</style>
