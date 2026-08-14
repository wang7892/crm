<template>
  <n-drawer v-model:show="drawerVisible" :width="960">
    <n-drawer-content :title="drawerTitle" closable>
      <div class="review-summary">
        <n-tag type="default" :bordered="false">总数 {{ ruleStats.total }}</n-tag>
        <n-tag type="warning" :bordered="false">待审核 {{ ruleStats.pending }}</n-tag>
        <n-tag type="success" :bordered="false">已通过 {{ ruleStats.approved }}</n-tag>
        <n-tag type="default" :bordered="false">已拒绝 {{ ruleStats.rejected }}</n-tag>
        <n-tag type="error" :bordered="false">无效 {{ ruleStats.invalid }}</n-tag>
      </div>

      <n-alert v-if="isDocumentReadOnly" type="info" class="parse-error">
        该文档已发布，当前规则为只读状态。如需调整，请先在文档列表撤回。
      </n-alert>

      <div class="review-toolbar">
        <n-input
          v-model:value="query.keyword"
          clearable
          placeholder="搜索规范术语、同义词"
          @keyup.enter="searchRules"
        />
        <n-select v-model:value="query.reviewStatus" clearable :options="reviewStatusOptions" placeholder="审核状态" />
        <n-button :loading="loading" @click="searchRules">查询</n-button>
        <n-button :disabled="isDocumentReadOnly || !checkedRowKeys.length" @click="openBatchReview('APPROVED')">
          批量通过
        </n-button>
        <n-button :disabled="isDocumentReadOnly || !checkedRowKeys.length" @click="openBatchReview('REJECTED')">
          批量拒绝
        </n-button>
      </div>

      <n-alert v-if="document?.parseError" type="error" class="parse-error">
        {{ document.parseError }}
      </n-alert>

      <n-data-table
        remote
        :columns="columns"
        :data="rows"
        :loading="loading"
        :pagination="pagination"
        :row-key="ruleRowKey"
        :checked-row-keys="checkedRowKeys"
        @update:checked-row-keys="updateCheckedRowKeys"
        @update:page="updatePage"
        @update:page-size="updatePageSize"
      />
    </n-drawer-content>
  </n-drawer>

  <n-modal v-model:show="editModalVisible" preset="card" title="编辑语义规则" class="rule-modal">
    <n-form label-placement="top">
      <n-grid :cols="2" :x-gap="12">
        <n-form-item-gi label="规范术语" required>
          <n-input v-model:value="editForm.canonicalTerm" maxlength="64" show-count />
        </n-form-item-gi>
        <n-form-item-gi label="优先级">
          <n-input-number v-model:value="editForm.priority" :min="0" :max="1000" class="full-width" />
        </n-form-item-gi>
      </n-grid>

      <n-form-item label="同义词">
        <n-dynamic-tags v-model:value="editForm.aliases" :max="20" />
      </n-form-item>

      <n-form-item label="定义">
        <n-input
          v-model:value="editForm.definition"
          type="textarea"
          maxlength="500"
          show-count
          :autosize="{ minRows: 2, maxRows: 4 }"
        />
      </n-form-item>

      <n-grid :cols="2" :x-gap="12">
        <n-form-item-gi label="目标实体" required>
          <n-select
            v-model:value="editForm.mapping.entity"
            filterable
            :options="entitySelectOptions"
            @update:value="handleMappingEntityChange"
          />
        </n-form-item-gi>
        <n-form-item-gi label="目标字段" required>
          <n-select
            v-model:value="editForm.mapping.field"
            filterable
            :disabled="!editForm.mapping.entity"
            :options="fieldOptionsForEntity(editForm.mapping.entity)"
          />
        </n-form-item-gi>
      </n-grid>

      <n-grid :cols="2" :x-gap="12">
        <n-form-item-gi label="生效时间">
          <n-date-picker v-model:value="editForm.effectiveFrom" type="datetime" clearable class="full-width" />
        </n-form-item-gi>
        <n-form-item-gi label="失效时间">
          <n-date-picker v-model:value="editForm.effectiveTo" type="datetime" clearable class="full-width" />
        </n-form-item-gi>
      </n-grid>

      <div class="form-section-title">
        <span>禁止映射</span>
        <n-button
          size="small"
          secondary
          :disabled="editForm.forbiddenMappings.length >= 10"
          @click="addForbiddenMapping"
        >
          添加
        </n-button>
      </div>
      <n-empty v-if="!editForm.forbiddenMappings.length" size="small" description="没有禁止映射" />
      <div v-else class="editable-list">
        <div v-for="(item, index) in editForm.forbiddenMappings" :key="index" class="editable-row forbidden-row">
          <n-select
            v-model:value="item.entity"
            filterable
            :options="entitySelectOptions"
            placeholder="禁止实体"
            @update:value="handleForbiddenEntityChange(index)"
          />
          <n-select
            v-model:value="item.field"
            filterable
            clearable
            :disabled="!item.entity"
            :options="forbiddenFieldOptions(item.entity)"
            placeholder="整个实体"
          />
          <n-input v-model:value="item.reason" maxlength="200" placeholder="原因（可选）" />
          <n-button size="small" quaternary type="error" @click="removeForbiddenMapping(index)">删除</n-button>
        </div>
      </div>

      <div class="form-section-title examples-title">
        <span>示例问题</span>
        <n-button size="small" secondary :disabled="editForm.examples.length >= 5" @click="addExample">添加</n-button>
      </div>
      <n-empty v-if="!editForm.examples.length" size="small" description="没有示例问题" />
      <div v-else class="editable-list">
        <div v-for="(item, index) in editForm.examples" :key="index" class="editable-row example-row">
          <n-input v-model:value="item.question" maxlength="200" placeholder="问题" />
          <n-select
            v-model:value="item.expectedEntity"
            filterable
            :options="entitySelectOptions"
            placeholder="预期实体"
            @update:value="handleExampleEntityChange(index)"
          />
          <n-select
            v-model:value="item.expectedField"
            filterable
            :disabled="!item.expectedEntity"
            :options="fieldOptionsForEntity(item.expectedEntity)"
            placeholder="预期字段"
          />
          <n-button size="small" quaternary type="error" @click="removeExample(index)">删除</n-button>
        </div>
      </div>

      <n-form-item label="来源原文" class="source-form-item">
        <n-input :value="editingRule?.rule.source.quote || ''" type="textarea" :rows="3" readonly />
      </n-form-item>
      <div class="source-meta">
        <span v-if="editingRule?.rule.source.pageNo">第 {{ editingRule.rule.source.pageNo }} 页</span>
        <span v-if="editingRule?.rule.source.sectionPath">{{ editingRule.rule.source.sectionPath }}</span>
        <span v-if="editingRule?.rule.extraction?.confidence != null">
          抽取置信度 {{ formatConfidence(editingRule.rule.extraction.confidence) }}
        </span>
      </div>

      <n-alert v-if="editingRule?.rule.validationErrors?.length" type="error" class="validation-errors">
        <div v-for="error in editingRule.rule.validationErrors" :key="error">{{ error }}</div>
      </n-alert>
    </n-form>

    <template #footer>
      <div class="modal-footer">
        <n-button @click="editModalVisible = false">取消</n-button>
        <n-button type="primary" :loading="saving" @click="saveRule">保存</n-button>
      </div>
    </template>
  </n-modal>

  <n-modal v-model:show="reviewModalVisible" preset="card" :title="reviewModalTitle" class="review-modal">
    <n-form label-placement="top">
      <n-form-item label="审核备注">
        <n-input
          v-model:value="reviewComment"
          type="textarea"
          maxlength="500"
          show-count
          :autosize="{ minRows: 3, maxRows: 6 }"
        />
      </n-form-item>
    </n-form>
    <template #footer>
      <div class="modal-footer">
        <n-button @click="reviewModalVisible = false">取消</n-button>
        <n-button :type="reviewAction === 'APPROVED' ? 'primary' : 'error'" :loading="reviewing" @click="submitReview">
          {{ reviewAction === 'APPROVED' ? '确认通过' : '确认拒绝' }}
        </n-button>
      </div>
    </template>
  </n-modal>
