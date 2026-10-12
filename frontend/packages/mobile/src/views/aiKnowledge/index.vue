<template>
  <CrmPageWrapper :title="t('aiKnowledge.title')" :back-route-name="WorkbenchRouteEnum.WORKBENCH_INDEX">
    <main class="query-knowledge-mobile">
      <van-tabs v-model:active="kind"
        ><van-tab title="术语配置" name="TERM" /><van-tab title="SQL 示例库" name="SQL_EXAMPLE"
      /></van-tabs>
      <van-search v-model="keyword" :placeholder="isTerm ? '搜索术语、同义词' : '搜索问题、SQL'" />
      <div class="list-toolbar"
        ><span>{{ filtered.length }} 条</span
        ><van-button size="small" icon="replay" :loading="loading" aria-label="刷新" @click="load" /><van-button
          size="small"
          type="primary"
          icon="plus"
          @click="edit()"
          >{{ isTerm ? '新建术语' : '添加示例' }}</van-button
        ></div
      >
      <van-empty v-if="!loading && !filtered.length" :description="loadError || '暂无数据'" />
      <div v-for="item in filtered" :key="item.id" class="knowledge-row">
        <button class="row-title" @click="openDetail(item)">{{ item.name }}</button
        ><p>{{ item.content.slice(0, 160) }}</p
        ><small>{{ scopeLabel(item) }}</small>
        <div class="row-actions"
          ><van-switch
            :model-value="item.enabled"
            size="20"
            :aria-label="`${item.name}启用状态`"
            @update:model-value="toggle(item, $event)" /><van-button
            size="mini"
            icon="edit"
            aria-label="编辑"
            @click="edit(item)" /><van-button
            v-if="canDelete"
            size="mini"
            icon="delete-o"
            aria-label="删除"
            @click="remove(item)"
        /></div>
      </div>
      <van-popup v-model:show="editorVisible" position="bottom" closeable class="knowledge-popup">
        <h2>{{ form.id ? '编辑' : '新建' }}{{ isTerm ? '术语' : 'SQL 示例' }}</h2>
        <van-form @submit="save">
          <van-field
            v-model="form.name"
            :label="isTerm ? '术语名称' : '问题描述'"
            :maxlength="255"
            :rules="[{ required: true, message: '请输入名称或问题' }]"
          />
          <van-field
            v-model="form.content"
            :label="isTerm ? '术语描述' : '示例 SQL'"
            type="textarea"
            rows="6"
            :maxlength="isTerm ? 6000 : 20000"
            :rules="[{ required: true, message: '请输入描述或 SQL' }]"
          />
          <van-field v-if="isTerm" v-model="aliases" label="同义词" type="textarea" placeholder="每行一个" />
          <van-cell v-if="isTerm" title="所有数据源"
            ><template #right-icon><van-switch v-model="form.allDatasources" size="22" /></template
          ></van-cell>
          <div v-if="!form.allDatasources" class="source-options"
            ><h3>生效数据源</h3>
            <van-checkbox-group v-if="isTerm" v-model="form.datasourceIds"
              ><van-checkbox v-for="s in sourceOptions" :key="s.value" :name="s.value">{{
                s.label
              }}</van-checkbox></van-checkbox-group
            >
            <van-radio-group v-else v-model="exampleSource"
              ><van-radio v-for="s in sourceOptions" :key="s.value" :name="s.value">{{
                s.label
              }}</van-radio></van-radio-group
            >
          </div>
          <van-cell title="启用"
            ><template #right-icon><van-switch v-model="form.enabled" size="22" /></template
          ></van-cell>
          <div class="save-command"
            ><van-button block type="primary" native-type="submit" :loading="saving">保存</van-button></div
          >
        </van-form>
      </van-popup>
      <van-popup v-model:show="detailVisible" position="bottom" closeable class="knowledge-popup">
        <template v-if="detail"
          ><h2>{{ detail.name }}</h2
          ><p class="detail-scope">{{ scopeLabel(detail) }}</p
          ><p v-if="detail.synonyms.length" class="detail-scope">{{ detail.synonyms.join('、') }}</p>
          <div v-if="isTerm" class="detail-content">{{ detail.content }}</div
          ><pre v-else class="detail-content sql-content">{{
            formatAiAgentSql(detail.content, sourceType(detail))
          }}</pre>
        </template>
      </van-popup>
    </main>
  </CrmPageWrapper>
</template>

