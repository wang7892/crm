<script setup lang="ts">
  import { computed } from 'vue';

  import { highlightAiAgentSql } from '../utils/aiAgentSql';

  const props = defineProps<{ code: string }>();
  const highlighted = computed(() => highlightAiAgentSql(props.code));
</script>

<template>
  <code class="ai-agent-sql-code" v-html="highlighted"></code>
</template>

<style scoped>
  .ai-agent-sql-code {
    font: inherit;
  }
  .ai-agent-sql-code :deep(.hljs-keyword),
  .ai-agent-sql-code :deep(.hljs-operator) {
    color: #cf222e;
  }
  .ai-agent-sql-code :deep(.hljs-built_in),
  .ai-agent-sql-code :deep(.hljs-type) {
    color: #ad4e00;
  }
  .ai-agent-sql-code :deep(.hljs-number) {
    color: #0550ae;
  }
  .ai-agent-sql-code :deep(.hljs-string) {
    color: #116329;
  }
  .ai-agent-sql-code :deep(.hljs-comment) {
    color: var(--text-n3);
  }
</style>
