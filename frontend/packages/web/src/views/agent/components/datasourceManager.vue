<template>
  <section class="datasource-page">
    <header class="datasource-toolbar">
      <h1>数据源</h1>
      <div class="datasource-tools">
        <n-input v-model:value="search" clearable placeholder="搜索数据源" class="search-input">
          <template #prefix
            ><n-icon><SearchOutline /></n-icon
          ></template>
        </n-input>
        <n-select v-model:value="typeFilter" :options="filterOptions" class="type-filter" />
        <n-button quaternary aria-label="刷新数据源" title="刷新数据源" :loading="loading" @click="emit('refresh')">
          <template #icon
            ><n-icon><RefreshOutline /></n-icon
          ></template>
        </n-button>
        <n-button v-if="canManage" type="primary" @click="openNew">
          <template #icon
            ><n-icon><AddOutline /></n-icon></template
          >新建数据源
        </n-button>
        <n-button quaternary title="返回聊天" aria-label="返回聊天" @click="emit('close')">
          <template #icon
            ><n-icon><CloseOutline /></n-icon
          ></template>
        </n-button>
      </div>
    </header>
    <n-spin :show="loading" class="datasource-body">
      <n-result v-if="error" status="error" title="数据源加载失败" :description="error">
        <template #footer><n-button @click="emit('refresh')">重试</n-button></template>
      </n-result>
      <div v-else class="source-grid">
        <article v-for="source in filteredSources" :key="source.id" class="source-card">
          <div class="source-heading">
            <CrmIcon
              :type="source.type === 'FILE' ? 'iconicon_file-excel_colorful' : 'iconicon_file-sql_colorful'"
              :size="32"
            />
            <div class="source-title"
              ><h2>{{ source.name }}</h2
              ><span>{{ typeLabel(source.type) }}</span></div
            >
          </div>
          <div class="source-location">{{
            source.type === 'FILE' ? source.database : `${source.host}:${source.port} / ${source.database}`
          }}</div>
          <div class="source-bottom">
            <button
              class="table-count"
              type="button"
              title="选择数据表"
              aria-label="选择数据表"
              @click="openEdit(source, true)"
            >
              <n-icon><GridOutline /></n-icon>{{ source.selectedTables.length }}/{{ source.tableCount }}
            </button>
            <div class="source-actions">
              <n-button
                quaternary
                size="small"
                title="使用此数据源"
                aria-label="使用此数据源"
                :disabled="!source.selectedTables.length"
                @click="emit('query', source.id)"
              >
                <template #icon
                  ><n-icon><ChatbubbleOutline /></n-icon
                ></template>
              </n-button>
              <n-button
                v-if="canManage"
                quaternary
                size="small"
                title="编辑连接"
                aria-label="编辑连接"
                @click="openEdit(source, false)"
              >
                <template #icon
                  ><n-icon><CreateOutline /></n-icon
                ></template>
              </n-button>
              <n-button
                v-if="canManage"
                quaternary
                size="small"
                title="删除数据源"
                aria-label="删除数据源"
                @click="remove(source)"
              >
                <template #icon
                  ><n-icon><TrashOutline /></n-icon
                ></template>
              </n-button>
            </div>
          </div>
        </article>
      </div>
      <n-empty
        v-if="!error && !loading && !filteredSources.length"
        class="source-empty"
        :description="sources.length ? '没有匹配的数据源' : '暂无外部数据源'"
      />
    </n-spin>

    <n-modal
      :show="showWizard"
      preset="card"
      :title="form.id ? '编辑数据源' : '新建数据源'"
      :style="{ width: 'min(980px, 96vw)' }"
      :mask-closable="false"
      @update:show="closeWizard"
    >
      <n-steps :current="step" size="small">
        <n-step title="选择数据源" /><n-step title="配置信息" /><n-step title="选择数据表" />
      </n-steps>
      <div class="wizard-content">
        <div v-if="step === 1" class="type-grid">
          <button
            v-for="type in types"
            :key="type.value"
            type="button"
            class="source-type"
            :class="{ selected: form.type === type.value }"
            @click="chooseType(type.value)"
          >
            <CrmIcon
              :type="type.value === 'FILE' ? 'iconicon_file-excel_colorful' : 'iconicon_file-sql_colorful'"
              :size="36"
            />
            <span>{{ type.label }}</span>
            <n-icon v-if="form.type === type.value" class="type-check"><CheckmarkCircle /></n-icon>
          </button>
        </div>
        <n-form v-else-if="step === 2" label-placement="top" class="connection-form">
          <n-form-item label="数据源名称" required class="full-field"
            ><n-input v-model:value="form.name" :maxlength="100" placeholder="数据源名称"
          /></n-form-item>
          <template v-if="form.type !== 'FILE'">
            <n-form-item label="主机地址" required
              ><n-input v-model:value="form.host" placeholder="IP 地址或域名"
            /></n-form-item>
            <n-form-item label="端口" required
              ><n-input-number v-model:value="form.port" :min="1" :max="65535" :show-button="false" class="full-width"
            /></n-form-item>
            <n-form-item :label="form.type === 'ORACLE' ? '服务名（Service Name）' : '数据库名称'" required
              ><n-input v-model:value="form.database"
            /></n-form-item>
            <n-form-item v-if="form.type !== 'MYSQL'" label="Schema"
              ><n-input v-model:value="form.schema" :placeholder="schemaPlaceholder"
            /></n-form-item>
            <n-form-item label="用户名" required
              ><n-input v-model:value="form.username" autocomplete="off"
            /></n-form-item>
            <n-form-item label="密码"
              ><n-input
                v-model:value="form.password"
                type="password"
                show-password-on="click"
                autocomplete="new-password"
                :placeholder="form.id ? '已设置，留空保持不变' : '密码'"
            /></n-form-item>
            <n-form-item label="SSL / TLS"><n-switch v-model:value="form.ssl" /></n-form-item>
          </template>
          <template v-else>
            <n-form-item label="文件" required class="full-field">
              <div v-if="form.id" class="uploaded-file"
                ><n-icon><DocumentOutline /></n-icon>{{ form.database }}</div
              >
              <input
                v-else
                ref="fileInput"
                type="file"
                accept=".xlsx,.xls,.csv"
                aria-label="Excel 或 CSV 文件"
                @change="onFileChange"
              />
            </n-form-item>
            <n-form-item v-if="!form.id && selectedFile?.name.toLowerCase().endsWith('.csv')" label="CSV 编码">
              <n-select
                v-model:value="encoding"
                :options="[
                  { label: 'UTF-8', value: 'UTF-8' },
                  { label: 'GB18030 / GBK', value: 'GB18030' },
                ]"
              />
            </n-form-item>
          </template>
        </n-form>
        <div v-else class="table-stage">
          <div class="table-toolbar">
            <n-input v-model:value="tableSearch" clearable placeholder="搜索数据表" class="table-search"
              ><template #prefix
                ><n-icon><SearchOutline /></n-icon></template
            ></n-input>
            <n-button v-if="canManage" text @click="selectVisible">选择全部</n-button>
            <n-button v-if="canManage" text @click="form.selectedTables = []">清空</n-button>
            <span class="selection-count">已选 {{ form.selectedTables.length }}/{{ tables.length }}</span>
          </div>
          <div class="table-layout">
            <n-scrollbar class="table-list">
              <n-checkbox-group v-model:value="form.selectedTables">
                <div
                  v-for="table in filteredTables"
                  :key="table.name"
                  class="table-row"
                  :class="{ focused: previewTable?.name === table.name }"
                >
                  <n-checkbox :value="table.name" :disabled="!canManage"
                    ><span class="table-name">{{ table.name }}</span
                    ><span class="table-comment">{{ table.comment }}</span></n-checkbox
                  >
                  <n-button
                    quaternary
                    size="tiny"
                    title="查看字段"
                    :aria-label="`查看 ${table.name} 的字段`"
                    @click="previewName = table.name"
                    ><template #icon
                      ><n-icon><ListOutline /></n-icon></template
                  ></n-button>
                </div>
              </n-checkbox-group>
              <n-empty v-if="!filteredTables.length" class="table-empty" description="没有匹配的数据表" />
            </n-scrollbar>
            <div class="field-preview">
              <h3>{{ previewTable?.name || '字段' }}</h3>
              <n-input
                v-if="previewTable"
                v-model:value="previewTable.comment"
                :disabled="!canManage"
                :maxlength="2000"
                type="textarea"
                :autosize="{ minRows: 2, maxRows: 4 }"
                placeholder="表业务说明"
                aria-label="表业务说明"
              />
              <n-data-table
                :columns="columnDefs"
                :data="previewTable?.columns || []"
                :max-height="320"
                size="small"
                :scroll-x="400"
              />
            </div>
          </div>
          <div class="relationship-editor">
            <div class="relationship-heading"
              ><h3>表关系</h3
              ><n-space v-if="canManage"
                ><n-button size="small" @click="importRelations"
                  ><template #icon><RefreshOutline /></template>读取外键</n-button
                ><n-button size="small" @click="addRelation"
                  ><template #icon><AddOutline /></template>添加关系</n-button
                ></n-space
              ></div
            >
            <div v-for="(relation, index) in form.relations" :key="index" class="relationship-row">
              <n-select
                v-model:value="relation.sourceTable"
                :options="relationTables"
                filterable
                placeholder="源表"
                :disabled="!canManage"
                @update:value="relation.sourceColumn = ''"
              />
              <n-select
                v-model:value="relation.sourceColumn"
                :options="relationColumns(relation.sourceTable)"
                filterable
                placeholder="源字段"
                :disabled="!canManage"
              />
              <span>=</span>
              <n-select
                v-model:value="relation.targetTable"
                :options="relationTables"
                filterable
                placeholder="目标表"
                :disabled="!canManage"
                @update:value="relation.targetColumn = ''"
              />
              <n-select
                v-model:value="relation.targetColumn"
                :options="relationColumns(relation.targetTable)"
                filterable
                placeholder="目标字段"
                :disabled="!canManage"
              />
              <n-button
                v-if="canManage"
                quaternary
                aria-label="删除表关系"
                title="删除表关系"
                @click="form.relations?.splice(index, 1)"
                ><template #icon><TrashOutline /></template
              ></n-button>
            </div>
          </div>
        </div>
      </div>
      <template #footer>
        <div class="wizard-footer">
          <div class="connection-status">
            <n-button v-if="step === 2 && form.type !== 'FILE'" :loading="busy" :disabled="busy" @click="testConnection"
              >测试连接</n-button
            >
            <span v-if="connected && step === 2" class="test-success">连接成功</span>
          </div>
          <div class="footer-actions">
            <n-button :disabled="busy" @click="closeWizard">取消</n-button>
            <n-button v-if="step > (form.id ? 2 : 1) && canManage" :disabled="busy" @click="step -= 1">上一步</n-button>
            <n-button v-if="step < 3" type="primary" :loading="busy" @click="nextStep">下一步</n-button>
            <n-button
              v-else-if="canManage"
              type="primary"
              :loading="busy"
              :disabled="!form.selectedTables.length"
              @click="save"
              >保存</n-button
            >
          </div>
        </div>
      </template>
    </n-modal>
  </section>