</template>

<script setup lang="ts">
  /* eslint-disable no-use-before-define */
  import {
    type DataTableColumns,
    type DataTableRowKey,
    NAlert,
    NButton,
    NDataTable,
    NDatePicker,
    NDrawer,
    NDrawerContent,
    NDynamicTags,
    NEmpty,
    NForm,
    NFormItem,
    NFormItemGi,
    NGrid,
    NInput,
    NInputNumber,
    NModal,
    NSelect,
    NTag,
    type SelectOption,
    useMessage,
  } from 'naive-ui';

  import type {
    AiKnowledgeDocumentItem,
    AiSemanticForbiddenMapping,
    AiSemanticRuleItem,
    AiSemanticRuleReviewStatus,
    AiSemanticRuleSaveParams,
    AiSemanticRuleStats,
    AiSemanticSchemaEntityOption,
  } from '@lib/shared/api/modules/aiAgent';

  import {
    batchReviewAiSemanticRules,
    getAiSemanticRulePage,
    getAiSemanticRuleSchemaOptions,
    reviewAiSemanticRule,
    saveAiSemanticRule,
  } from '@/api/modules';

  const props = defineProps<{
    show: boolean;
    document: AiKnowledgeDocumentItem | null;
  }>();

  const emit = defineEmits<{
    (event: 'update:show', value: boolean): void;
    (event: 'updated'): void;
  }>();

  const message = useMessage();

  const drawerVisible = computed({
    get: () => props.show,
    set: (value: boolean) => emit('update:show', value),
  });
  const drawerTitle = computed(() => (props.document ? `审核规则 - ${props.document.name}` : '审核语义规则'));
  const isDocumentReadOnly = computed(() => Boolean(props.document?.enabled));
  const ruleStats = computed<AiSemanticRuleStats>(
    () => props.document?.ruleStats || { total: total.value, pending: 0, approved: 0, rejected: 0, invalid: 0 }
  );

  const reviewStatusOptions = [
    { label: '待审核', value: 'PENDING' },
    { label: '已通过', value: 'APPROVED' },
    { label: '已拒绝', value: 'REJECTED' },
    { label: '无效', value: 'INVALID' },
  ];

  const query = reactive({
    current: 1,
    pageSize: 20,
    keyword: '',
    reviewStatus: null as AiSemanticRuleReviewStatus | null,
  });
  const rows = ref<AiSemanticRuleItem[]>([]);
  const total = ref(0);
  const loading = ref(false);
  const checkedRowKeys = ref<DataTableRowKey[]>([]);
  const schemaOptions = ref<AiSemanticSchemaEntityOption[]>([]);

  const editModalVisible = ref(false);
  const editingRule = ref<AiSemanticRuleItem | null>(null);
  const saving = ref(false);
  const editForm = reactive<AiSemanticRuleSaveParams>({
    canonicalTerm: '',
    aliases: [],
    definition: '',
    mapping: { entity: '', field: '' },
    forbiddenMappings: [],
    examples: [],
    priority: 100,
    effectiveFrom: null,
    effectiveTo: null,
    expectedUpdateTime: 0,
  });

  const reviewModalVisible = ref(false);
  const reviewAction = ref<'APPROVED' | 'REJECTED'>('APPROVED');
  const reviewComment = ref('');
  const reviewTarget = ref<AiSemanticRuleItem | null>(null);
  const batchReview = ref(false);
  const reviewing = ref(false);

  const pagination = computed(() => ({
    page: query.current,
    pageSize: query.pageSize,
    itemCount: total.value,
    showSizePicker: true,
    pageSizes: [10, 20, 50],
  }));

  const entitySelectOptions = computed<SelectOption[]>(() =>
    schemaOptions.value.map((item) => ({
      label: `${item.label} (${item.key})`,
      value: item.key,
    }))
  );

  const columns: DataTableColumns<AiSemanticRuleItem> = [
    {
      type: 'selection',
      disabled: (row) => isDocumentReadOnly.value || row.rule.review.status === 'INVALID',
    },
    {
      title: '规范术语',
      key: 'canonicalTerm',
      minWidth: 130,
      render(row) {
        return h('div', { class: 'term-cell' }, [
          h('strong', row.rule.canonicalTerm),
          row.rule.aliases?.length ? h('span', row.rule.aliases.join('、')) : null,
        ]);
      },
    },
    {
      title: '目标映射',
      key: 'mapping',
      minWidth: 210,
      render(row) {
        return `${schemaEntityLabel(row.rule.mapping.entity)} / ${schemaFieldLabel(
          row.rule.mapping.entity,
          row.rule.mapping.field
        )}`;
      },
    },
    {
      title: '来源',
      key: 'source',
      minWidth: 230,
      ellipsis: { tooltip: true },
      render(row) {
        const position = [
          row.rule.source.pageNo ? `第 ${row.rule.source.pageNo} 页` : '',
          row.rule.source.sectionPath || '',
        ]
          .filter(Boolean)
          .join(' / ');
        return position ? `${position}：${row.rule.source.quote}` : row.rule.source.quote;
      },
    },
    {
      title: '置信度',
      key: 'confidence',
      width: 90,
      render(row) {
        return formatConfidence(row.rule.extraction?.confidence);
      },
    },
    {
      title: '状态',
      key: 'reviewStatus',
      width: 100,
      render(row) {
        return h(
          NTag,
          { type: reviewStatusTagType(row.rule.review.status), bordered: false },
          { default: () => reviewStatusText(row.rule.review.status) }
        );
      },
    },
    {
      title: '操作',
      key: 'actions',
      width: 190,
      fixed: 'right',
      render(row) {
        return h('div', { class: 'rule-actions' }, [
          h(
            NButton,
            { size: 'small', quaternary: true, disabled: isDocumentReadOnly.value, onClick: () => openEdit(row) },
            { default: () => '编辑' }
          ),
          h(
            NButton,
            {
              size: 'small',
              quaternary: true,
              type: 'primary',
              disabled:
                isDocumentReadOnly.value ||
                row.rule.review.status === 'INVALID' ||
                Boolean(row.rule.validationErrors?.length),
              onClick: () => openSingleReview(row, 'APPROVED'),
            },
            { default: () => '通过' }
          ),
          h(
            NButton,
            {
              size: 'small',
              quaternary: true,
              type: 'error',
              disabled: isDocumentReadOnly.value,
              onClick: () => openSingleReview(row, 'REJECTED'),
            },
            { default: () => '拒绝' }
          ),
        ]);
      },
    },
  ];

  watch(
    () => [props.show, props.document?.id] as const,
    async ([show, documentId]) => {
      if (!show || !documentId) return;
      query.current = 1;
      query.keyword = '';
      query.reviewStatus = null;
      checkedRowKeys.value = [];
      await Promise.all([loadSchemaOptions(), loadRules()]);
    }
  );

  async function loadSchemaOptions() {
    if (schemaOptions.value.length) return;
    const result = await getAiSemanticRuleSchemaOptions();
    schemaOptions.value = result.entities || [];
  }

  async function loadRules() {
    if (!props.document?.id) return;
    loading.value = true;
    try {
      const result = await getAiSemanticRulePage({
        documentId: props.document.id,
        current: query.current,
        pageSize: query.pageSize,
        keyword: query.keyword.trim() || undefined,
        reviewStatus: query.reviewStatus || undefined,
      });
      rows.value = result.list || [];
      total.value = result.total || 0;
      checkedRowKeys.value = checkedRowKeys.value.filter((key) => rows.value.some((row) => row.chunkId === key));
    } finally {
      loading.value = false;
    }
  }

  function searchRules() {
    query.current = 1;
    loadRules();
  }

  function updatePage(page: number) {
    query.current = page;
    loadRules();
  }

  function updatePageSize(pageSize: number) {
    query.pageSize = pageSize;
    query.current = 1;
    loadRules();
  }

  function updateCheckedRowKeys(keys: DataTableRowKey[]) {
    checkedRowKeys.value = keys;
  }

  function ruleRowKey(row: AiSemanticRuleItem) {
    return row.chunkId;
  }

  function openEdit(row: AiSemanticRuleItem) {
    if (isDocumentReadOnly.value) return;
    editingRule.value = row;
    editForm.canonicalTerm = row.rule.canonicalTerm;
    editForm.aliases = [...(row.rule.aliases || [])];
    editForm.definition = row.rule.definition || '';
    editForm.mapping = { ...row.rule.mapping };
    editForm.forbiddenMappings = (row.rule.forbiddenMappings || []).map((item) => ({ ...item }));
    editForm.examples = (row.rule.examples || []).map((item) => ({ ...item }));
    editForm.priority = row.rule.priority ?? 100;
    editForm.effectiveFrom = row.rule.effectiveFrom ?? null;
    editForm.effectiveTo = row.rule.effectiveTo ?? null;
    editForm.expectedUpdateTime = row.updateTime;
    editModalVisible.value = true;
  }

  async function saveRule() {
    if (!editingRule.value) return;
    const canonicalTerm = editForm.canonicalTerm.trim();
    if (!canonicalTerm || !editForm.mapping.entity || !editForm.mapping.field) {
      message.warning('请填写规范术语、目标实体和目标字段');
      return;
    }
    if (editForm.aliases.some((alias) => !alias.trim())) {
      message.warning('同义词不能为空');
      return;
    }
    if (editForm.forbiddenMappings.some((item) => !item.entity)) {
      message.warning('请选择禁止映射的实体');
      return;
    }
    if (editForm.examples.some((item) => !item.question.trim() || !item.expectedEntity || !item.expectedField)) {
      message.warning('请完整填写示例问题及预期实体、字段');
      return;
    }
    if (
      editForm.effectiveFrom != null &&
      editForm.effectiveTo != null &&
      editForm.effectiveFrom >= editForm.effectiveTo
    ) {
      message.warning('失效时间必须晚于生效时间');
      return;
    }

    saving.value = true;
    try {
      await saveAiSemanticRule(editingRule.value.chunkId, {
        canonicalTerm,
        aliases: editForm.aliases.map((alias) => alias.trim()).filter(Boolean),
        definition: editForm.definition?.trim() || null,
        mapping: { entity: editForm.mapping.entity, field: editForm.mapping.field },
        forbiddenMappings: editForm.forbiddenMappings.map((item) => ({
          entity: item.entity,
          field: item.field || null,
          reason: item.reason?.trim() || null,
        })),
        examples: editForm.examples.map((item) => ({ ...item, question: item.question.trim() })),
        priority: editForm.priority,
        effectiveFrom: editForm.effectiveFrom ?? null,
        effectiveTo: editForm.effectiveTo ?? null,
        expectedUpdateTime: editForm.expectedUpdateTime,
      });
      message.success('规则已保存');
      editModalVisible.value = false;
      await refreshAfterMutation();
    } finally {
      saving.value = false;
    }
  }

  function openSingleReview(row: AiSemanticRuleItem, status: 'APPROVED' | 'REJECTED') {
    if (isDocumentReadOnly.value) return;
    reviewTarget.value = row;
    batchReview.value = false;
    reviewAction.value = status;
    reviewComment.value = row.rule.review.comment || '';
    reviewModalVisible.value = true;
  }

  function openBatchReview(status: 'APPROVED' | 'REJECTED') {
    if (isDocumentReadOnly.value || !checkedRowKeys.value.length) return;
    reviewTarget.value = null;
    batchReview.value = true;
    reviewAction.value = status;
    reviewComment.value = '';
    reviewModalVisible.value = true;
  }

  const reviewModalTitle = computed(() => {
    const actionText = reviewAction.value === 'APPROVED' ? '通过' : '拒绝';
    return batchReview.value ? `批量${actionText}规则` : `${actionText}规则`;
  });

  async function submitReview() {
    reviewing.value = true;
    try {
      if (batchReview.value) {
        const selectedRows = rows.value.filter((row) => checkedRowKeys.value.includes(row.chunkId));
        await batchReviewAiSemanticRules({
          items: selectedRows.map((row) => ({
            chunkId: row.chunkId,
            status: reviewAction.value,
            comment: reviewComment.value.trim() || undefined,
            expectedUpdateTime: row.updateTime,
          })),
        });
      } else if (reviewTarget.value) {
        await reviewAiSemanticRule(reviewTarget.value.chunkId, {
          status: reviewAction.value,
          comment: reviewComment.value.trim() || undefined,
          expectedUpdateTime: reviewTarget.value.updateTime,
        });
      }
      message.success(reviewAction.value === 'APPROVED' ? '审核通过' : '已拒绝规则');
      reviewModalVisible.value = false;
      checkedRowKeys.value = [];
      await refreshAfterMutation();
    } finally {
      reviewing.value = false;
    }
  }

  async function refreshAfterMutation() {
    await loadRules();
    emit('updated');
  }

  function handleMappingEntityChange(entity: string) {
    if (!fieldExists(entity, editForm.mapping.field)) editForm.mapping.field = '';
  }

  function addForbiddenMapping() {
    if (editForm.forbiddenMappings.length >= 10) return;
    editForm.forbiddenMappings.push({ entity: '', field: null, reason: '' });
  }

  function removeForbiddenMapping(index: number) {
    editForm.forbiddenMappings.splice(index, 1);
  }

  function handleForbiddenEntityChange(index: number) {
    const item = editForm.forbiddenMappings[index];
    if (item && item.field && !fieldExists(item.entity, item.field)) item.field = null;
  }

  function addExample() {
    if (editForm.examples.length >= 5) return;
    editForm.examples.push({ question: '', expectedEntity: '', expectedField: '' });
  }

  function removeExample(index: number) {
    editForm.examples.splice(index, 1);
  }

  function handleExampleEntityChange(index: number) {
    const item = editForm.examples[index];
    if (item && !fieldExists(item.expectedEntity, item.expectedField)) item.expectedField = '';
  }

  function fieldOptionsForEntity(entity: string): SelectOption[] {
    const option = schemaOptions.value.find((item) => item.key === entity);
    return (option?.fields || []).map((field) => ({ label: `${field.label} (${field.key})`, value: field.key }));
  }

  function forbiddenFieldOptions(entity: string): SelectOption[] {
    return [{ label: '整个实体', value: '' }, ...fieldOptionsForEntity(entity)];
  }

  function fieldExists(entity: string, field?: string | null) {
    if (!field) return false;
    return schemaOptions.value.some(
      (item) => item.key === entity && item.fields.some((candidate) => candidate.key === field)
    );
  }

  function schemaEntityLabel(entity: string) {
    const option = schemaOptions.value.find((item) => item.key === entity);
    return option ? `${option.label} (${entity})` : entity;
  }

  function schemaFieldLabel(entity: string, field: string) {
    const option = schemaOptions.value.find((item) => item.key === entity);
    const fieldOption = option?.fields.find((item) => item.key === field);
    return fieldOption ? `${fieldOption.label} (${field})` : field;
  }

  function reviewStatusText(status: AiSemanticRuleReviewStatus) {
    return reviewStatusOptions.find((item) => item.value === status)?.label || status;
  }

  function reviewStatusTagType(status: AiSemanticRuleReviewStatus) {
    if (status === 'APPROVED') return 'success';
    if (status === 'PENDING') return 'warning';
    if (status === 'INVALID') return 'error';
    return 'default';
  }

  function formatConfidence(confidence?: number | null) {
    if (confidence == null) return '-';
    return `${(confidence * 100).toFixed(0)}%`;
  }
