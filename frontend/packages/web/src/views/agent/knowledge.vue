<template>
  <n-scrollbar class="knowledge-page">
    <div class="knowledge-inner">
      <header class="knowledge-header">
        <div>
          <h1>公司知识库</h1>
          <p>上传公司的业务资料、规则制度、产品说明和常见问题，让智能体回答前优先参考公司知识。</p>
        </div>
        <n-button type="primary" @click="openUploadModal">上传文档</n-button>
      </header>

      <section class="knowledge-panel">
        <div class="toolbar">
          <n-input v-model:value="query.keyword" clearable placeholder="搜索文件名、备注" />
          <n-select v-model:value="query.fileType" clearable :options="fileTypeOptions" placeholder="文件类型" />
          <n-select v-model:value="query.category" clearable :options="categoryOptions" placeholder="知识分类" />
          <n-select v-model:value="query.parseStatus" clearable :options="parseStatusOptions" placeholder="解析状态" />
          <n-select v-model:value="query.enabled" clearable :options="enabledOptions" placeholder="启用状态" />
          <n-button @click="loadDocuments">查询</n-button>
        </div>

        <n-data-table
          remote
          :columns="documentColumns"
          :data="documentRows"
          :loading="documentLoading"
          :pagination="documentPagination"
          @update:page="updateDocumentPage"
          @update:page-size="updateDocumentPageSize"
        />
      </section>

      <section class="knowledge-panel search-panel">
        <div class="section-title">
          <h2>知识检索测试</h2>
        </div>
        <div class="test-input">
          <n-input
            v-model:value="testQuestion"
            type="textarea"
            :autosize="{ minRows: 3, maxRows: 5 }"
            placeholder="输入一个问题，例如：品种是什么意思"
          />
          <n-button type="primary" :loading="testLoading" @click="runSearchTest">测试检索</n-button>
        </div>
        <div v-if="testResult" class="test-result">
          <n-alert type="info" :show-icon="false">
            {{ testResult.answerPreview || '已完成检索测试' }}
          </n-alert>
          <n-empty v-if="!testResult.matches?.length" description="未命中文档片段" />
          <div v-else class="match-list">
            <article v-for="match in testResult.matches" :key="match.chunkId" class="match-item">
              <div class="match-meta">
                <strong>{{ match.documentName || '未命名文档' }}</strong>
                <span v-if="match.pageNo">第 {{ match.pageNo }} 页</span>
                <span>相关度 {{ match.score.toFixed(1) }}</span>
              </div>
              <p>{{ match.content }}</p>
            </article>
          </div>
        </div>
      </section>
    </div>
  </n-scrollbar>

  <n-modal v-model:show="uploadModalVisible" preset="card" title="上传知识文档" class="knowledge-modal">
    <n-form label-placement="top">
      <n-form-item label="文件">
        <n-upload
          v-model:file-list="uploadFileList"
          :max="1"
          accept=".pdf,.docx,.txt,.md"
          :custom-request="customUploadRequest"
          @before-upload="beforeUpload"
        >
          <n-upload-dragger>
            <div class="upload-dragger">
              <strong>点击或拖拽文件到这里上传</strong>
              <span>支持 PDF、DOCX、TXT、MD，单文件不超过 50MB</span>
            </div>
          </n-upload-dragger>
        </n-upload>
      </n-form-item>
      <n-form-item label="知识分类">
        <n-select v-model:value="uploadForm.category" clearable :options="categoryOptions" />
      </n-form-item>
      <n-form-item label="备注">
        <n-input v-model:value="uploadForm.remark" type="textarea" :autosize="{ minRows: 2, maxRows: 4 }" />
      </n-form-item>
    </n-form>
  </n-modal>

  <n-drawer v-model:show="chunkDrawerVisible" :width="720">
    <n-drawer-content title="文档切片">
      <n-data-table
        remote
        :columns="chunkColumns"
        :data="chunkRows"
        :loading="chunkLoading"
        :pagination="chunkPagination"
        @update:page="updateChunkPage"
        @update:page-size="updateChunkPageSize"
      />
    </n-drawer-content>
  </n-drawer>
</template>