<script setup lang="ts">
  import { computed, onMounted, ref, watch } from 'vue';
  import { showConfirmDialog, showFailToast, showSuccessToast } from 'vant';

  import type { AiAgentDatasourceItem } from '@lib/shared/api/modules/aiAgent';
  import type { QueryKnowledgeItem, QueryKnowledgeKind } from '@lib/shared/api/modules/queryKnowledge';
  import { useI18n } from '@lib/shared/hooks/useI18n';
  import { formatAiAgentSql } from '@lib/shared/utils/aiAgentSql';

  import CrmPageWrapper from '@/components/pure/crm-page-wrapper/index.vue';

  import {
    deleteQueryKnowledge,
    enableQueryKnowledge,
    getAiAgentDatasources,
    getQueryKnowledge,
    saveQueryKnowledge,
  } from '@/api/modules';
  import { hasAnyPermission } from '@/utils/permission';

  import { WorkbenchRouteEnum } from '@/enums/routeEnum';

  const { t } = useI18n();
  const kind = ref<QueryKnowledgeKind>('TERM');
  const isTerm = computed(() => kind.value === 'TERM');
  const keyword = ref('');
  const rows = ref<QueryKnowledgeItem[]>([]);
  const sources = ref<AiAgentDatasourceItem[]>([]);
  const loading = ref(false);
  const loadError = ref('');
  let version = 0;
  const canDelete = computed(() => hasAnyPermission(['AGENT:DELETE']));
  const sourceOptions = computed(() => [
    { label: '内置 CRM', value: 'CRM' },
    ...sources.value.map((s) => ({ label: s.name, value: s.id })),
  ]);
  const filtered = computed(() =>
    rows.value.filter((r) =>
      `${r.name} ${r.content} ${r.synonyms.join(' ')}`.toLowerCase().includes(keyword.value.toLowerCase())
    )
  );
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
  const aliases = ref('');
  const editorVisible = ref(false);
  const saving = ref(false);
  const exampleSource = computed({
    get: () => form.value.datasourceIds[0],
    set: (s: string) => {
      form.value.datasourceIds = s ? [s] : [];
    },
  });
  const detail = ref<QueryKnowledgeItem | null>(null);
  const detailVisible = ref(false);
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
  function openDetail(item: QueryKnowledgeItem) {
    detail.value = item;
    detailVisible.value = true;
  }
  function edit(item?: QueryKnowledgeItem) {
    form.value = item ? JSON.parse(JSON.stringify(item)) : blank();
    aliases.value = form.value.synonyms.join('\n');
    editorVisible.value = true;
  }
  async function load() {
    const token = ++version;
    loading.value = true;
    loadError.value = '';
    try {
      const data = await getQueryKnowledge(kind.value);
      if (token === version) rows.value = Array.isArray(data) ? data : [];
    } catch {
      if (token === version) {
        rows.value = [];
        loadError.value = '加载失败，请刷新重试';
      }
    } finally {
      if (token === version) loading.value = false;
    }
  }
  async function save() {
    if (!form.value.name.trim() || !form.value.content.trim()) return;
    if (!form.value.allDatasources && !form.value.datasourceIds.length) {
      showFailToast('请选择生效数据源');
      return;
    }
    form.value.synonyms = isTerm.value
      ? aliases.value
          .split('\n')
          .map((s) => s.trim())
          .filter(Boolean)
      : [];
    if (form.value.allDatasources) form.value.datasourceIds = [];
    saving.value = true;
    try {
      await saveQueryKnowledge(form.value);
      editorVisible.value = false;
      showSuccessToast('保存成功');
      await load();
    } catch {
      /* Shared API client displays the error. */
    } finally {
      saving.value = false;
    }
  }
  async function toggle(item: QueryKnowledgeItem, enabled: boolean) {
    try {
      await enableQueryKnowledge(item, enabled);
      await load();
    } catch {
      /* Preserve saved state. */
    }
  }
  async function remove(item: QueryKnowledgeItem) {
    try {
      await showConfirmDialog({ title: '删除知识', message: `确定删除“${item.name}”吗？` });
    } catch {
      return;
    }
    try {
      await deleteQueryKnowledge([item]);
      showSuccessToast('已删除');
      await load();
    } catch {
      /* Shared API client displays the error. */
    }
  }
  watch(kind, () => {
    keyword.value = '';
    editorVisible.value = false;
    detailVisible.value = false;
    load();
  });
  onMounted(async () => {
    load();
    try {
      const data = await getAiAgentDatasources();
      sources.value = Array.isArray(data) ? data : [];
    } catch {
      /* Shared API client displays the error. */
    }
  });
</script>

<style scoped lang="less">
  .query-knowledge-mobile {
    min-height: 100%;
    background: #ffffff;
  }
  .list-toolbar {
    display: flex;
    align-items: center;
    gap: 12px;
    padding: 12px 16px;
    border-bottom: 1px solid #edf0f2;
  }
  .list-toolbar > span {
    flex: 1;
    font-size: 13px;
    color: #67717c;
  }
  .knowledge-row {
    padding: 16px;
    border-bottom: 1px solid #edf0f2;
  }
  .row-title {
    padding: 0;
    font-size: 15px;
    font-weight: 600;
    border: 0;
    text-align: left;
    color: #009fa7;
    background: transparent;
    overflow-wrap: anywhere;
  }
  .knowledge-row p {
    overflow: hidden;
    margin: 8px 0;
    max-height: 66px;
    font-size: 13px;
    color: #46515d;
    line-height: 1.7;
    overflow-wrap: anywhere;
  }
  .knowledge-row small {
    display: block;
    color: #67717c;
    overflow-wrap: anywhere;
  }
  .row-actions {
    display: flex;
    justify-content: flex-end;
    align-items: center;
    gap: 16px;
    margin-top: 12px;
  }
  .knowledge-popup {
    overflow: auto;
    max-height: 90vh;
  }
  h2 {
    margin: 20px 48px 20px 16px;
    font-size: 18px;
    overflow-wrap: anywhere;
  }
  h3 {
    margin: 0 0 12px;
    font-size: 14px;
  }
  .source-options,
  .save-command {
    padding: 16px;
  }
  .source-options :deep(.van-checkbox),
  .source-options :deep(.van-radio) {
    margin-bottom: 12px;
  }
  .detail-scope {
    padding: 0 16px;
    font-size: 13px;
    color: #67717c;
    overflow-wrap: anywhere;
  }
  .detail-content {
    margin: 16px;
    font-size: 14px;
    white-space: pre-wrap;
    overflow-wrap: anywhere;
    line-height: 1.8;
  }
  .sql-content {
    overflow: auto;
    padding: 12px;
    font-family: Consolas, monospace;
    background: #f4f6f8;
  }
</style>
