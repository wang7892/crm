<template>
  <main class="query-knowledge-page">
    <header class="page-heading">
      <div class="heading-title"
        ><h1>{{ title }}</h1
        ><span>{{ rows.length }}</span></div
      >
      <div class="commands">
        <n-button @click="exportFile(false)"
          ><template #icon><DownloadOutline /></template>导出</n-button
        >
        <n-button @click="importVisible = true"
          ><template #icon><CloudUploadOutline /></template>导入</n-button
        >
        <n-button type="primary" @click="edit()"
          ><template #icon><AddOutline /></template>{{ isTerm ? '新建术语' : '添加示例 SQL' }}</n-button
        >
      </div>
    </header>

    <div class="filter-bar">
      <n-input
        v-model:value="keyword"
        clearable
        :placeholder="isTerm ? '搜索术语、同义词、描述' : '搜索问题、SQL'"
        class="keyword-filter"
      >
        <template #prefix><SearchOutline /></template>
      </n-input>
      <n-select
        v-model:value="sourceFilter"
        :options="sourceOptions"
        placeholder="生效数据源"
        clearable
        class="source-filter"
      />
      <n-select
        v-model:value="statusFilter"
        :options="statusOptions"
        placeholder="状态"
        clearable
        class="status-filter"
      />
      <n-tooltip
        ><template #trigger
          ><n-button circle quaternary aria-label="刷新" :loading="loading" @click="load"
            ><template #icon><RefreshOutline /></template></n-button></template
        >刷新</n-tooltip
      >
      <n-tooltip
        ><template #trigger
          ><n-button circle quaternary aria-label="匹配测试" @click="openTest"
            ><template #icon><FlaskOutline /></template></n-button></template
        >匹配测试</n-tooltip
      >
    </div>

    <div v-if="checked.length" class="selection-bar">
      <span>已选择 {{ checked.length }} 条</span>
      <n-button size="small" @click="checked = []">清空选择</n-button>
      <n-button v-if="canDelete" size="small" type="error" secondary @click="confirmDelete(selectedRows)"
        ><template #icon><TrashOutline /></template>删除</n-button
      >
    </div>
    <n-alert v-if="loadError" type="error" :bordered="false" class="load-error">{{ loadError }}</n-alert>
    <n-data-table
      v-model:checked-row-keys="checked"
      :columns="columns"
      :data="filtered"
      :loading="loading"
      :row-key="(row: QueryKnowledgeItem) => row.id!"
      :pagination="pagination"
      :scroll-x="isTerm ? 960 : 900"
      :bordered="false"
      :single-line="false"
      :max-height="tableHeight"
    />

    <n-drawer v-model:show="editorVisible" :width="600" :mask-closable="!saving" class="knowledge-drawer">
      <n-drawer-content :title="`${form.id ? '编辑' : '新建'}${isTerm ? '术语' : 'SQL 示例'}`" closable>
        <n-form ref="formRef" :model="form" :rules="rules" label-placement="top">
          <n-form-item path="name" :label="isTerm ? '术语名称' : '问题描述'">
            <n-input
              v-model:value="form.name"
              :maxlength="255"
              show-count
              :placeholder="isTerm ? '请输入术语名称' : '请输入问题描述'"
            />
          </n-form-item>
          <n-form-item path="content" :label="isTerm ? '术语描述' : '示例 SQL'">
            <n-input
              v-model:value="form.content"
              type="textarea"
              :autosize="{ minRows: isTerm ? 5 : 12, maxRows: 20 }"
              :maxlength="isTerm ? 6000 : 20000"
              show-count
              :placeholder="isTerm ? '请输入业务定义' : 'SELECT ...'"
              :class="!isTerm && 'sql-input'"
            />
          </n-form-item>
          <n-form-item v-if="isTerm" label="同义词">
            <n-dynamic-tags v-model:value="form.synonyms" :max="30" />
          </n-form-item>
          <n-form-item label="生效数据源" path="datasourceIds">
            <div class="scope-field">
              <n-radio-group v-if="isTerm" v-model:value="scopeMode" name="knowledge-scope">
                <n-radio value="ALL">所有数据源</n-radio><n-radio value="SELECTED">部分数据源</n-radio>
              </n-radio-group>
              <n-select
                v-if="!isTerm || scopeMode === 'SELECTED'"
                :value="isTerm ? form.datasourceIds : form.datasourceIds[0]"
                :options="sourceOptions"
                :multiple="isTerm"
                filterable
                placeholder="请选择数据源"
                @update:value="setScope"
              />
            </div>
          </n-form-item>
          <n-form-item label="状态"
            ><n-switch v-model:value="form.enabled"
              ><template #checked>启用</template><template #unchecked>停用</template></n-switch
            ></n-form-item
          >
        </n-form>
        <template #footer
          ><n-space
            ><n-button :disabled="saving" @click="editorVisible = false">取消</n-button
            ><n-button type="primary" :loading="saving" @click="save">保存</n-button></n-space
          ></template
        >
      </n-drawer-content>
    </n-drawer>

    <n-drawer v-model:show="detailVisible" :width="650" class="knowledge-drawer">
      <n-drawer-content :title="detail?.name" closable>
        <template v-if="detail">
          <dl class="detail-meta"
            ><dt>生效数据源</dt><dd>{{ scopeLabel(detail) }}</dd
            ><dt>状态</dt><dd>{{ detail.enabled ? '启用' : '停用' }}</dd>
            <template v-if="detail.synonyms.length"
              ><dt>同义词</dt><dd>{{ detail.synonyms.join('、') }}</dd></template
            >
          </dl>
          <div class="content-heading"
            ><h2>{{ isTerm ? '术语描述' : '示例 SQL' }}</h2>
            <n-tooltip
              ><template #trigger
                ><n-button circle quaternary aria-label="复制内容" @click="copy(detail.content)"
                  ><template #icon><CopyOutline /></template></n-button></template
              >复制</n-tooltip
            >
          </div>
          <div v-if="isTerm" class="term-content">{{ detail.content }}</div>
          <pre
            v-else
            class="sql-content"
          ><code v-html="highlightAiAgentSql(formatAiAgentSql(detail.content, sourceType(detail)))"></code></pre>
        </template>
      </n-drawer-content>
    </n-drawer>

    <n-modal v-model:show="importVisible" preset="card" title="导入问数知识" class="knowledge-modal">
      <div class="import-actions"
        ><n-button :loading="exporting" @click="exportFile(true)"
          ><template #icon><DownloadOutline /></template>下载模板</n-button
        >
        <n-upload accept=".xlsx,.xls,.csv" :show-file-list="false" :custom-request="importFile" :disabled="importing">
          <n-button type="primary" :loading="importing"
            ><template #icon><CloudUploadOutline /></template>选择文件</n-button
          >
        </n-upload>
      </div>
    </n-modal>

    <n-modal v-model:show="testVisible" preset="card" title="知识匹配测试" class="knowledge-modal">
      <n-space vertical :size="16">
        <n-select v-model:value="testSource" :options="sourceOptions" />
        <n-input
          v-model:value="testQuestion"
          type="textarea"
          :maxlength="2000"
          placeholder="请输入问题"
          :autosize="{ minRows: 3 }"
        />
        <n-button type="primary" :loading="testing" @click="test"
          ><template #icon><FlaskOutline /></template>测试匹配</n-button
        >
        <template v-if="testResult">
          <n-alert v-if="testResult.truncated" type="warning">匹配内容超过上下文限制，部分条目未提供给模型。</n-alert>
          <h3>术语配置 · {{ testResult.terms.length }}</h3>
          <div v-for="item in testResult.terms" :key="item.id" class="match-entry"
            ><strong>{{ item.name }}</strong
            ><p>{{ item.content }}</p></div
          >
          <h3>SQL 示例库 · {{ testResult.examples.length }}</h3>
          <div v-for="item in testResult.examples" :key="item.id" class="match-entry"
            ><strong>{{ item.name }}</strong
            ><pre>{{ formatAiAgentSql(item.content, sourceType(item)) }}</pre>
          </div>
        </template>
      </n-space>
    </n-modal>
  </main>
