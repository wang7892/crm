<script setup lang="ts">
  import { computed, ref } from 'vue';

  import AiAgentSqlCode from './ai-agent-sql-code.vue';

  import { type AiAgentExecutionResult, formatAiAgentDuration, getAiAgentExecution } from '../utils/aiAgentExecution';
  import { formatAiAgentSql } from '../utils/aiAgentSql';

  const props = defineProps<{ result: AiAgentExecutionResult }>();
  const execution = computed(() => getAiAgentExecution(props.result));
  const drawer = ref<HTMLDialogElement>();
  const trigger = ref<HTMLButtonElement>();
  function openDetails() {
    drawer.value?.showModal();
  }
  function closeDetails() {
    drawer.value?.close();
  }
  function closeOnBackdrop(event: MouseEvent) {
    if (event.target === drawer.value) closeDetails();
  }
</script>

<template>
  <div
    v-if="execution.durationMs != null || execution.totalTokens != null || execution.steps.length || result.queryError"
    class="ai-agent-execution"
  >
    <div class="execution-totals">
      <span>消耗 Tokens {{ execution.totalTokens ?? '—' }}</span>
      <span>耗时 {{ formatAiAgentDuration(execution.durationMs) }}</span>
    </div>
    <button ref="trigger" type="button" class="execution-trigger" @click="openDetails">
      <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" aria-hidden="true">
        <path d="M6 2h9l4 4v16H6zM14 2v5h5M9 11h7M9 15h7M9 19h4" />
      </svg>
      执行详情
    </button>
    <Teleport to="body">
      <dialog
        ref="drawer"
        class="execution-drawer"
        aria-label="执行详情"
        @click="closeOnBackdrop"
        @close="trigger?.focus()"
      >
        <div class="drawer-content">
          <header class="drawer-header">
            <h2>执行详情</h2>
            <button type="button" class="drawer-close" aria-label="关闭执行详情" autofocus @click="closeDetails"
              >×</button
            >
          </header>
          <div class="drawer-body">
            <h3>概览</h3>
            <div class="execution-overview">
              <div class="overview-card"
                ><span>消耗 Tokens</span><strong>{{ execution.totalTokens ?? '—' }}</strong></div
              >
              <div class="overview-card"
                ><span>耗时</span><strong>{{ formatAiAgentDuration(execution.durationMs) }}</strong></div
              >
            </div>
            <p v-if="result.queryError" class="execution-error">{{ result.queryError }}</p>
            <h3>执行详情</h3>
            <p v-if="!execution.steps.length" class="execution-empty">此记录未保存执行步骤。</p>
            <details
              v-for="(step, index) in execution.steps"
              :key="index"
              class="execution-step"
              :data-stage="step.name"
            >
              <summary class="execution-step-heading">
                <span class="execution-step-title">{{ step.title }}</span>
                <span class="execution-step-metrics">
                  <span v-if="step.totalTokens != null && step.totalTokens > 0">{{ step.totalTokens }} tokens</span>
                  <span>{{ formatAiAgentDuration(step.durationMs) }}</span>
                  <span
                    class="execution-status"
                    :class="{ failed: step.status === 'FAILED', unknown: !step.status }"
                    :aria-label="step.status === 'SUCCESS' ? '成功' : step.status === 'FAILED' ? '失败' : '未记录'"
                    :title="step.status === 'SUCCESS' ? '成功' : step.status === 'FAILED' ? '失败' : '未记录'"
                  >
                    {{ step.status === 'SUCCESS' ? '✓' : step.status === 'FAILED' ? '×' : '—' }}
                  </span>
                </span>
              </summary>
              <p v-if="step.summary" class="execution-summary">{{ step.summary }}</p>
              <div v-for="(detail, detailIndex) in step.details" :key="detailIndex" class="execution-detail">
                <h4>{{ detail.title }}</h4>
                <pre
                  v-if="detail.type === 'sql'"
                  class="execution-sql"
                ><AiAgentSqlCode :code="formatAiAgentSql(detail.content, result.datasourceType)" /></pre>
                <p v-else>{{ detail.content }}</p>
              </div>
              <div v-for="(call, callIndex) in step.modelCalls" :key="callIndex" class="execution-model-call">
                <span
                  >{{ call.model }} · {{ call.provider }} · {{ formatAiAgentDuration(call.durationMs) }} ·
                  {{ call.status === 'SUCCESS' ? '成功' : '失败' }}</span
                >
                <span
                  >输入 Tokens {{ call.promptTokens ?? '—' }} · 输出 Tokens {{ call.completionTokens ?? '—' }} · 合计
                  {{ call.totalTokens ?? '—' }}</span
                >
              </div>
            </details>
          </div>
        </div>
      </dialog>
    </Teleport>
  </div>
</template>