<script setup lang="ts">
  /* eslint-disable no-use-before-define */
  import {
    NAlert,
    NButton,
    NDataTable,
    NDrawer,
    NDrawerContent,
    NEmpty,
    NForm,
    NFormItem,
    NInput,
    NModal,
    NScrollbar,
    NSelect,
    NTag,
    NUpload,
    NUploadDragger,
    type UploadCustomRequestOptions,
    type UploadFileInfo,
    type UploadSettledFileInfo,
    useDialog,
    useMessage,
  } from 'naive-ui';

  import type {
    AiKnowledgeChunkItem,
    AiKnowledgeDocumentItem,
    AiKnowledgeSearchTestResult,
  } from '@lib/shared/api/modules/aiAgent';

  import {
    deleteAiKnowledgeDocument,
    disableAiKnowledgeDocument,
    enableAiKnowledgeDocument,
    getAiKnowledgeChunkPage,
    getAiKnowledgeDocumentDownloadUrl,
    getAiKnowledgeDocumentPage,
    reparseAiKnowledgeDocument,
    testAiKnowledgeSearch,
    uploadAiKnowledgeDocument,
  } from '@/api/modules';

  const message = useMessage();
  const dialog = useDialog();

  const fileTypeOptions = [
    { label: 'PDF', value: 'pdf' },
    { label: 'Word', value: 'docx' },
    { label: 'TXT', value: 'txt' },
    { label: 'Markdown', value: 'md' },
  ];
  const categoryOptions = [
    { label: '产品资料', value: 'PRODUCT' },
    { label: '业务规则', value: 'BUSINESS_RULE' },
    { label: '报价规则', value: 'PRICE_RULE' },
    { label: '订单规则', value: 'ORDER_RULE' },
    { label: '客户规则', value: 'CUSTOMER_RULE' },
    { label: '售后规则', value: 'AFTER_SALES' },
    { label: '其他', value: 'OTHER' },
  ];
  const parseStatusOptions = [
    { label: '已上传', value: 'UPLOADED' },
    { label: '解析中', value: 'PARSING' },
    { label: '解析成功', value: 'PARSED' },
    { label: '解析失败', value: 'FAILED' },
  ];
  const enabledOptions = [
    { label: '启用', value: 1 },
    { label: '停用', value: 0 },
  ];

  const query = reactive({
    current: 1,
    pageSize: 20,
    keyword: '',
    fileType: null as string | null,
    category: null as string | null,
    parseStatus: null as string | null,
    enabled: null as number | null,
  });

  const documentRows = ref<AiKnowledgeDocumentItem[]>([]);
  const documentLoading = ref(false);
  const documentTotal = ref(0);

  const uploadModalVisible = ref(false);
  const uploadFileList = ref<UploadFileInfo[]>([]);
  const uploadForm = reactive({
    category: null as string | null,
    remark: '',
  });

  const chunkDrawerVisible = ref(false);
  const currentDocument = ref<AiKnowledgeDocumentItem | null>(null);
  const chunkRows = ref<AiKnowledgeChunkItem[]>([]);
  const chunkLoading = ref(false);
  const chunkTotal = ref(0);
  const chunkQuery = reactive({
    current: 1,
    pageSize: 10,
  });

  const testQuestion = ref('');
  const testLoading = ref(false);
  const testResult = ref<AiKnowledgeSearchTestResult | null>(null);

  const documentPagination = computed(() => ({
    page: query.current,
    pageSize: query.pageSize,
    itemCount: documentTotal.value,
    showSizePicker: true,
    pageSizes: [10, 20, 50, 100],
  }));

  const chunkPagination = computed(() => ({
    page: chunkQuery.current,
    pageSize: chunkQuery.pageSize,
    itemCount: chunkTotal.value,
    showSizePicker: true,
    pageSizes: [10, 20, 50],
  }));

  const documentColumns = [
    { title: '文件名', key: 'name', minWidth: 220 },
    { title: '类型', key: 'fileType', width: 90 },
    {
      title: '大小',
      key: 'fileSize',
      width: 110,
      render(row: AiKnowledgeDocumentItem) {
        return formatFileSize(row.fileSize);
      },
    },
    {
      title: '解析状态',
      key: 'parseStatus',
      width: 120,
      render(row: AiKnowledgeDocumentItem) {
        return h(
          NTag,
          { type: parseStatusType(row.parseStatus), bordered: false },
          { default: () => parseStatusText(row) }
        );
      },
    },
    { title: '切片数', key: 'chunkCount', width: 90 },
    {
      title: '状态',
      key: 'enabled',
      width: 90,
      render(row: AiKnowledgeDocumentItem) {
        return h(
          NTag,
          { type: row.enabled ? 'success' : 'default', bordered: false },
          { default: () => (row.enabled ? '启用' : '停用') }
        );
      },
    },
    {
      title: '操作',
      key: 'actions',
      width: 310,
      render(row: AiKnowledgeDocumentItem) {
        return h('div', { class: 'row-actions' }, [
          h(NButton, { size: 'small', quaternary: true, onClick: () => openChunks(row) }, { default: () => '切片' }),
          h(NButton, { size: 'small', quaternary: true, onClick: () => reparse(row) }, { default: () => '重新解析' }),
          h(
            NButton,
            { size: 'small', quaternary: true, onClick: () => toggleEnabled(row) },
            { default: () => (row.enabled ? '停用' : '启用') }
          ),
          h(
            NButton,
            { size: 'small', quaternary: true, onClick: () => downloadDocument(row) },
            { default: () => '下载' }
          ),
          h(
            NButton,
            { size: 'small', quaternary: true, type: 'error', onClick: () => confirmDelete(row) },
            { default: () => '删除' }
          ),
        ]);
      },
    },
  ];

  const chunkColumns = [
    { title: '序号', key: 'chunkIndex', width: 80 },
    { title: '标题', key: 'title', width: 140 },
    { title: '页码', key: 'pageNo', width: 80 },
    { title: '内容', key: 'content', minWidth: 360 },
  ];

  function openUploadModal() {
    uploadFileList.value = [];
    uploadForm.category = null;
    uploadForm.remark = '';
    uploadModalVisible.value = true;
  }

  async function loadDocuments() {
    documentLoading.value = true;
    try {
      const result = await getAiKnowledgeDocumentPage({
        current: query.current,
        pageSize: query.pageSize,
        keyword: query.keyword || undefined,
        fileType: query.fileType || undefined,
        category: query.category || undefined,
        parseStatus: query.parseStatus || undefined,
        enabled: query.enabled,
      });
      documentRows.value = result.list || [];
      documentTotal.value = result.total || 0;
    } finally {
      documentLoading.value = false;
    }
  }

  function updateDocumentPage(page: number) {
    query.current = page;
    loadDocuments();
  }

  function updateDocumentPageSize(pageSize: number) {
    query.pageSize = pageSize;
    query.current = 1;
    loadDocuments();
  }

  function beforeUpload({ file }: { file: UploadSettledFileInfo }) {
    const rawFile = file.file;
    if (!rawFile) return false;
    const ext = rawFile.name.split('.').pop()?.toLowerCase();
    if (!['pdf', 'docx', 'txt', 'md'].includes(ext || '')) {
      message.warning('暂只支持 PDF、DOCX、TXT、MD 文件');
      return false;
    }
    if (rawFile.size > 50 * 1024 * 1024) {
      message.warning('文件不能超过 50MB');
      return false;
    }
    return true;
  }

  async function customUploadRequest({ file, onFinish, onError, onProgress }: UploadCustomRequestOptions) {
    try {
      if (!file.file) return;
      onProgress({ percent: 30 });
      await uploadAiKnowledgeDocument(file.file, uploadForm.category || undefined, uploadForm.remark || undefined);
      onProgress({ percent: 100 });
      onFinish();
      message.success('上传成功，已开始解析');
      uploadModalVisible.value = false;
      await loadDocuments();
    } catch (error) {
      onError();
    }
  }

  async function openChunks(row: AiKnowledgeDocumentItem) {
    currentDocument.value = row;
    chunkQuery.current = 1;
    chunkDrawerVisible.value = true;
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

  function updateChunkPage(page: number) {
    chunkQuery.current = page;
    loadChunks();
  }

  function updateChunkPageSize(pageSize: number) {
    chunkQuery.pageSize = pageSize;
    chunkQuery.current = 1;
    loadChunks();
  }

  async function reparse(row: AiKnowledgeDocumentItem) {
    await reparseAiKnowledgeDocument(row.id);
    message.success('已重新解析');
    await loadDocuments();
  }

  async function toggleEnabled(row: AiKnowledgeDocumentItem) {
    if (row.enabled) {
      await disableAiKnowledgeDocument(row.id);
      message.success('已停用');
    } else {
      await enableAiKnowledgeDocument(row.id);
      message.success('已启用');
    }
    await loadDocuments();
  }

  function downloadDocument(row: AiKnowledgeDocumentItem) {
    window.open(getAiKnowledgeDocumentDownloadUrl(row.id), '_blank');
  }

  function confirmDelete(row: AiKnowledgeDocumentItem) {
    dialog.warning({
      title: '删除文档',
      content: `确定删除“${row.name}”吗？删除后会同步删除文档切片。`,
      positiveText: '删除',
      negativeText: '取消',
      onPositiveClick: async () => {
        await deleteAiKnowledgeDocument(row.id);
        message.success('删除成功');
        await loadDocuments();
      },
    });
  }

  async function runSearchTest() {
    const text = testQuestion.value.trim();
    if (!text) {
      message.warning('请输入要测试的问题');
      return;
    }
    testLoading.value = true;
    try {
      testResult.value = await testAiKnowledgeSearch(text, 8);
    } finally {
      testLoading.value = false;
    }
  }

  function formatFileSize(size?: number) {
    const value = size || 0;
    if (value < 1024) return `${value} B`;
    if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KB`;
    return `${(value / 1024 / 1024).toFixed(1)} MB`;
  }

  function parseStatusText(row: AiKnowledgeDocumentItem) {
    const option = parseStatusOptions.find((item) => item.value === row.parseStatus);
    return option?.label || row.parseStatus;
  }

  function parseStatusType(status: string) {
    if (status === 'PARSED') return 'success';
    if (status === 'FAILED') return 'error';
    if (status === 'PARSING') return 'warning';
    return 'default';
  }

  onMounted(loadDocuments);
</script>

<style lang="less" scoped>
  .knowledge-page {
    height: 100%;
    background: var(--text-n9);
  }

  .knowledge-inner {
    padding: 20px;
  }

  .knowledge-header,
  .knowledge-panel {
    border-radius: 8px;
    background: var(--text-n10);
  }

  .knowledge-header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    margin-bottom: 16px;
    padding: 20px;
    gap: 16px;
  }

  .knowledge-header h1,
  .section-title h2 {
    margin: 0;
    color: var(--text-n1);
    font-weight: 600;
  }

  .knowledge-header h1 {
    font-size: 22px;
    line-height: 32px;
  }

  .section-title h2 {
    font-size: 16px;
    line-height: 24px;
  }

  .knowledge-header p {
    margin: 4px 0 0;
    color: var(--text-n3);
    line-height: 22px;
  }

  .knowledge-panel {
    margin-bottom: 16px;
    padding: 12px;
  }

  .toolbar {
    display: grid;
    align-items: center;
    margin-bottom: 12px;
    grid-template-columns: minmax(240px, 1fr) 130px 150px 150px 130px auto;
    gap: 10px;
  }

  .search-panel {
    display: flex;
    flex-direction: column;
    gap: 12px;
  }

  .test-input {
    display: grid;
    align-items: end;
    grid-template-columns: minmax(0, 1fr) auto;
    gap: 12px;
  }

  .test-result,
  .match-list {
    display: flex;
    flex-direction: column;
    gap: 10px;
  }

  .match-item {
    padding: 12px;
    border: 1px solid var(--line-n3);
    border-radius: 8px;
  }

  .match-item p {
    margin: 8px 0 0;
    line-height: 22px;
    color: var(--text-n2);
  }

  .match-meta {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    color: var(--text-n3);
    gap: 10px;
  }

  .match-meta strong {
    color: var(--text-n1);
  }

  .knowledge-modal {
    width: min(640px, calc(100vw - 48px));
  }

  .upload-dragger {
    display: flex;
    align-items: center;
    flex-direction: column;
    padding: 24px;
    color: var(--text-n3);
    gap: 8px;
  }

  .upload-dragger strong {
    color: var(--text-n1);
  }

  :deep(.row-actions) {
    display: flex;
    flex-wrap: wrap;
    gap: 4px;
  }

  @media (max-width: 1180px) {
    .toolbar,
    .test-input {
      grid-template-columns: 1fr;
    }
  }
</style>