</template>

<script setup lang="ts">
  import { computed, h, onMounted, reactive, ref, watch } from 'vue';
  import { useRoute } from 'vue-router';
  import {
    NAlert,
    NButton,
    NDataTable,
    NDrawer,
    NDrawerContent,
    NDynamicTags,
    NForm,
    NFormItem,
    NInput,
    NModal,
    NRadio,
    NRadioGroup,
    NSelect,
    NSpace,
    NSwitch,
    NTooltip,
    NUpload,
    useDialog,
    useMessage,
  } from 'naive-ui';
  import {
    AddOutline,
    CloudUploadOutline,
    CopyOutline,
    CreateOutline,
    DownloadOutline,
    FlaskOutline,
    RefreshOutline,
    SearchOutline,
    TrashOutline,
  } from '@vicons/ionicons5';

  import type { AiAgentDatasourceItem } from '@lib/shared/api/modules/aiAgent';
  import type {
    QueryKnowledgeItem,
    QueryKnowledgeKind,
    QueryKnowledgeMatches,
  } from '@lib/shared/api/modules/queryKnowledge';
  import { formatAiAgentSql, highlightAiAgentSql } from '@lib/shared/utils/aiAgentSql';

  import {
    deleteQueryKnowledge,
    enableQueryKnowledge,
    exportQueryKnowledge,
    getAiAgentDatasources,
    getQueryKnowledge,
    importQueryKnowledge,
    saveQueryKnowledge,
    testQueryKnowledge,
  } from '@/api/modules';
  import { hasAnyPermission } from '@/utils/permission';

  import type { DataTableColumns, FormInst, FormRules, UploadCustomRequestOptions } from 'naive-ui';

  const route = useRoute();
  const kind = computed<QueryKnowledgeKind>(() => (route.path.endsWith('/examples') ? 'SQL_EXAMPLE' : 'TERM'));
  const isTerm = computed(() => kind.value === 'TERM');
  const title = computed(() => (isTerm.value ? '术语配置' : 'SQL 示例库'));
  const message = useMessage();
  const dialog = useDialog();
  const rows = ref<QueryKnowledgeItem[]>([]);
  const sources = ref<AiAgentDatasourceItem[]>([]);
  const loading = ref(false);
  const loadError = ref('');
  let loadVersion = 0;
  const keyword = ref('');
  const sourceFilter = ref<string | null>(null);
  const statusFilter = ref<string | null>(null);
  const checked = ref<Array<string | number>>([]);
  const pagination = reactive({
    page: 1,
    pageSize: 20,
    showSizePicker: true,
    pageSizes: [10, 20, 50],
    onChange: (page: number) => {
      pagination.page = page;
    },
    onUpdatePageSize: (size: number) => {
      pagination.pageSize = size;
      pagination.page = 1;
    },
  });
  const tableHeight = computed(() => Math.max(300, window.innerHeight - 240));
  const canDelete = computed(() => hasAnyPermission(['AGENT:DELETE']));
  const sourceOptions = computed(() => [
    { label: '内置 CRM', value: 'CRM' },
    ...sources.value.map((s) => ({ label: s.name, value: s.id })),
  ]);
  const statusOptions = [
    { label: '启用', value: 'ENABLED' },
    { label: '停用', value: 'DISABLED' },
  ];
  const filtered = computed(() =>
    rows.value.filter((r) => {
      const text = `${r.name} ${r.content} ${r.synonyms.join(' ')}`.toLowerCase();
      return (
        (!keyword.value || text.includes(keyword.value.trim().toLowerCase())) &&
        (!sourceFilter.value || r.allDatasources || r.datasourceIds.includes(sourceFilter.value)) &&
        (!statusFilter.value || r.enabled === (statusFilter.value === 'ENABLED'))
      );
    })
  );
  const selectedRows = computed(() => rows.value.filter((r) => checked.value.includes(r.id!)));
  const editorVisible = ref(false);
  const saving = ref(false);
  const formRef = ref<FormInst | null>(null);
  const blank = (): QueryKnowledgeItem => ({
    kind: kind.value,
    name: '',
    content: '',
    synonyms: [],
    allDatasources: isTerm.value,
    datasourceIds: [],
    enabled: true,
    revision: 0,
  });
  const form = ref<QueryKnowledgeItem>(blank());
  const scopeMode = computed({
    get: () => (form.value.allDatasources ? 'ALL' : 'SELECTED'),
    set: (v: string) => {
      form.value.allDatasources = v === 'ALL';
      if (v === 'ALL') form.value.datasourceIds = [];
    },
  });
  const rules: FormRules = {
    name: {
      required: true,
      trigger: ['input', 'blur'],
      validator: () => (form.value.name.trim() ? true : new Error('请输入名称或问题')),
    },
    content: {
      required: true,
      trigger: ['input', 'blur'],
      validator: () => (form.value.content.trim() ? true : new Error('请输入描述或 SQL')),
    },
    datasourceIds: {
      trigger: 'change',
      validator: () =>
        form.value.allDatasources || form.value.datasourceIds.length ? true : new Error('请选择生效数据源'),
    },
  };
  const detailVisible = ref(false);
  const detail = ref<QueryKnowledgeItem | null>(null);
  const importVisible = ref(false);
  const importing = ref(false);
  const exporting = ref(false);
  const testVisible = ref(false);
  const testing = ref(false);
  const testQuestion = ref('');
  const testSource = ref('CRM');
  const testResult = ref<QueryKnowledgeMatches | null>(null);
  async function load() {
    const version = ++loadVersion;
    loading.value = true;
    loadError.value = '';
    try {
      const data = await getQueryKnowledge(kind.value);
      if (version !== loadVersion) return;
      if (!Array.isArray(data)) throw new Error('问数知识接口未返回有效数据');
      rows.value = data;
      checked.value = [];
    } catch (e) {
      if (version === loadVersion) {
        rows.value = [];
        loadError.value = e instanceof Error ? e.message : '加载失败，请重试';
      }
    } finally {
      if (version === loadVersion) loading.value = false;
    }
  }

  function scopeLabel(item: QueryKnowledgeItem) {
    return item.allDatasources
      ? '所有数据源'
      : item.datasourceIds
          .map((id) => sourceOptions.value.find((s) => s.value === id)?.label || '数据源不可用')
          .join('、');
  }
  function sourceType(item: QueryKnowledgeItem) {
    return sources.value.find((s) => s.id === item.datasourceIds[0])?.type || 'MYSQL';
  }
  function showDetail(item: QueryKnowledgeItem) {
    detail.value = item;
    detailVisible.value = true;
  }
  function action(label: string, icon: typeof CreateOutline, click: () => void) {
    return h(NTooltip, null, {
      trigger: () =>
        h(
          NButton,
          { 'circle': true, 'quaternary': true, 'size': 'small', 'aria-label': label, 'onClick': click },
          { icon: () => h(icon) }
        ),
      default: () => label,
    });
  }

  function edit(item?: QueryKnowledgeItem) {
    form.value = item ? JSON.parse(JSON.stringify(item)) : blank();
    editorVisible.value = true;
  }
  function setScope(value: string | string[]) {
    if (Array.isArray(value)) form.value.datasourceIds = value;
    else form.value.datasourceIds = value ? [value] : [];
  }
  async function save() {
    try {
      await formRef.value?.validate();
    } catch {
      return;
    }
    saving.value = true;
    try {
      await saveQueryKnowledge(form.value);
      editorVisible.value = false;
      message.success('保存成功');
      await load();
    } catch {
      /* API error is displayed by the shared client. */
    } finally {
      saving.value = false;
    }
  }
  async function toggle(item: QueryKnowledgeItem, enabled: boolean) {
    try {
      await enableQueryKnowledge(item, enabled);
      await load();
    } catch {
      /* Keep the last saved switch state. */
    }
  }
  function confirmDelete(items: QueryKnowledgeItem[]) {
    dialog.warning({
      title: '删除知识',
      content: `确定删除选中的 ${items.length} 条知识吗？`,
      positiveText: '删除',
      negativeText: '取消',
      onPositiveClick: async () => {
        await deleteQueryKnowledge(items);
        message.success('删除成功');
        await load();
      },
    });
  }
  async function copy(text: string) {
    try {
      await navigator.clipboard.writeText(text);
      message.success('已复制');
    } catch {
      message.error('复制失败');
    }
  }
  async function exportFile(template: boolean) {
    exporting.value = true;
    try {
      const response = await exportQueryKnowledge(kind.value, template ? [] : checked.value.map(String), template);
      const blob = response.data;
      if (!(blob instanceof Blob)) throw new Error('导出接口未返回有效文件');
      const url = URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = url;
      link.download = `${title.value}${template ? '-导入模板' : ''}.xlsx`;
      link.click();
      setTimeout(() => URL.revokeObjectURL(url), 1000);
    } catch (e) {
      message.error(e instanceof Error ? e.message : '导出失败');
    } finally {
      exporting.value = false;
    }
  }
  async function importFile(options: UploadCustomRequestOptions) {
    const { file } = options.file;
    if (!file || file.size > 5 * 1024 * 1024) {
      message.error('文件不能超过 5MB');
      options.onError();
      return;
    }
    importing.value = true;
    try {
      const result = await importQueryKnowledge(kind.value, file);
      options.onFinish();
      importVisible.value = false;
      message.success(`已导入 ${result.imported} 条知识`);
      await load();
    } catch {
      options.onError();
    } finally {
      importing.value = false;
    }
  }
  function openTest() {
    testResult.value = null;
    testVisible.value = true;
  }
  async function test() {
    if (!testQuestion.value.trim()) {
      message.warning('请输入问题');
      return;
    }
    testing.value = true;
    testResult.value = null;
    try {
      testResult.value = await testQueryKnowledge(testQuestion.value.trim(), testSource.value);
    } catch {
      /* API error is displayed by the shared client. */
    } finally {
      testing.value = false;
    }
  }
  const columns = computed<DataTableColumns<QueryKnowledgeItem>>(() => [
    { type: 'selection' },
    {
      title: isTerm.value ? '术语名称' : '问题描述',
      key: 'name',
      width: 245,
      ellipsis: { tooltip: true },
      render: (r) =>
        h(NButton, { text: true, type: 'primary', onClick: () => showDetail(r) }, { default: () => r.name }),
    },
    ...(isTerm.value
      ? [
          {
            title: '同义词',
            key: 'synonyms',
            width: 180,
            ellipsis: { tooltip: true },
            render: (r: QueryKnowledgeItem) => r.synonyms.join('、') || '-',
          },
        ]
      : []),
    { title: isTerm.value ? '术语描述' : '示例 SQL', key: 'content', minWidth: 240, ellipsis: { tooltip: true } },
    { title: '生效数据源', key: 'datasourceIds', width: 190, ellipsis: { tooltip: true }, render: scopeLabel },
    {
      title: '语义索引',
      key: 'embeddingStatus',
      width: 100,
      render: (r) =>
        ({ READY: '已就绪', PENDING: '待重建', DISABLED: '未启用' }[r.embeddingStatus || 'DISABLED'] || '待重建'),
    },
    {
      title: '状态',
      key: 'enabled',
      width: 85,
      render: (r) =>
        h(NSwitch, { value: r.enabled, size: 'small', onUpdateValue: (enabled: boolean) => toggle(r, enabled) }),
    },
    {
      title: '操作',
      key: 'actions',
      width: 88,
      fixed: 'right',
      render: (r) =>
        h('div', { class: 'row-actions' }, [
          action('编辑', CreateOutline, () => edit(r)),
          ...(canDelete.value ? [action('删除', TrashOutline, () => confirmDelete([r]))] : []),
        ]),
    },
  ]);

  watch([keyword, sourceFilter, statusFilter], () => {
    pagination.page = 1;
  });
  watch(kind, () => {
    keyword.value = '';
    sourceFilter.value = null;
    statusFilter.value = null;
    pagination.page = 1;
    editorVisible.value = false;
    detailVisible.value = false;
    testVisible.value = false;
    importVisible.value = false;
    load();
  });
  onMounted(async () => {
    load();
    try {
      const data = await getAiAgentDatasources();
      sources.value = Array.isArray(data) ? data : [];
    } catch {
      message.error('数据源列表加载失败');
    }
  });