</script>

<style lang="less" scoped>
  .review-summary,
  .review-toolbar,
  .modal-footer,
  .source-meta,
  .form-section-title {
    display: flex;
    align-items: center;
  }
  .review-summary {
    flex-wrap: wrap;
    margin-bottom: 12px;
    gap: 8px;
  }
  .review-toolbar {
    margin-bottom: 12px;
    gap: 8px;
  }
  .review-toolbar > :first-child {
    min-width: 220px;
    flex: 1;
  }
  .review-toolbar > :nth-child(2) {
    width: 140px;
  }
  .parse-error,
  .validation-errors {
    margin-bottom: 12px;
  }
  .full-width {
    width: 100%;
  }
  .form-section-title {
    justify-content: space-between;
    margin: 4px 0 8px;
    font-weight: 500;
    color: var(--text-n1);
  }
  .examples-title,
  .source-form-item {
    margin-top: 16px;
  }
  .editable-list {
    display: flex;
    flex-direction: column;
    gap: 8px;
  }
  .editable-row {
    display: grid;
    align-items: center;
    gap: 8px;
  }
  .forbidden-row {
    grid-template-columns: minmax(150px, 0.8fr) minmax(150px, 0.8fr) minmax(220px, 1.2fr) auto;
  }
  .example-row {
    grid-template-columns: minmax(240px, 1.3fr) minmax(150px, 0.8fr) minmax(150px, 0.8fr) auto;
  }
  .source-meta {
    flex-wrap: wrap;
    margin-top: -12px;
    margin-bottom: 12px;
    color: var(--text-n3);
    gap: 12px;
  }
  .modal-footer {
    justify-content: flex-end;
    gap: 8px;
  }
  .rule-modal {
    width: min(880px, calc(100vw - 48px));
  }
  .review-modal {
    width: min(520px, calc(100vw - 48px));
  }
  :deep(.term-cell) {
    display: flex;
    flex-direction: column;
    gap: 3px;
  }
  :deep(.term-cell span) {
    font-size: 12px;
    color: var(--text-n3);
  }
  :deep(.rule-actions) {
    display: flex;
    gap: 2px;
  }

  @media (max-width: 900px) {
    .review-toolbar,
    .editable-row {
      display: grid;
      grid-template-columns: 1fr;
    }
    .review-toolbar > :nth-child(2) {
      width: 100%;
    }
  }
</style>