</template>

<script setup lang="ts">
  import { computed, h, reactive, ref, watch } from 'vue';
  import {
    NButton,
    NCheckbox,
    NCheckboxGroup,
    NDataTable,
    NEmpty,
    NForm,
    NFormItem,
    NIcon,
    NInput,
    NInputNumber,
    NModal,
    NResult,
    NScrollbar,
    NSelect,
    NSpace,
    NSpin,
    NStep,
    NSteps,
    NSwitch,
    useDialog,
    useMessage,
  } from 'naive-ui';
  import {
    AddOutline,
    ChatbubbleOutline,
    CheckmarkCircle,
    CloseOutline,
    CreateOutline,
    DocumentOutline,
    GridOutline,
    ListOutline,
    RefreshOutline,
    SearchOutline,
    TrashOutline,
  } from '@vicons/ionicons5';

  import type {
    AiAgentDatasourceConfig,
    AiAgentDatasourceItem,
    AiAgentDatasourceTable,
    AiAgentDatasourceType,
  } from '@lib/shared/api/modules/aiAgent';

  import CrmIcon from '@/components/pure/crm-icon-font/index.vue';

  import {
    deleteAiAgentDatasource,
    getAiAgentDatasourceTables,
    saveAiAgentDatasource,
    testAiAgentDatasource,
    uploadAiAgentDatasource,
  } from '@/api/modules';
  import { hasAnyPermission } from '@/utils/permission';

  const props = defineProps<{ sources: AiAgentDatasourceItem[]; loading: boolean; error: string }>();
  const emit = defineEmits<{ refresh: []; query: [id: string]; close: [] }>();
  const message = useMessage();
  const dialog = useDialog();
  const canManage = computed(() => hasAnyPermission(['AGENT:UPDATE']));
  const types: { label: string; value: AiAgentDatasourceType; port?: number }[] = [
    { label: 'MySQL', value: 'MYSQL', port: 3306 },
    { label: 'PostgreSQL', value: 'POSTGRESQL', port: 5432 },
    { label: 'Oracle', value: 'ORACLE', port: 1521 },
    { label: 'SQL Server', value: 'SQLSERVER', port: 1433 },
    { label: 'Excel / CSV', value: 'FILE' },
  ];
  const filterOptions = [{ label: '全部类型', value: '' }, ...types];
  const search = ref('');
  const typeFilter = ref('');
  const filteredSources = computed(() =>
    props.sources.filter(
      (s) =>
        (!typeFilter.value || s.type === typeFilter.value) &&
        s.name.toLowerCase().includes(search.value.trim().toLowerCase())
    )
  );
  const typeLabel = (type: string) => types.find((t) => t.value === type)?.label || type;
  const showWizard = ref(false);
  const step = ref(1);
  const busy = ref(false);
  const connected = ref(false);
  const selectedFile = ref<File | null>(null);
  const fileInput = ref<HTMLInputElement>();
  const encoding = ref('UTF-8');
  const draftId = ref<string>();
  const form = reactive<AiAgentDatasourceConfig>({ name: '', type: 'MYSQL', port: 3306, selectedTables: [] });
  watch(
    () => [form.type, form.host, form.port, form.database, form.schema, form.username, form.password, form.ssl],
    () => {
      connected.value = false;
    }
  );
  const tables = ref<AiAgentDatasourceTable[]>([]);
  const tableSearch = ref('');
  const previewName = ref('');
  const filteredTables = computed(() =>
    tables.value.filter((t) =>
      `${t.name} ${t.comment || ''}`.toLowerCase().includes(tableSearch.value.trim().toLowerCase())
    )
  );
  const previewTable = computed(
    () => tables.value.find((t) => t.name === previewName.value) || filteredTables.value[0]
  );
  const schemaPlaceholder = computed(
    () =>
      ({ POSTGRESQL: 'public', ORACLE: '默认用户名大写', SQLSERVER: 'dbo' }[
        form.type as 'POSTGRESQL' | 'ORACLE' | 'SQLSERVER'
      ] || '')
  );
  const columnDefs = [
    { title: '字段', key: 'name', width: 145, ellipsis: { tooltip: true } },
    { title: '类型', key: 'type', width: 110 },
    {
      title: '业务说明',
      key: 'comment',
      minWidth: 200,
      render: (row: AiAgentDatasourceTable['columns'][number]) =>
        h(NInput, {
          'value': row.comment || '',
          'disabled': !canManage.value,
          'maxlength': 2000,
          'placeholder': '字段业务说明',
          'onUpdate:value': (value: string) => {
            row.comment = value;
          },
        }),
    },
  ];
  const relationTables = computed(() => form.selectedTables.map((name) => ({ label: name, value: name })));
  const relationColumns = (name: string) =>
    (tables.value.find((t) => t.name === name)?.columns || []).map((c) => ({ label: c.name, value: c.name }));
  function addRelation() {
    if ((form.relations?.length || 0) >= 100) return;
    (form.relations ||= []).push({ sourceTable: '', sourceColumn: '', targetTable: '', targetColumn: '' });
  }
  function importRelations() {
    const unique = new Map((form.relations || []).map((r) => [JSON.stringify(r), r]));
    tables.value
      .flatMap((t) => t.relations || [])
      .filter((r) => form.selectedTables.includes(r.sourceTable) && form.selectedTables.includes(r.targetTable))
      .forEach((r) => unique.set(JSON.stringify(r), { ...r }));
    if (unique.size > 100) {
      message.warning('最多配置 100 条表关系，请减少选中表');
      return;
    }
    form.relations = [...unique.values()];
  }
  watch(
    () => [...form.selectedTables],
    (selected) => {
      form.relations = (form.relations || []).filter(
        (r) =>
          (!r.sourceTable || selected.includes(r.sourceTable)) && (!r.targetTable || selected.includes(r.targetTable))
      );
      if (!form.id) importRelations();
    }
  );

  function reset() {
    Object.keys(form).forEach((key) => delete (form as unknown as Record<string, unknown>)[key]);
    Object.assign(form, {
      name: '',
      type: 'MYSQL',
      host: '',
      port: 3306,
      database: '',
      schema: '',
      username: '',
      password: '',
      ssl: false,
      selectedTables: [],
      relations: [],
    });
    tables.value = [];
    selectedFile.value = null;
    tableSearch.value = '';
    previewName.value = '';
    draftId.value = undefined;
    connected.value = false;
    encoding.value = 'UTF-8';
  }
  function openNew() {
    reset();
    step.value = 1;
    showWizard.value = true;
  }
  function chooseType(type: AiAgentDatasourceType) {
    form.type = type;
    form.port = types.find((t) => t.value === type)?.port;
    form.schema = '';
    connected.value = false;
  }
  function openEdit(source: AiAgentDatasourceItem, selectTables: boolean) {
    reset();
    Object.assign(form, { ...source, password: '', selectedTables: [...source.selectedTables] });
    tables.value = JSON.parse(JSON.stringify(source.tables));
    form.relations = JSON.parse(JSON.stringify(source.relations || []));
    step.value = selectTables || !canManage.value ? 3 : 2;
    showWizard.value = true;
  }
  async function closeWizard() {
    if (busy.value) return;
    if (draftId.value) {
      busy.value = true;
      try {
        await deleteAiAgentDatasource(draftId.value);
        draftId.value = undefined;
      } catch {
        message.error('无法清理未保存文件，请重试取消');
        return;
      } finally {
        busy.value = false;
        emit('refresh');
      }
    }
    showWizard.value = false;
  }
  function onFileChange(event: Event) {
    selectedFile.value = (event.target as HTMLInputElement).files?.[0] || null;
  }
  function validConfig() {
    if (!form.name.trim()) {
      message.warning('请输入数据源名称');
      return false;
    }
    if (
      form.type !== 'FILE' &&
      (!form.host?.trim() || !form.port || !form.database?.trim() || !form.username?.trim())
    ) {
      message.warning('请填写完整的连接配置');
      return false;
    }
    if (form.type === 'FILE' && !form.id && !selectedFile.value) {
      message.warning('请选择 Excel 或 CSV 文件');
      return false;
    }
    return true;
  }
  async function testConnection() {
    if (!validConfig()) return;
    busy.value = true;
    connected.value = false;
    try {
      await testAiAgentDatasource({ ...form });
      connected.value = true;
      message.success('连接成功');
    } catch {
      // The shared API client displays the backend error; keep the form available for correction.
    } finally {
      busy.value = false;
    }
  }
  async function nextStep() {
    if (step.value === 1) {
      step.value = 2;
      return;
    }
    if (!validConfig()) return;
    busy.value = true;
    try {
      if (form.type === 'FILE' && !form.id && selectedFile.value) {
        if (selectedFile.value.size > 20 * 1024 * 1024) {
          message.warning('文件不能超过 20MB');
          return;
        }
        const uploaded = await uploadAiAgentDatasource(form.name, selectedFile.value, encoding.value);
        draftId.value = uploaded.id;
        Object.assign(form, { ...uploaded, password: '', selectedTables: [] });
        tables.value = uploaded.tables;
      } else {
        const inspected = await getAiAgentDatasourceTables({ ...form });
        inspected.forEach((table) => {
          const old = tables.value.find((t) => t.name === table.name);
          if (old) {
            table.comment = old.comment;
            table.columns.forEach((c) => {
              const field = old.columns.find((f) => f.name === c.name);
              if (field) c.comment = field.comment;
            });
          }
        });
        tables.value = inspected;
      }
      form.selectedTables = form.selectedTables.filter((name) => tables.value.some((t) => t.name === name));
      if (!tables.value.length) {
        message.warning('当前数据库或 Schema 没有可访问的数据表');
        return;
      }
      step.value = 3;
    } catch {
      connected.value = false;
    } finally {
      busy.value = false;
    }
  }
  function selectVisible() {
    const selection = [...new Set([...form.selectedTables, ...filteredTables.value.map((t) => t.name)])];
    if (selection.length > 60) {
      message.warning('最多选择 60 张表，请缩小搜索范围');
      return;
    }
    form.selectedTables = selection;
  }
  async function save() {
    if (form.selectedTables.length > 60) {
      message.warning('最多选择 60 张表');
      return;
    }
    if ((form.relations || []).some((r) => !r.sourceTable || !r.sourceColumn || !r.targetTable || !r.targetColumn)) {
      message.warning('请填写完整的表关系，或删除空关系');
      return;
    }
    busy.value = true;
    try {
      await saveAiAgentDatasource({
        ...form,
        metadata: tables.value,
        relations: (form.relations || []).filter(
          (r) => form.selectedTables.includes(r.sourceTable) && form.selectedTables.includes(r.targetTable)
        ),
      });
      draftId.value = undefined;
      showWizard.value = false;
      message.success('数据源已保存');
      emit('refresh');
    } catch {
      // Retain the selection and connection fields after a rejected save.
    } finally {
      busy.value = false;
    }
  }
  function remove(source: AiAgentDatasourceItem) {
    dialog.warning({
      title: '删除数据源',
      content: `确定删除“${source.name}”？`,
      positiveText: '删除',
      negativeText: '取消',
      onPositiveClick: async () => {
        await deleteAiAgentDatasource(source.id);
        message.success('数据源已删除');
        emit('refresh');
      },
    });
  }
