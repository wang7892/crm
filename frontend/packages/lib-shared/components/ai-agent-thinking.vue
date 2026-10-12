<script setup lang="ts">
  import { computed, ref } from 'vue';

  const props = withDefaults(defineProps<{ content?: string; loading?: boolean }>(), {
    content: '',
    loading: false,
  });
  const expanded = ref(props.loading);
  // Render provider text safely. Backtick spans use SQLBot's muted inline-code appearance.
  const parts = computed(() => props.content.split(/(`[^`\n]+`)/g));
</script>

<template>
  <div class="ai-agent-thinking">
    <button type="button" class="thinking-heading" :aria-expanded="expanded" @click="expanded = !expanded">
      <span v-if="loading" class="thinking-spinner" aria-hidden="true"></span>
      <span>{{ loading ? '思考中' : '思考过程' }}</span>
      <span class="thinking-chevron" :class="{ 'thinking-chevron--expanded': expanded }" aria-hidden="true">⌄</span>
    </button>
    <div v-if="expanded && content.trim()" class="thinking-content">
      <template v-for="(part, index) in parts" :key="index">
        <code v-if="part.startsWith('`') && part.endsWith('`')">{{ part.slice(1, -1) }}</code>
        <span v-else>{{ part }}</span>
      </template>
    </div>
    <div v-else-if="expanded && !loading" class="thinking-content thinking-content--empty">
      本次回答没有可展示的思考内容。
    </div>
  </div>
</template>

<style scoped>
  .ai-agent-thinking {
    margin: 12px 0;
    min-width: 0;
    max-width: 100%;
    font-size: 14px;
    line-height: 22px;
  }
  .thinking-heading {
    display: inline-flex;
    align-items: center;
    padding: 4px 12px;
    border: 1px solid var(--text-n7, #d9dcdf);
    border-radius: 6px;
    color: var(--text-n1, #1f2329);
    background: var(--text-n10, #ffffff);
    gap: 6px;
    font: inherit;
    cursor: pointer;
  }
  .thinking-heading:hover {
    background: var(--text-n9, #f5f6f7);
  }
  .thinking-heading:focus-visible {
    outline: 2px solid var(--primary-6, #3370ff);
    outline-offset: 3px;
  }
  .thinking-chevron--expanded {
    transform: rotate(180deg);
  }
  .thinking-content {
    margin-top: 8px;
    padding-left: 9px;
    border-left: 1px solid var(--text-n7, #d9dcdf);
    white-space: pre-wrap;
    color: var(--text-n3, #8f959e);
    overflow-wrap: anywhere;
  }
  code {
    padding: 2px 4px;
    border-radius: 4px;
    color: inherit;
    background: var(--text-n9, #eff0f1);
    font: 0.9em/1.7 Consolas, Monaco, monospace;
  }
  .thinking-spinner {
    width: 12px;
    height: 12px;
    border: 2px solid var(--text-n7, #d9dcdf);
    border-top-color: var(--primary-6, #3370ff);
    border-radius: 50%;
    animation: thinking-spin 0.8s linear infinite;
  }
  @keyframes thinking-spin {
    to {
      transform: rotate(360deg);
    }
  }
  @media (prefers-reduced-motion: reduce) {
    .thinking-spinner {
      animation: none;
    }
  }
</style>