</script>

<style scoped lang="less">
  .query-knowledge-page {
    padding: 24px;
    min-height: 100%;
    background: var(--text-n10, #ffffff);
  }
  .page-heading,
  .heading-title,
  .commands,
  .filter-bar,
  .selection-bar,
  .content-heading,
  .row-actions {
    display: flex;
    align-items: center;
  }
  .page-heading {
    justify-content: space-between;
    gap: 16px;
    padding-bottom: 24px;
  }
  .heading-title {
    gap: 12px;
    min-width: 0;
  }
  h1 {
    margin: 0;
    font-size: 22px;
    font-weight: 600;
  }
  .heading-title span {
    font-size: 14px;
    color: var(--text-n4);
  }
  .commands {
    gap: 8px;
    flex-wrap: wrap;
  }
  .filter-bar {
    gap: 12px;
    margin-bottom: 16px;
    flex-wrap: wrap;
  }
  .keyword-filter {
    flex: 1;
    min-width: 220px;
  }
  .source-filter {
    width: 200px;
  }
  .status-filter {
    width: 120px;
  }
  .selection-bar {
    padding-bottom: 12px;
    min-height: 40px;
    gap: 12px;
  }
  .load-error {
    margin-bottom: 16px;
  }
  .scope-field {
    display: grid;
    gap: 16px;
    width: 100%;
  }
  .sql-input :deep(textarea) {
    font-family: Consolas, Monaco, monospace;
  }
  :deep(.n-data-table td .n-button) {
    max-width: 100%;
  }
  :deep(.n-data-table td .n-button__content) {
    display: block;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  :deep(.row-actions) {
    display: flex;
    gap: 4px;
  }
  .detail-meta {
    display: grid;
    grid-template-columns: 100px minmax(0, 1fr);
    gap: 14px;
    margin: 0 0 28px;
    font-size: 14px;
  }
  .detail-meta dt {
    color: var(--text-n4);
  }
  .detail-meta dd {
    margin: 0;
    overflow-wrap: anywhere;
  }
  .content-heading {
    justify-content: space-between;
  }
  h2,
  h3 {
    margin: 0;
    font-size: 15px;
    font-weight: 600;
  }
  .term-content {
    white-space: pre-wrap;
    overflow-wrap: anywhere;
    line-height: 1.8;
  }
  .sql-content {
    overflow: auto;
    padding: 16px;
    border: 1px solid #e3e7eb;
    border-radius: 4px;
    background: #f4f6f8;
    font: 13px/1.8 Consolas, Monaco, monospace;
  }
  .sql-content :deep(.hljs-keyword) {
    color: #087c9a;
  }
  .sql-content :deep(.hljs-string) {
    color: #a04439;
  }
  .sql-content :deep(.hljs-number) {
    color: #724aab;
  }
  .import-actions {
    display: flex;
    align-items: center;
    gap: 16px;
  }
  .match-entry {
    padding: 12px 0;
    border-bottom: 1px solid #e3e7eb;
    overflow-wrap: anywhere;
  }
  .match-entry p {
    margin: 8px 0 0;
    white-space: pre-wrap;
  }
  .match-entry pre {
    overflow: auto;
    font: 12px/1.7 Consolas, monospace;
  }
  @media (max-width: 650px) {
    .query-knowledge-page {
      padding: 16px;
    }
    .page-heading {
      align-items: flex-start;
      flex-direction: column;
    }
    .keyword-filter {
      flex-basis: 100%;
    }
    .source-filter {
      flex: 1;
      width: auto;
    }
  }
</style>

<style lang="less">
  .knowledge-drawer.n-drawer {
    max-width: calc(100vw - 16px);
  }
  .knowledge-modal.n-card {
    overflow-y: auto;
    width: 650px;
    max-width: calc(100vw - 32px);
    max-height: calc(100vh - 48px);
    border-radius: 6px;
  }
</style>