</script>

<style lang="less" scoped>
  .relationship-editor {
    padding-top: 16px;
  }
  .relationship-heading {
    display: flex;
    justify-content: space-between;
    align-items: center;
  }
  .relationship-row {
    display: grid;
    grid-template-columns: 1fr 1fr 16px 1fr 1fr 32px;
    gap: 8px;
    align-items: center;
    margin: 8px 0;
  }
  .field-preview > .n-input {
    margin-bottom: 12px;
  }
  @media (max-width: 640px) {
    .relationship-row {
      grid-template-columns: minmax(0, 1fr) minmax(0, 1fr) 24px;
    }
    .relationship-row > span {
      display: none;
    }
  }
  .datasource-page {
    display: flex;
    min-width: 0;
    height: 100%;
    flex-direction: column;
    background: var(--text-n10, #ffffff);
  }
  .datasource-toolbar {
    display: flex;
    justify-content: space-between;
    align-items: center;
    padding: 24px;
    border-bottom: 1px solid var(--text-n8, #e5e6eb);
    gap: 16px;
  }
  h1 {
    margin: 0;
    font-size: 20px;
    font-weight: 500;
  }
  .datasource-tools {
    display: flex;
    align-items: center;
    min-width: 0;
    gap: 10px;
  }
  .search-input {
    width: 210px;
  }
  .type-filter {
    width: 140px;
  }
  .datasource-body {
    flex: 1;
    overflow: auto;
    padding: 24px;
  }
  .source-grid {
    display: grid;
    gap: 20px;
    grid-template-columns: repeat(auto-fill, minmax(min(290px, 100%), 1fr));
  }
  .source-card {
    display: flex;
    padding: 18px;
    min-width: 0;
    min-height: 174px;
    border: 1px solid var(--text-n7, #dcdfe6);
    border-radius: 6px;
    flex-direction: column;
  }
  .source-heading {
    display: flex;
    gap: 12px;
    align-items: center;
  }
  .source-title {
    min-width: 0;
  }
  .source-title h2 {
    margin: 0 0 4px;
    overflow-wrap: anywhere;
    font-size: 16px;
    font-weight: 500;
  }
  .source-title span,
  .source-location {
    font-size: 12px;
    color: var(--text-n3, #68717e);
  }
  .source-location {
    margin-top: 18px;
    overflow-wrap: anywhere;
  }
  .source-bottom {
    display: flex;
    justify-content: space-between;
    align-items: center;
    margin-top: auto;
    padding-top: 20px;
  }
  .table-count {
    display: flex;
    align-items: center;
    border: 0;
    color: var(--primary-8, #1677ff);
    background: none;
    gap: 7px;
    cursor: pointer;
  }
  .source-actions {
    display: flex;
    gap: 3px;
  }
  .source-empty {
    margin-top: 100px;
  }
  :deep(.source-modal) {
    width: min(980px, 96vw);
  }
  .wizard-content {
    overflow: auto;
    margin-top: 28px;
    min-height: 350px;
    max-height: min(64vh, 650px);
  }
  .type-grid {
    display: grid;
    gap: 16px;
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
  .source-type {
    position: relative;
    display: flex;
    align-items: center;
    padding: 20px;
    min-height: 108px;
    border: 1px solid #dcdfe6;
    border-radius: 6px;
    text-align: left;
    background: transparent;
    gap: 14px;
    cursor: pointer;
  }
  .source-type.selected {
    border-color: var(--primary-8, #1677ff);
    background: var(--primary-7, #f0f7ff);
  }
  .type-check {
    position: absolute;
    top: 10px;
    right: 10px;
    color: var(--primary-8, #1677ff);
  }
  .connection-form {
    display: grid;
    gap: 0 24px;
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .full-field {
    grid-column: 1 / -1;
  }
  .full-width {
    width: 100%;
  }
  .uploaded-file {
    display: flex;
    gap: 8px;
    align-items: center;
    overflow-wrap: anywhere;
  }
  .table-toolbar {
    display: flex;
    gap: 16px;
    align-items: center;
    margin-bottom: 16px;
  }
  .table-search {
    width: 240px;
  }
  .selection-count {
    margin-left: auto;
    font-size: 12px;
    white-space: nowrap;
    color: #68717e;
  }
  .table-layout {
    display: grid;
    grid-template-columns: minmax(220px, 1fr) minmax(0, 1.5fr);
    gap: 20px;
  }
  .table-list {
    height: 345px;
    border-right: 1px solid #e5e6eb;
  }
  .table-row {
    display: flex;
    justify-content: space-between;
    align-items: center;
    padding: 8px 10px;
    min-height: 46px;
    gap: 8px;
  }
  .table-row.focused {
    background: #f5f7fa;
  }
  .table-row :deep(.n-checkbox) {
    min-width: 0;
  }
  .table-row :deep(.n-checkbox__label) {
    min-width: 0;
    overflow-wrap: anywhere;
  }
  .table-name {
    display: block;
    font-size: 13px;
  }
  .table-comment {
    display: block;
    font-size: 12px;
    color: #68717e;
  }
  .field-preview {
    min-width: 0;
  }
  .field-preview h3 {
    margin: 0 0 12px;
    font-size: 14px;
    font-weight: 500;
    overflow-wrap: anywhere;
  }
  .table-empty {
    margin-top: 60px;
  }
  .wizard-footer {
    display: flex;
    gap: 16px;
    justify-content: space-between;
    align-items: center;
  }
  .connection-status,
  .footer-actions {
    display: flex;
    gap: 12px;
    align-items: center;
  }
  .test-success {
    font-size: 13px;
    color: #18a058;
  }
  @media (max-width: 1100px) {
    .datasource-toolbar {
      align-items: flex-start;
      flex-wrap: wrap;
    }
    .datasource-tools {
      flex-wrap: wrap;
      width: 100%;
    }
  }
  @media (max-width: 640px) {
    .relationship-row > :nth-child(4) {
      grid-column: 1;
      grid-row: 2;
    }
    .relationship-row > :nth-child(5) {
      grid-column: 2;
      grid-row: 2;
    }
    .relationship-row > .n-button {
      grid-column: 3;
      grid-row: 1 / 3;
    }
    .datasource-toolbar,
    .datasource-body {
      padding: 16px;
    }
    .search-input {
      width: 100%;
    }
    .type-grid {
      grid-template-columns: repeat(2, minmax(0, 1fr));
    }
    .source-type {
      padding: 14px;
      gap: 10px;
    }
    .connection-form {
      grid-template-columns: minmax(0, 1fr);
    }
    .table-toolbar {
      flex-wrap: wrap;
      gap: 10px;
    }
    .table-search {
      width: 100%;
    }
    .table-layout {
      grid-template-columns: minmax(0, 1fr);
    }
    .table-list {
      height: 200px;
      border-right: 0;
    }
    .wizard-footer {
      align-items: flex-start;
      flex-wrap: wrap;
    }
    .footer-actions {
      margin-left: auto;
    }
  }
</style>