<style scoped>
  .ai-agent-execution {
    display: flex;
    justify-content: space-between;
    align-items: center;
    margin: 12px 0;
    padding: 8px 12px;
    min-height: 38px;
    font-size: 14px;
    border-radius: 6px;
    color: #646a73;
    background: #f5f6f7;
    flex-wrap: wrap;
    gap: 8px;
    box-sizing: border-box;
    line-height: 22px;
  }
  .execution-totals,
  .execution-trigger {
    display: flex;
    align-items: center;
    flex-wrap: wrap;
    gap: 12px;
  }
  .execution-trigger {
    margin-left: auto;
    padding: 0;
    border: 0;
    color: inherit;
    background: transparent;
    gap: 4px;
    font: inherit;
    cursor: pointer;
  }
  .execution-drawer {
    position: fixed;
    margin: 0;
    padding: 0;
    width: max(50vw, 600px);
    max-width: 100vw;
    height: 100dvh;
    max-height: 100dvh;
    font-size: 14px;
    border: 0;
    color: #1f2329;
    background: #ffffff;
    box-shadow: -4px 0 24px #0000001a;
    inset: 0 0 0 auto;
    line-height: 22px;
  }
  .execution-drawer::backdrop {
    background: #00000066;
  }
  .drawer-content {
    display: flex;
    flex-direction: column;
    height: 100%;
  }
  .drawer-header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    padding: 20px 24px;
    border-bottom: 1px solid #dee0e3;
  }
  .drawer-header h2 {
    margin: 0;
    font-size: 18px;
    font-weight: 500;
  }
  .drawer-close {
    padding: 0 6px;
    font-size: 26px;
    border: 0;
    color: #646a73;
    background: transparent;
    cursor: pointer;
  }
  .drawer-body {
    overflow: auto;
    padding: 24px;
    overscroll-behavior: contain;
  }
  h3 {
    margin: 0 0 16px;
    font-size: 16px;
    font-weight: 500;
  }
  .execution-overview {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 16px;
    margin-bottom: 24px;
  }
  .overview-card {
    display: flex;
    flex-direction: column;
    gap: 4px;
    padding: 16px;
    border: 1px solid #dee0e3;
    border-radius: 12px;
    color: #646a73;
  }
  .overview-card strong {
    font-size: 20px;
    font-weight: 500;
    color: #1f2329;
    overflow-wrap: anywhere;
  }
  .execution-step {
    margin-bottom: 8px;
    padding: 16px;
    border: 1px solid #dee0e3;
    border-radius: 12px;
  }
  .execution-step-heading {
    display: flex;
    align-items: center;
    gap: 8px;
    cursor: pointer;
  }
  .execution-step-heading::before {
    content: '›';
    color: #646a73;
  }
  .execution-step[open] > summary::before {
    transform: rotate(90deg);
  }
  .execution-step-title {
    flex: 1;
    min-width: 0;
    overflow-wrap: anywhere;
  }
  .execution-step-metrics {
    display: flex;
    justify-content: flex-end;
    font-size: 12px;
    color: #646a73;
    flex-wrap: wrap;
    gap: 8px;
  }
  .execution-step-metrics span {
    white-space: nowrap;
  }
  .execution-status {
    display: inline-flex;
    justify-content: center;
    align-items: center;
    width: 14px;
    height: 14px;
    font-weight: 600;
    border-radius: 50%;
    color: #ffffff;
    background: #34c724;
    align-self: center;
    line-height: 14px;
  }
  .execution-status.failed {
    background: #c53535;
  }
  .execution-status.unknown {
    color: #646a73;
    background: transparent;
  }
  .execution-error {
    color: #c53535;
  }
  .execution-summary,
  .execution-detail p,
  .execution-error {
    margin: 12px 0;
    white-space: pre-wrap;
    overflow-wrap: anywhere;
  }
  .execution-detail h4 {
    margin: 12px 0 4px;
    font-size: 14px;
    font-weight: 500;
  }
  .execution-sql {
    overflow: auto;
    margin: 8px 0;
    padding: 12px;
    max-height: 360px;
    background: #f5f6f7;
    font: 12px/1.7 Consolas, Monaco, monospace;
  }
  .execution-model-call {
    display: flex;
    margin-top: 12px;
    font-size: 12px;
    color: #646a73;
    flex-direction: column;
    gap: 4px;
    overflow-wrap: anywhere;
  }
  .execution-empty {
    color: #646a73;
  }
  button:focus-visible,
  summary:focus-visible {
    outline: 2px solid #3370ff;
    outline-offset: 3px;
  }
  @media (max-width: 767px) {
    .execution-drawer {
      width: 100vw;
    }
    .drawer-header,
    .drawer-body {
      padding: 16px;
    }
    .execution-step {
      padding: 12px;
    }
    .execution-step-heading {
      flex-wrap: wrap;
    }
    .execution-step-metrics {
      justify-content: flex-start;
      padding-left: 14px;
      width: 100%;
    }
  }
</style>
