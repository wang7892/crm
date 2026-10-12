<template>
  <div class="agent-result">
    <section v-for="entry in tables" :key="entry.title">
      <h4>{{ entry.title }}</h4>
      <div class="table-scroll" tabindex="0" :aria-label="entry.title">
        <table>
          <thead
            ><tr
              ><th v-for="column in entry.table.columns" :key="column.key">{{ column.label }}</th></tr
            ></thead
          >
          <tbody>
            <tr v-for="(row, index) in entry.table.rows" :key="index">
              <td v-for="column in entry.table.columns" :key="column.key">{{ cell(row[column.key]) }}</td>
            </tr>
            <tr v-if="!entry.table.rows.length"><td :colspan="entry.table.columns.length || 1">没有匹配的数据</td></tr>
          </tbody>
        </table>
      </div>
    </section>
    <div v-if="result.resultTable && result.pageInfo" class="page-info">
      已返回 {{ result.resultTable.rows.length }} 条
      <template v-if="result.pageInfo.total != null"
        >，共 {{ result.pageInfo.total }} {{ result.pageInfo.unit }}</template
      >
      <button v-if="result.pageInfo.continuable" type="button" :disabled="busy" @click="$emit('continue')"
        >继续查看</button
      >
    </div>
    <div
      v-if="result.queryQuestion && (result.resultTable || result.predictionTable || result.queryError)"
      class="actions"
    >
      <button type="button" :disabled="busy" @click="$emit('action', 'REGENERATE')">重新生成</button>
      <template v-if="result.resultTable?.rows.length">
        <button type="button" :disabled="busy" @click="$emit('action', 'ANALYSIS')">分析</button>
        <button type="button" :disabled="busy" @click="$emit('action', 'PREDICT')">预测</button>
      </template>
      <button v-if="tables.length" type="button" :disabled="busy" @click="$emit('export')">导出 Excel</button>
    </div>
    <p v-for="warning in result.warnings" :key="warning" class="page-info">{{ warning }}</p>
    <div v-if="result.recommendedQuestions?.length" class="suggestions">
      <span>你还可以问</span>
      <button
        v-for="question in result.recommendedQuestions"
        :key="question"
        type="button"
        :disabled="busy"
        @click="$emit('ask', question)"
        >{{ question }}</button
      >
    </div>
  </div>
</template>

<script setup lang="ts">
  import { computed } from 'vue';

  import type { AiAgentChatParams, AiAgentChatResult, AiAgentResultTable } from '@lib/shared/api/modules/aiAgent';

  const props = defineProps<{
    result: Pick<
      AiAgentChatResult,
      | 'resultTable'
      | 'predictionTable'
      | 'recommendedQuestions'
      | 'queryQuestion'
      | 'queryError'
      | 'pageInfo'
      | 'warnings'
    >;
    busy: boolean;
  }>();
  defineEmits<{
    action: [action: AiAgentChatParams['action']];
    ask: [question: string];
    continue: [];
    export: [];
  }>();
  const tables = computed(() => {
    const entries: { title: string; table: AiAgentResultTable }[] = [];
    if (props.result.resultTable) entries.push({ title: '查询结果', table: props.result.resultTable });
    if (props.result.predictionTable) entries.push({ title: '预测数据', table: props.result.predictionTable });
    return entries;
  });
  function cell(value: unknown) {
    if (value == null) return '—';
    return typeof value === 'object' ? JSON.stringify(value) : String(value);
  }
</script>

<style scoped>
  .agent-result {
    margin-top: 12px;
    min-width: 0;
  }
  h4 {
    margin: 10px 0;
    font-size: 14px;
  }
  .table-scroll {
    overflow: auto;
    max-height: 420px;
    border: 1px solid var(--text-n8, #e5e6eb);
    border-radius: 6px;
  }
  table {
    border-collapse: collapse;
    min-width: 100%;
    font-size: 13px;
    white-space: nowrap;
  }
  th,
  td {
    padding: 9px 12px;
    border-bottom: 1px solid var(--text-n8, #e5e6eb);
    text-align: left;
  }
  th {
    position: sticky;
    top: 0;
    background: var(--text-n10, #f7f8fa);
  }
  .actions,
  .page-info,
  .suggestions {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
    margin-top: 12px;
  }
  button {
    padding: 5px 10px;
    font-size: 13px;
    border: 1px solid var(--text-n8, #dcdfe6);
    border-radius: 5px;
    text-align: left;
    color: #2468c5;
    background: transparent;
    cursor: pointer;
  }
  button:disabled {
    opacity: 0.5;
    cursor: default;
  }
  .suggestions {
    flex-direction: column;
    align-items: flex-start;
  }
  .suggestions span {
    font-size: 12px;
    color: var(--text-n3, #68717e);
  }
</style>
