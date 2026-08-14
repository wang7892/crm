<template>
  <CrmPageWrapper :title="t('task.detailTitle')" :back-route-name="TaskRouteEnum.TASK_INDEX">
    <div v-if="task" class="task-detail">
      <section class="task-detail__summary">
        <div class="task-detail__heading">
          <h1>{{ task.name }}</h1>
          <van-tag :type="statusTagType[task.status]" plain>
            {{ t(statusLabelKey[task.status]) }}
          </van-tag>
        </div>
        <p v-if="task.description" class="task-detail__description">{{ task.description }}</p>
      </section>

      <section class="task-section">
        <h2>{{ t('task.basicInfo') }}</h2>
        <div class="task-info-grid">
          <div v-for="item in basicInfo" :key="item.label" class="task-info-item">
            <span>{{ item.label }}</span>
            <strong>{{ item.value }}</strong>
          </div>
        </div>
      </section>

      <section class="task-section task-section--report">
        <div class="task-section__heading">
          <h2>{{ t('task.report.title') }}</h2>
          <van-tag :type="reportStateTagType[task.reportState]" plain>
            {{ t(reportStateLabelKey[task.reportState]) }}
          </van-tag>
        </div>
        <van-field
          v-model="reportContent"
          type="textarea"
          rows="4"
          autosize
          maxlength="5000"
          show-word-limit
          :readonly="isCompleted"
          :placeholder="t('task.report.placeholder')"
          class="task-report-input"
        />
      </section>

      <section class="task-section">
        <h2>{{ t('task.aiReply.title') }}</h2>
        <p class="task-section__content">{{ task.aiReply || t('task.aiReply.empty') }}</p>
      </section>

      <AttachmentList :title="t('task.attachment.task')" :attachments="task.taskAttachments" />

      <section class="task-section task-section--attachments">
        <div class="task-section__heading">
          <h2>{{ t('task.attachment.report') }}</h2>
          <span class="task-attachment-count">{{ reportAttachmentCount }}/10</span>
        </div>

        <div v-if="task.reportAttachments.length || pendingReportFiles.length" class="task-attachment-list">
          <div v-for="attachment in task.reportAttachments" :key="attachment.id" class="task-attachment-row">
            <button type="button" class="task-attachment" @click="downloadAttachment(attachment)">
              <van-icon name="description-o" />
              <span class="task-attachment__name">{{ attachment.originalName }}</span>
              <span class="task-attachment__size">{{ formatFileSize(attachment.sizeBytes) }}</span>
            </button>
            <button
              v-if="!isCompleted"
              type="button"
              class="task-attachment__remove"
              :aria-label="t('task.attachment.remove')"
              @click="removePersistedAttachment(attachment)"
            >
              <van-icon name="delete-o" />
            </button>
          </div>

          <div
            v-for="(file, index) in pendingReportFiles"
            :key="pendingFileKey(file, index)"
            class="task-attachment-row"
          >
            <div class="task-attachment task-attachment--pending">
              <van-icon name="description-o" />
              <span class="task-attachment__name">{{ file.name }}</span>
              <span class="task-attachment__size">{{ formatFileSize(file.size) }}</span>
            </div>
            <button
              type="button"
              class="task-attachment__remove"
              :aria-label="t('task.attachment.remove')"
              @click="removePendingAttachment(index)"
            >
              <van-icon name="delete-o" />
            </button>
          </div>
        </div>
        <p v-else class="task-section__empty">{{ t('task.attachment.empty') }}</p>

        <label v-if="!isCompleted" class="task-upload-button">
          <van-icon name="plus" />
          <span>{{ t('task.attachment.upload') }}</span>
          <input type="file" multiple accept="*/*" @change="selectReportAttachments" />
        </label>
        <p v-if="!isCompleted" class="task-upload-tip">{{ t('task.attachment.uploadTip') }}</p>
      </section>

      <div v-if="!isCompleted" class="task-actions">
        <van-button block :loading="saving" :disabled="submitting" @click="saveDraft">
          {{ t('task.action.saveDraft') }}
        </van-button>
        <van-button type="primary" block :loading="submitting" :disabled="saving" @click="submitReport">
          {{ t('task.action.submitReport') }}
        </van-button>
      </div>
    </div>
    <van-empty v-else-if="!loading" :description="t('common.noData')" />
  </CrmPageWrapper>
</template>

<script setup lang="ts">
  import { defineComponent, h, type PropType } from 'vue';
  import { useRoute } from 'vue-router';
  import { closeToast, showConfirmDialog, showFailToast, showLoadingToast, showSuccessToast } from 'vant';
  import dayjs from 'dayjs';

  import { useI18n } from '@lib/shared/hooks/useI18n';
  import type { TaskAttachmentItem, TaskItem, TaskReportState, TaskStatus } from '@lib/shared/models/task';

  import CrmPageWrapper from '@/components/pure/crm-page-wrapper/index.vue';

  import {
    deleteTaskAttachment,
    downloadTaskAttachment,
    getTask,
    saveTaskReport,
    submitTaskReport as submitTaskReportApi,
    uploadTaskAttachments,
  } from '@/api/modules';
  import { TaskRouteEnum } from '@/enums/routeEnum';

  defineOptions({
    name: TaskRouteEnum.TASK_DETAIL,
  });

  const MAX_FILE_COUNT = 10;
  const MAX_FILE_SIZE = 50 * 1024 * 1024;

  const route = useRoute();
  const { t } = useI18n();
  const task = ref<TaskItem>();
  const reportContent = ref('');
  const pendingReportFiles = ref<File[]>([]);
  const loading = ref(false);
  const saving = ref(false);
  const submitting = ref(false);

  const statusLabelKey: Record<TaskStatus, string> = {
    PENDING: 'task.status.pending',
    IN_PROGRESS: 'task.status.inProgress',
    OVERDUE: 'task.status.overdue',
    COMPLETED: 'task.status.completed',
  };

  const statusTagType: Record<TaskStatus, 'primary' | 'success' | 'danger' | 'warning'> = {
    PENDING: 'warning',
    IN_PROGRESS: 'primary',
    OVERDUE: 'danger',
    COMPLETED: 'success',
  };

  const reportStateLabelKey: Record<TaskReportState, string> = {
    UNSAVED: 'task.reportState.unsaved',
    DRAFT: 'task.reportState.draft',
    SUBMITTED: 'task.reportState.submitted',
  };

  const reportStateTagType: Record<TaskReportState, 'primary' | 'success' | 'warning'> = {
    UNSAVED: 'warning',
    DRAFT: 'primary',
    SUBMITTED: 'success',
  };

  const isCompleted = computed(() => task.value?.status === 'COMPLETED');
  const reportAttachmentCount = computed(
    () => (task.value?.reportAttachments.length || 0) + pendingReportFiles.value.length
  );

  function formatDateTime(value?: number) {
    return value ? dayjs(value).format('YYYY-MM-DD HH:mm') : '-';
  }

  const basicInfo = computed(() => {
    if (!task.value) return [];
    return [
      { label: t('task.source'), value: t(task.value.source === 'AI' ? 'task.source.ai' : 'task.source.manager') },
      { label: t('task.assignee'), value: task.value.assigneeName || t('task.assigneePending') },
      { label: t('task.customer'), value: task.value.customerName || '-' },
      { label: t('task.deadline'), value: formatDateTime(task.value.deadline) },
      { label: t('task.createTime'), value: formatDateTime(task.value.createTime) },
      { label: t('task.startedAt'), value: formatDateTime(task.value.startedAt) },
      { label: t('task.completedAt'), value: formatDateTime(task.value.completedAt) },
      { label: t('task.reportSubmittedAt'), value: formatDateTime(task.value.reportSubmittedAt) },
    ];
  });

  function formatFileSize(size: number) {
    if (size < 1024) return `${size} B`;
    if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KB`;
    return `${(size / 1024 / 1024).toFixed(1)} MB`;
  }

  function pendingFileKey(file: File, index: number) {
    return `${file.name}-${file.size}-${file.lastModified}-${index}`;
  }

  async function downloadAttachment(attachment: TaskAttachmentItem) {
    try {
      showLoadingToast({ message: t('common.loading'), forbidClick: true, duration: 0 });
      const response = await downloadTaskAttachment(attachment.id);
      const blob = response instanceof Blob ? response : new Blob([response]);
      const url = URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = url;
      link.download = attachment.originalName;
      document.body.appendChild(link);
      link.click();
      document.body.removeChild(link);
      URL.revokeObjectURL(url);
      closeToast();
    } catch {
      closeToast();
      showFailToast(t('task.attachment.downloadFailed'));
    }
  }

  const AttachmentList = defineComponent({
    name: 'TaskAttachmentList',
    props: {
      title: {
        type: String,
        required: true,
      },
      attachments: {
        type: Array as PropType<TaskAttachmentItem[]>,
        default: () => [],
      },
    },
    setup(props) {
      return () =>
        h('section', { class: 'task-section' }, [
          h('h2', props.title),
          props.attachments.length
            ? h(
                'div',
                { class: 'task-attachment-list' },
                props.attachments.map((attachment) =>
                  h(
                    'button',
                    {
                      key: attachment.id,
                      type: 'button',
                      class: 'task-attachment',
                      onClick: () => downloadAttachment(attachment),
                    },
                    [
                      h('span', { class: 'task-attachment__name' }, attachment.originalName),
                      h('span', { class: 'task-attachment__size' }, formatFileSize(attachment.sizeBytes)),
                    ]
                  )
                )
              )
            : h('p', { class: 'task-section__empty' }, t('task.attachment.empty')),
        ]);
    },
  });

  function selectReportAttachments(event: Event) {
    const input = event.target as HTMLInputElement;
    const selectedFiles = Array.from(input.files || []);
    input.value = '';
    if (!selectedFiles.length) return;

    if (reportAttachmentCount.value + selectedFiles.length > MAX_FILE_COUNT) {
      showFailToast(t('task.attachment.tooMany'));
      return;
    }
    if (selectedFiles.some((file) => file.size > MAX_FILE_SIZE)) {
      showFailToast(t('task.attachment.tooLarge'));
      return;
    }
    pendingReportFiles.value.push(...selectedFiles);
  }

  function removePendingAttachment(index: number) {
    pendingReportFiles.value.splice(index, 1);
  }

  async function removePersistedAttachment(attachment: TaskAttachmentItem) {
    try {
      await showConfirmDialog({
        title: t('task.attachment.remove'),
        message: attachment.originalName,
      });
    } catch {
      return;
    }

    try {
      await deleteTaskAttachment(attachment.id);
      if (task.value) {
        task.value.reportAttachments = task.value.reportAttachments.filter((item) => item.id !== attachment.id);
        task.value.reportState =
          task.value.reportContent?.trim() || task.value.reportAttachments.length ? 'DRAFT' : 'UNSAVED';
      }
    } catch {
      showFailToast(t('task.message.actionFailed'));
    }
  }

  async function uploadPendingAttachments() {
    if (!task.value || !pendingReportFiles.value.length) return;
    await uploadTaskAttachments(task.value.id, 'REPORT', pendingReportFiles.value);
    pendingReportFiles.value = [];
  }

  async function saveDraft() {
    if (!task.value || saving.value) return;
    saving.value = true;
    try {
      await uploadPendingAttachments();
      task.value = await saveTaskReport({
        id: task.value.id,
        reportContent: reportContent.value.trim() || undefined,
        aiReply: task.value.aiReply || undefined,
      });
      reportContent.value = task.value.reportContent || '';
      showSuccessToast(t('task.message.draftSaved'));
    } catch {
      showFailToast(t('task.message.actionFailed'));
      await loadTask(false);
    } finally {
      saving.value = false;
    }
  }

  async function submitReport() {
    if (!task.value || submitting.value) return;
    if (!reportContent.value.trim() && reportAttachmentCount.value === 0) {
      showFailToast(t('task.message.reportRequired'));
      return;
    }

    submitting.value = true;
    try {
      await uploadPendingAttachments();
      task.value = await submitTaskReportApi({
        id: task.value.id,
        reportContent: reportContent.value.trim() || undefined,
        aiReply: task.value.aiReply || undefined,
      });
      reportContent.value = task.value.reportContent || '';
      showSuccessToast(t('task.message.reportSubmitted'));
    } catch {
      showFailToast(t('task.message.actionFailed'));
      await loadTask(false);
    } finally {
      submitting.value = false;
    }
  }

  async function loadTask(showLoading = true) {
    const id = route.query.id?.toString();
    if (!id) return;
    loading.value = true;
    if (showLoading) {
      showLoadingToast({ message: t('common.loading'), forbidClick: true, duration: 0 });
    }
    try {
      task.value = await getTask(id);
      reportContent.value = task.value.reportContent || '';
    } finally {
      loading.value = false;
      if (showLoading) closeToast();
    }
  }

  onBeforeMount(() => loadTask());
</script>

<style lang="less" scoped>
  .task-detail {
    display: flex;
    padding: 12px 12px 28px;
    min-height: 100%;
    background: var(--text-n9);
    flex-direction: column;
    gap: 12px;
  }
  .task-detail__summary,
  .task-section {
    padding: 16px;
    border: 1px solid var(--text-n8);
    border-radius: 8px;
    background: var(--text-n10);
  }
  .task-detail__heading,
  .task-section__heading {
    display: flex;
    justify-content: space-between;
    align-items: flex-start;
    gap: 12px;
  }
  .task-detail__heading h1 {
    margin: 0;
    min-width: 0;
    font-size: 18px;
    font-weight: 600;
    color: var(--text-n1);
    overflow-wrap: anywhere;
    line-height: 26px;
    flex: 1;
  }
  .task-detail__heading :deep(.van-tag),
  .task-section__heading :deep(.van-tag) {
    flex-shrink: 0;
  }
  .task-detail__description,
  .task-section__content {
    margin: 12px 0 0;
    font-size: 14px;
    line-height: 22px;
    white-space: pre-wrap;
    overflow-wrap: anywhere;
    color: var(--text-n2);
  }
  .task-section h2 {
    margin: 0;
    font-size: 15px;
    font-weight: 600;
    line-height: 22px;
    color: var(--text-n1);
  }
  .task-info-grid {
    display: grid;
    margin-top: 14px;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 14px 16px;
  }
  .task-info-item {
    display: flex;
    min-width: 0;
    flex-direction: column;
    gap: 4px;
  }
  .task-info-item span {
    font-size: 12px;
    line-height: 18px;
    color: var(--text-n4);
  }
  .task-info-item strong {
    font-size: 14px;
    font-weight: 400;
    line-height: 21px;
    overflow-wrap: anywhere;
    color: var(--text-n2);
  }
  .task-report-input {
    margin-top: 12px;
    padding: 10px 12px;
    border: 1px solid var(--text-n7);
    border-radius: 6px;
    background: var(--text-n10);
  }
  .task-report-input:focus-within {
    border-color: var(--primary-8);
  }
  .task-report-input :deep(.van-field__control) {
    min-height: 88px;
    line-height: 22px;
  }
  .task-section__empty {
    margin: 12px 0 0;
    font-size: 13px;
    color: var(--text-n4);
  }
  .task-attachment-count {
    font-size: 12px;
    color: var(--text-n4);
    line-height: 22px;
  }
  :deep(.task-attachment-list),
  .task-attachment-list {
    display: flex;
    margin-top: 12px;
    flex-direction: column;
    gap: 8px;
  }
  .task-attachment-row {
    display: flex;
    min-width: 0;
    gap: 8px;
  }
  :deep(.task-attachment),
  .task-attachment {
    display: flex;
    align-items: center;
    padding: 10px 12px;
    width: 100%;
    min-width: 0;
    border: 1px solid var(--text-n8);
    border-radius: 6px;
    text-align: left;
    background: var(--text-n10);
    gap: 8px;
    flex: 1;
  }
  :deep(button.task-attachment:active),
  button.task-attachment:active {
    background: var(--text-n9);
  }
  .task-attachment--pending {
    border-style: dashed;
  }
  :deep(.task-attachment__name),
  .task-attachment__name {
    overflow: hidden;
    min-width: 0;
    font-size: 13px;
    text-overflow: ellipsis;
    white-space: nowrap;
    color: var(--primary-8);
    line-height: 20px;
    flex: 1;
  }
  :deep(.task-attachment__size),
  .task-attachment__size {
    flex-shrink: 0;
    font-size: 12px;
    color: var(--text-n4);
  }
  .task-attachment__remove {
    display: flex;
    justify-content: center;
    align-items: center;
    width: 40px;
    min-width: 40px;
    font-size: 18px;
    border: 1px solid var(--text-n8);
    border-radius: 6px;
    color: var(--error-1);
    background: var(--text-n10);
  }
  .task-upload-button {
    position: relative;
    display: flex;
    justify-content: center;
    align-items: center;
    overflow: hidden;
    margin-top: 12px;
    min-height: 44px;
    font-size: 14px;
    border: 1px dashed var(--primary-8);
    border-radius: 6px;
    color: var(--primary-8);
    background: var(--primary-7);
    gap: 6px;
  }
  .task-upload-button input {
    position: absolute;
    width: 1px;
    height: 1px;
    opacity: 0;
  }
  .task-upload-tip {
    margin: 8px 0 0;
    font-size: 12px;
    line-height: 18px;
    color: var(--text-n4);
  }
  .task-actions {
    display: grid;
    padding: 4px 0 0;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 10px;
  }
  .task-actions :deep(.van-button) {
    min-width: 0;
    height: 44px;
    border-radius: 6px;
  }
</style>
