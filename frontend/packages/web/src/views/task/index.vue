<template>
  <div class="h-full">
    <CrmCard :title="t('task.title')" hide-footer no-content-padding>
      <div class="flex h-full min-h-0 flex-col">
        <div class="task-toolbar">
          <div class="flex min-w-0 items-center gap-[12px]">
            <n-input
              v-model:value="keyword"
              clearable
              class="task-search w-[280px]"
              :placeholder="t('task.searchPlaceholder')"
            >
              <template #prefix>
                <CrmIcon type="iconicon_search" :size="16" />
              </template>
            </n-input>
            <n-select
              v-model:value="status"
              clearable
              class="task-status-filter w-[160px]"
              :options="statusOptions"
              :placeholder="t('task.allStatus')"
            />
            <n-select
              v-if="isTaskManager"
              v-model:value="assigneeFilter"
              clearable
              filterable
              class="task-assignee-filter w-[190px]"
              :options="assigneeFilterOptions"
              :placeholder="t('task.allAssignees')"
            />
            <n-tooltip trigger="hover">
              <template #trigger>
                <n-button quaternary circle :aria-label="t('task.resetFilters')" @click="resetFilters">
                  <template #icon>
                    <CrmIcon type="iconicon_refresh" :size="16" />
                  </template>
                </n-button>
              </template>
              {{ t('task.resetFilters') }}
            </n-tooltip>
          </div>
          <div class="task-toolbar__summary">
            <span class="shrink-0 text-[var(--text-n4)]">
              {{ t('task.total', { count: taskTotal }) }}
            </span>
            <n-button v-if="isTaskManager" type="primary" @click="openCreateTask">
              <template #icon>
                <CrmIcon type="iconicon_add" :size="16" />
              </template>
              {{ t('task.manage.create') }}
            </n-button>
          </div>
        </div>

        <div class="task-list">
          <n-spin v-if="listLoading" class="task-list__loading" />
          <div v-else-if="filteredTasks.length" class="task-card-list">
            <article
              v-for="task in filteredTasks"
              :key="task.id"
              class="task-card"
              :class="[`task-card--${task.status.toLowerCase()}`, { 'task-card--expanded': isTaskExpanded(task.id) }]"
            >
              <div
                class="task-card__summary"
                role="button"
                tabindex="0"
                :aria-expanded="isTaskExpanded(task.id)"
                :aria-label="t(isTaskExpanded(task.id) ? 'task.collapse' : 'task.expand')"
                @click="toggleTask(task.id)"
                @keydown.enter="toggleTask(task.id)"
                @keydown.space.prevent="toggleTask(task.id)"
              >
                <div class="task-card__main">
                  <div class="task-card__heading">
                    <h3 class="task-card__title">{{ task.name }}</h3>
                    <n-tag :bordered="false" :type="statusTagType[task.status]">
                      {{ t(statusLabelKey[task.status]) }}
                    </n-tag>
                  </div>
                  <div class="task-card__meta">
                    <span>
                      <span class="task-card__meta-label">{{ t('task.source') }}</span>
                      {{ t(sourceLabelKey[task.source]) }}
                    </span>
                    <span>
                      <span class="task-card__meta-label">{{ t('task.assignee') }}</span>
                      <n-tag v-if="!task.assigneeId" size="small" :bordered="false" type="error">
                        {{ t('task.assigneePending') }}
                      </n-tag>
                      <template v-else>{{ task.assigneeName }}</template>
                    </span>
                    <span v-if="task.customerName">
                      <span class="task-card__meta-label">{{ t('task.customer') }}</span>
                      {{ task.customerName }}
                    </span>
                  </div>
                </div>
                <div class="task-card__schedule">
                  <div class="task-card__schedule-item">
                    <span class="task-card__label">{{ t('task.deadline') }}</span>
                    <time>{{ formatDateTime(task.deadline) }}</time>
                  </div>
                  <div class="task-card__schedule-item">
                    <span class="task-card__label">{{ t('task.createTime') }}</span>
                    <time>{{ formatDateTime(task.createTime) }}</time>
                  </div>
                </div>
                <div v-if="isTaskManager" class="task-card__management" @click.stop>
                  <n-tooltip v-if="task.status !== 'COMPLETED'" trigger="hover">
                    <template #trigger>
                      <n-button
                        quaternary
                        circle
                        :aria-label="t('task.manage.reassign')"
                        @click.stop="openReassignTask(task)"
                      >
                        <template #icon>
                          <CrmIcon type="iconicon_user" :size="16" />
                        </template>
                      </n-button>
                    </template>
                    {{ t('task.manage.reassign') }}
                  </n-tooltip>
                  <n-tooltip trigger="hover">
                    <template #trigger>
                      <n-button quaternary circle :aria-label="t('task.manage.edit')" @click.stop="openEditTask(task)">
                        <template #icon>
                          <CrmIcon type="iconicon_edit" :size="16" />
                        </template>
                      </n-button>
                    </template>
                    {{ t('task.manage.edit') }}
                  </n-tooltip>
                  <n-popconfirm @positive-click="deleteTask(task)">
                    <template #trigger>
                      <n-button
                        quaternary
                        circle
                        class="task-card__delete-button"
                        :aria-label="t('task.manage.delete')"
                        @click.stop
                      >
                        <template #icon>
                          <CrmIcon type="iconicon_delete" :size="16" />
                        </template>
                      </n-button>
                    </template>
                    {{ t('task.manage.deleteConfirm') }}
                  </n-popconfirm>
                </div>
              </div>

              <n-collapse-transition :show="isTaskExpanded(task.id)">
                <div class="task-detail">
                  <div
                    v-if="task.description || task.customerName || task.taskAttachments.length"
                    class="task-assignment-info"
                  >
                    <div v-if="task.customerName" class="task-assignment-info__item">
                      <span class="task-card__label">{{ t('task.customer') }}</span>
                      <span>{{ task.customerName }}</span>
                    </div>
                    <div
                      v-if="task.description"
                      class="task-assignment-info__item task-assignment-info__item--description"
                    >
                      <span class="task-card__label">{{ t('task.manage.description') }}</span>
                      <span>{{ task.description }}</span>
                    </div>
                    <div v-if="task.taskAttachments.length" class="task-assignment-info__item">
                      <span class="task-card__label">{{ t('task.manage.attachments') }}</span>
                      <span class="task-assignment-info__files">
                        <n-button
                          v-for="attachment in task.taskAttachments"
                          :key="attachment.id"
                          text
                          type="primary"
                          @click="downloadAttachmentFile(attachment.id, attachment.name)"
                        >
                          {{ attachment.name }}
                        </n-button>
                      </span>
                    </div>
                  </div>
                  <div class="task-detail__content">
                    <section class="task-detail__section task-detail__section--report">
                      <div class="task-detail__heading">
                        <div>
                          <h4>{{ t('task.report.title') }}</h4>
                          <p>{{ t('task.report.description') }}</p>
                        </div>
                        <n-tag size="small" :bordered="false" :type="reportStateTagType[task.reportState]">
                          {{ t(reportStateLabelKey[task.reportState]) }}
                        </n-tag>
                      </div>
                      <n-input
                        v-model:value="task.reportContent"
                        type="textarea"
                        :autosize="{ minRows: 5, maxRows: 10 }"
                        :placeholder="t('task.report.placeholder')"
                        :disabled="!canOperateTask(task) || task.status === 'COMPLETED'"
                        @paste="handleReportPaste(task, $event)"
                        @update:value="markTaskDirty(task)"
                      />
                      <div class="task-detail__attachment-heading">
                        <span>{{ t('task.attachment.title') }}</span>
                        <span>{{ t('task.attachment.count', { count: task.attachments.length }) }}</span>
                      </div>
                      <n-upload
                        v-model:file-list="task.attachments"
                        multiple
                        :default-upload="false"
                        :max="10"
                        :disabled="!canOperateTask(task) || task.status === 'COMPLETED'"
                        @before-upload="handleBeforeAttachmentUpload"
                        @change="markTaskDirty(task)"
                        @download="downloadUploadFile"
                      >
                        <n-upload-dragger>
                          <div class="task-upload">
                            <CrmIcon type="iconicon_upload" :size="22" />
                            <div>
                              <strong>{{ t('task.attachment.upload') }}</strong>
                              <span>{{ t('task.attachment.tip') }}</span>
                            </div>
                          </div>
                        </n-upload-dragger>
                      </n-upload>
                    </section>

                    <section class="task-detail__section task-detail__section--reply">
                      <div class="task-detail__heading">
                        <div>
                          <h4>{{ t('task.aiReply.title') }}</h4>
                          <p>{{ t('task.aiReply.description') }}</p>
                        </div>
                        <div class="flex items-center gap-[8px]">
                          <n-button
                            v-if="isTaskManager"
                            quaternary
                            circle
                            :loading="aiGeneratingTaskIds.includes(task.id)"
                            :aria-label="t('task.action.regenerateReply')"
                            @click="regenerateReply(task)"
                          >
                            <template #icon>
                              <CrmIcon type="iconicon_refresh" :size="16" />
                            </template>
                          </n-button>
                          <n-tag size="small" :bordered="false" type="info">{{ t('task.aiReply.generated') }}</n-tag>
                        </div>
                      </div>
                      <n-input
                        v-model:value="task.aiReply"
                        type="textarea"
                        :autosize="{ minRows: 10, maxRows: 16 }"
                        :placeholder="t('task.aiReply.placeholder')"
                        :disabled="!canOperateTask(task) || task.status === 'COMPLETED'"
                        @update:value="markTaskDirty(task)"
                      />
                    </section>
                  </div>

                  <div class="task-detail__actions">
                    <n-button :disabled="!task.aiReply.trim()" @click="copyReply(task)">
                      <template #icon>
                        <CrmIcon type="iconicon_file_copy" :size="16" />
                      </template>
                      {{ t('task.action.copyReply') }}
                    </n-button>
                    <n-button
                      :disabled="!canOperateTask(task) || Boolean(task.startedAt) || task.status === 'COMPLETED'"
                      :loading="taskActionIds.includes(task.id)"
                      @click="startTask(task)"
                    >
                      <template #icon>
                        <CrmIcon type="iconicon_play_circle" :size="16" />
                      </template>
                      {{ t('task.action.start') }}
                    </n-button>
                    <n-button
                      :disabled="!canOperateTask(task) || task.status === 'COMPLETED'"
                      :loading="taskActionIds.includes(task.id)"
                      @click="saveDraft(task)"
                    >
                      <template #icon>
                        <CrmIcon type="iconicon_save" :size="16" />
                      </template>
                      {{ t('task.action.saveDraft') }}
                    </n-button>
                    <n-button
                      type="primary"
                      :disabled="!canOperateTask(task) || task.status === 'COMPLETED'"
                      :loading="taskActionIds.includes(task.id)"
                      @click="submitReport(task)"
                    >
                      <template #icon>
                        <CrmIcon type="iconicon_send_colorful" :size="16" />
                      </template>
                      {{ t('task.action.submitReport') }}
                    </n-button>
                  </div>
                </div>
              </n-collapse-transition>
            </article>
          </div>
          <n-empty v-else :description="t('task.empty')" class="task-empty" />
        </div>
      </div>
    </CrmCard>

    <n-modal
      v-model:show="taskModalVisible"
      preset="card"
      :title="taskModalTitle"
      class="task-modal"
      :mask-closable="false"
    >
      <n-form label-placement="top" class="task-form">
        <template v-if="taskModalMode !== 'REASSIGN'">
          <n-form-item :label="t('task.manage.name')" required>
            <n-input v-model:value="taskForm.name" :placeholder="t('task.manage.namePlaceholder')" />
          </n-form-item>
          <div class="task-form__row">
            <n-form-item :label="t('task.assignee')" required>
              <n-select
                v-model:value="taskForm.assigneeId"
                :options="assigneeOptions"
                :disabled="taskForm.completed"
                :placeholder="t('task.manage.assigneePlaceholder')"
              />
            </n-form-item>
            <n-form-item :label="t('task.deadline')" required>
              <n-date-picker
                v-model:value="taskForm.deadline"
                type="datetime"
                clearable
                class="w-full"
                :placeholder="t('task.manage.deadlinePlaceholder')"
              />
            </n-form-item>
          </div>
          <div class="task-form__row">
            <n-form-item :label="t('task.customer')">
              <n-select
                v-model:value="taskForm.customerId"
                clearable
                filterable
                remote
                :loading="customerOptionsLoading"
                :options="customerOptions"
                :placeholder="t('task.manage.customerPlaceholder')"
                @search="searchCustomerOptions"
              />
            </n-form-item>
            <n-form-item :label="t('task.manage.description')">
              <n-input v-model:value="taskForm.description" :placeholder="t('task.manage.descriptionPlaceholder')" />
            </n-form-item>
          </div>
          <n-form-item :label="t('task.manage.attachments')">
            <n-upload
              v-model:file-list="taskForm.attachments"
              multiple
              :default-upload="false"
              :max="10"
              @before-upload="handleBeforeAttachmentUpload"
              @download="downloadUploadFile"
            >
              <n-upload-dragger>
                <div class="task-form__upload">
                  <CrmIcon type="iconicon_upload" :size="20" />
                  <span>{{ t('task.manage.attachmentUpload') }}</span>
                </div>
              </n-upload-dragger>
            </n-upload>
          </n-form-item>
        </template>
        <n-form-item v-else :label="t('task.manage.assignee')" required>
          <n-select
            v-model:value="taskForm.assigneeId"
            :options="assigneeOptions"
            :placeholder="t('task.manage.assigneePlaceholder')"
          />
        </n-form-item>
        <div class="task-modal__actions">
          <n-button @click="taskModalVisible = false">{{ t('common.cancel') }}</n-button>
          <n-button type="primary" :loading="taskFormSaving" @click="saveTaskForm">{{ t('common.confirm') }}</n-button>
        </div>
      </n-form>
    </n-modal>
  </div>
</template>

<script setup lang="ts">
  import { computed, onMounted, ref, watch } from 'vue';
  import {
    NButton,
    NCollapseTransition,
    NDatePicker,
    NEmpty,
    NForm,
    NFormItem,
    NInput,
    NModal,
    NPopconfirm,
    NSelect,
    NSpin,
    NTag,
    NTooltip,
    NUpload,
    NUploadDragger,
    useMessage,
  } from 'naive-ui';
  import { debounce } from 'lodash-es';

  import { useI18n } from '@lib/shared/hooks/useI18n';
  import type {
    TaskAttachmentItem,
    TaskAttachmentScene,
    TaskItem,
    TaskReportState,
    TaskSource,
    TaskStatus,
  } from '@lib/shared/models/task';

  import CrmCard from '@/components/pure/crm-card/index.vue';
  import CrmIcon from '@/components/pure/crm-icon-font/index.vue';

  import {
    addTask,
    deleteTask as deleteTaskApi,
    deleteTaskAttachment,
    downloadTaskAttachment,
    getTaskAssigneeOptions,
    getTaskCustomerOptions,
    getTaskPage,
    reassignTask,
    regenerateTaskAiReply,
    saveTaskReport,
    startTask as startTaskApi,
    submitTaskReport,
    updateTask,
    uploadTaskAttachments,
  } from '@/api/modules';
  import useLegacyCopy from '@/hooks/useLegacyCopy';
  import useUserStore from '@/store/modules/user';
  import { hasAnyPermission } from '@/utils/permission';

  import type { SelectOption, TagProps, UploadFileInfo } from 'naive-ui';

  type TaskModalMode = 'CREATE' | 'EDIT' | 'REASSIGN';

  interface TaskListItem
    extends Omit<
      TaskItem,
      'taskAttachments' | 'reportAttachments' | 'description' | 'customerName' | 'reportContent' | 'aiReply'
    > {
    description: string;
    customerName: string;
    reportContent: string;
    aiReply: string;
    taskAttachments: UploadFileInfo[];
    attachments: UploadFileInfo[];
    persistedReportAttachmentIds: string[];
  }

  interface TaskFormState {
    id: string;
    name: string;
    assigneeId: string | null;
    deadline: number | null;
    description: string;
    customerId: string | null;
    attachments: UploadFileInfo[];
    persistedTaskAttachmentIds: string[];
    completed: boolean;
  }

  function createTaskForm(): TaskFormState {
    return {
      id: '',
      name: '',
      assigneeId: null,
      deadline: Date.now() + 24 * 60 * 60 * 1000,
      description: '',
      customerId: null,
      attachments: [],
      persistedTaskAttachmentIds: [],
      completed: false,
    };
  }

  const { t } = useI18n();
  const message = useMessage();
  const { legacyCopy } = useLegacyCopy();
  const userStore = useUserStore();

  const keyword = ref('');
  const status = ref<TaskStatus | null>(null);
  const assigneeFilter = ref<string | null>(null);
  const expandedTaskIds = ref<string[]>([]);
  const taskModalVisible = ref(false);
  const taskModalMode = ref<TaskModalMode>('CREATE');
  const taskForm = ref<TaskFormState>(createTaskForm());
  const tasks = ref<TaskListItem[]>([]);
  const taskTotal = ref(0);
  const listLoading = ref(false);
  const taskFormSaving = ref(false);
  const taskActionIds = ref<string[]>([]);
  const aiGeneratingTaskIds = ref<string[]>([]);
  const assigneeOptions = ref<SelectOption[]>([]);
  const customerOptions = ref<SelectOption[]>([]);
  const customerOptionsLoading = ref(false);

  const taskModalTitle = computed(() => {
    if (taskModalMode.value === 'REASSIGN') {
      return t('task.manage.reassignTitle');
    }
    return taskModalMode.value === 'EDIT' ? t('task.manage.editTitle') : t('task.manage.createTitle');
  });

  const isTaskManager = computed(() => {
    if (userStore.isAdmin || hasAnyPermission(['TASK:ADD', 'TASK:UPDATE', 'TASK:DELETE'])) {
      return true;
    }
    return (userStore.userInfo.roles as unknown[]).some((role) => {
      const roleId = typeof role === 'string' ? role : String((role as { id?: string })?.id || '');
      const roleName = typeof role === 'string' ? role : String((role as { name?: string })?.name || '');
      return (
        ['org_admin', 'sales_manager'].includes(roleId) ||
        ['管理员', '销售经理', 'Organization Administrator', 'Sales Manager'].includes(roleName)
      );
    });
  });

  const canExecuteTasks = computed(() => userStore.isAdmin || hasAnyPermission(['TASK:EXECUTE']));

  const statusOptions = computed<SelectOption[]>(() => [
    { label: t('task.status.pending'), value: 'PENDING' },
    { label: t('task.status.inProgress'), value: 'IN_PROGRESS' },
    { label: t('task.status.overdue'), value: 'OVERDUE' },
    { label: t('task.status.completed'), value: 'COMPLETED' },
  ]);

  const assigneeFilterOptions = computed<SelectOption[]>(() => [
    { label: t('task.unassigned'), value: '__UNASSIGNED__' },
    ...assigneeOptions.value,
  ]);

  const statusTagType: Record<TaskStatus, TagProps['type']> = {
    PENDING: 'warning',
    IN_PROGRESS: 'info',
    OVERDUE: 'error',
    COMPLETED: 'success',
  };

  const statusLabelKey: Record<TaskStatus, string> = {
    PENDING: 'task.status.pending',
    IN_PROGRESS: 'task.status.inProgress',
    OVERDUE: 'task.status.overdue',
    COMPLETED: 'task.status.completed',
  };

  const sourceLabelKey: Record<TaskSource, string> = {
    AI: 'task.source.ai',
    MANAGER: 'task.source.manager',
  };

  const reportStateTagType: Record<TaskReportState, TagProps['type']> = {
    UNSAVED: 'default',
    DRAFT: 'warning',
    SUBMITTED: 'success',
  };

  const reportStateLabelKey: Record<TaskReportState, string> = {
    UNSAVED: 'task.reportState.unsaved',
    DRAFT: 'task.reportState.draft',
    SUBMITTED: 'task.reportState.submitted',
  };

  const filteredTasks = computed(() => tasks.value);

  function formatDateTime(timestamp: number) {
    const date = new Date(timestamp);
    const pad = (value: number) => String(value).padStart(2, '0');
    return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(
      date.getMinutes()
    )}`;
  }

  function getAssigneeName(assigneeId: string | null) {
    return assigneeOptions.value.find((option) => option.value === assigneeId)?.label?.toString() || '';
  }

  function attachmentToUploadFile(attachment: TaskAttachmentItem): UploadFileInfo {
    return {
      id: attachment.id,
      name: attachment.originalName,
      status: 'finished',
    };
  }

  function mapTaskItem(task: TaskItem): TaskListItem {
    return {
      ...task,
      description: task.description || '',
      assigneeName: task.assigneeName || '',
      customerName: task.customerName || '',
      reportContent: task.reportContent || '',
      aiReply: task.aiReply || '',
      taskAttachments: task.taskAttachments.map(attachmentToUploadFile),
      attachments: task.reportAttachments.map(attachmentToUploadFile),
      persistedReportAttachmentIds: task.reportAttachments.map((attachment) => attachment.id),
    };
  }

  let latestListRequest = 0;
  async function loadTasks() {
    const requestId = ++latestListRequest;
    listLoading.value = true;
    try {
      const result = await getTaskPage({
        current: 1,
        pageSize: 500,
        keyword: keyword.value.trim() || undefined,
        status: status.value || undefined,
        assigneeId: isTaskManager.value ? assigneeFilter.value || undefined : undefined,
      });
      if (requestId !== latestListRequest) return;
      tasks.value = result.list.map(mapTaskItem);
      taskTotal.value = result.total;
    } finally {
      if (requestId === latestListRequest) {
        listLoading.value = false;
      }
    }
  }

  async function loadAssigneeOptions() {
    const options = await getTaskAssigneeOptions();
    assigneeOptions.value = options.map((item) => ({ label: item.name, value: item.id }));
  }

  async function loadCustomerOptions(keywordValue = '') {
    customerOptionsLoading.value = true;
    try {
      const options = await getTaskCustomerOptions(keywordValue);
      customerOptions.value = options.map((item) => ({ label: item.name, value: item.id }));
    } finally {
      customerOptionsLoading.value = false;
    }
  }

  const searchCustomerOptions = debounce((query: string) => {
    loadCustomerOptions(query);
  }, 200);

  const reloadTasksByFilter = debounce(() => {
    loadTasks();
  }, 250);

  function canOperateTask(task: TaskListItem) {
    return (
      Boolean(task.assigneeId) &&
      canExecuteTasks.value &&
      (userStore.isAdmin || isTaskManager.value || task.assigneeId === userStore.userInfo.id)
    );
  }

  function resetFilters() {
    keyword.value = '';
    status.value = null;
    assigneeFilter.value = null;
  }

  function openCreateTask() {
    taskModalMode.value = 'CREATE';
    taskForm.value = createTaskForm();
    taskModalVisible.value = true;
    loadCustomerOptions();
  }

  function openEditTask(task: TaskListItem) {
    if (task.customerId && !customerOptions.value.some((option) => option.value === task.customerId)) {
      customerOptions.value.push({ label: task.customerName || task.customerId, value: task.customerId });
    }
    taskModalMode.value = 'EDIT';
    taskForm.value = {
      id: task.id,
      name: task.name,
      assigneeId: task.assigneeId || null,
      deadline: task.deadline,
      description: task.description || '',
      customerId: task.customerId || null,
      attachments: [...task.taskAttachments],
      persistedTaskAttachmentIds: task.taskAttachments.map((attachment) => attachment.id),
      completed: task.status === 'COMPLETED',
    };
    taskModalVisible.value = true;
  }

  function openReassignTask(task: TaskListItem) {
    taskModalMode.value = 'REASSIGN';
    taskForm.value = {
      ...createTaskForm(),
      id: task.id,
      assigneeId: task.assigneeId || null,
      completed: false,
    };
    taskModalVisible.value = true;
  }

  async function syncTaskAttachments(
    taskId: string,
    scene: TaskAttachmentScene,
    fileList: UploadFileInfo[],
    persistedIds: string[]
  ) {
    const retainedIds = fileList.map((file) => file.id).filter((id) => persistedIds.includes(id));
    const removedIds = persistedIds.filter((id) => !retainedIds.includes(id));
    await Promise.all(removedIds.map((attachmentId) => deleteTaskAttachment(attachmentId)));
    const files = fileList
      .filter((file) => !persistedIds.includes(file.id))
      .map((file) => file.file)
      .filter((file): file is File => Boolean(file));
    if (files.length) {
      await uploadTaskAttachments(taskId, scene, files);
    }
  }

  async function saveTaskForm() {
    const form = taskForm.value;
    const assigneeName = getAssigneeName(form.assigneeId);
    if (!assigneeName) {
      message.warning(t('task.message.assigneeRequired'));
      return;
    }

    if (taskModalMode.value === 'REASSIGN') {
      taskFormSaving.value = true;
      try {
        await reassignTask({ id: form.id, assigneeId: form.assigneeId || '' });
        message.success(t('task.message.reassigned'));
        taskModalVisible.value = false;
        await loadTasks();
      } finally {
        taskFormSaving.value = false;
      }
      return;
    }

    if (!form.name.trim()) {
      message.warning(t('task.message.nameRequired'));
      return;
    }
    if (!form.deadline) {
      message.warning(t('task.message.deadlineRequired'));
      return;
    }

    taskFormSaving.value = true;
    try {
      const wasCreate = taskModalMode.value === 'CREATE';
      const payload = {
        name: form.name.trim(),
        assigneeId: form.assigneeId || '',
        deadline: form.deadline,
        description: form.description.trim() || undefined,
        customerId: form.customerId || undefined,
      };
      const savedTask = wasCreate ? await addTask(payload) : await updateTask({ id: form.id, ...payload });
      if (wasCreate) {
        taskForm.value.id = savedTask.id;
        taskForm.value.persistedTaskAttachmentIds = savedTask.taskAttachments.map((attachment) => attachment.id);
        taskModalMode.value = 'EDIT';
      }
      await syncTaskAttachments(
        savedTask.id,
        'TASK',
        form.attachments,
        wasCreate ? [] : form.persistedTaskAttachmentIds
      );
      if (wasCreate) {
        expandedTaskIds.value = [savedTask.id, ...expandedTaskIds.value];
        message.success(t('task.message.created'));
      } else {
        message.success(t('task.message.updated'));
      }
      taskModalVisible.value = false;
      await loadTasks();
    } finally {
      taskFormSaving.value = false;
    }
  }

  async function deleteTask(task: TaskListItem) {
    await deleteTaskApi(task.id);
    expandedTaskIds.value = expandedTaskIds.value.filter((id) => id !== task.id);
    message.success(t('task.message.deleted'));
    await loadTasks();
  }

  function isTaskExpanded(taskId: string) {
    return expandedTaskIds.value.includes(taskId);
  }

  function toggleTask(taskId: string) {
    expandedTaskIds.value = isTaskExpanded(taskId)
      ? expandedTaskIds.value.filter((id) => id !== taskId)
      : [...expandedTaskIds.value, taskId];
  }

  function markTaskDirty(task: TaskListItem) {
    if (!canOperateTask(task) || task.status === 'COMPLETED') return;
    task.reportState = 'UNSAVED';
  }

  function beforeAttachmentUpload(file: UploadFileInfo) {
    const maxSize = 50 * 1024 * 1024;
    if (file.file?.size && file.file.size > maxSize) {
      message.warning(t('task.message.attachmentTooLarge'));
      return false;
    }
    return true;
  }

  function handleBeforeAttachmentUpload(options: { file: UploadFileInfo }) {
    return beforeAttachmentUpload(options.file);
  }

  function handleReportPaste(task: TaskListItem, event: ClipboardEvent) {
    if (!canOperateTask(task) || task.status === 'COMPLETED') return;
    const imageItems = Array.from(event.clipboardData?.items ?? []).filter((item) => item.type.startsWith('image/'));
    if (!imageItems.length) {
      return;
    }

    if (task.attachments.length + imageItems.length > 10) {
      message.warning(t('task.message.attachmentCountExceeded'));
      return;
    }

    event.preventDefault();
    imageItems.forEach((item, index) => {
      const file = item.getAsFile();
      if (!file) {
        return;
      }
      const extension = file.type.split('/')[1] || 'png';
      task.attachments.push({
        id: `${task.id}-paste-${Date.now()}-${index}`,
        name: `${t('task.attachment.screenshot')}-${Date.now()}.${extension}`,
        status: 'pending',
        file,
      });
    });
    markTaskDirty(task);
    message.success(t('task.message.screenshotAdded'));
  }

  function hasReportContent(task: TaskListItem) {
    return Boolean(task.reportContent?.trim() || task.attachments.length);
  }

  function replaceTask(updatedTask: TaskItem) {
    const index = tasks.value.findIndex((task) => task.id === updatedTask.id);
    if (index !== -1) {
      tasks.value[index] = mapTaskItem(updatedTask);
    }
  }

  async function runTaskAction(task: TaskListItem, action: () => Promise<TaskItem>) {
    if (taskActionIds.value.includes(task.id)) return;
    taskActionIds.value.push(task.id);
    try {
      const updatedTask = await action();
      replaceTask(updatedTask);
    } finally {
      taskActionIds.value = taskActionIds.value.filter((id) => id !== task.id);
    }
  }

  async function saveDraft(task: TaskListItem) {
    await runTaskAction(task, async () => {
      await syncTaskAttachments(task.id, 'REPORT', task.attachments, task.persistedReportAttachmentIds);
      const updatedTask = await saveTaskReport({
        id: task.id,
        reportContent: task.reportContent || undefined,
        aiReply: task.aiReply || undefined,
      });
      message.success(t('task.message.draftSaved'));
      return updatedTask;
    });
  }

  async function submitReport(task: TaskListItem) {
    if (!hasReportContent(task)) {
      message.warning(t('task.message.reportRequired'));
      return;
    }
    await runTaskAction(task, async () => {
      await syncTaskAttachments(task.id, 'REPORT', task.attachments, task.persistedReportAttachmentIds);
      const updatedTask = await submitTaskReport({
        id: task.id,
        reportContent: task.reportContent || undefined,
        aiReply: task.aiReply || undefined,
      });
      message.success(t('task.message.reportSubmitted'));
      return updatedTask;
    });
  }

  async function startTask(task: TaskListItem) {
    if (task.startedAt || task.status === 'COMPLETED') return;
    await runTaskAction(task, async () => {
      const updatedTask = await startTaskApi(task.id);
      message.success(t('task.message.taskStarted'));
      return updatedTask;
    });
  }

  async function regenerateReply(task: TaskListItem) {
    if (aiGeneratingTaskIds.value.includes(task.id)) return;
    aiGeneratingTaskIds.value.push(task.id);
    try {
      replaceTask(await regenerateTaskAiReply(task.id));
      message.success(t('task.message.replyRegenerated'));
    } finally {
      aiGeneratingTaskIds.value = aiGeneratingTaskIds.value.filter((id) => id !== task.id);
    }
  }

  async function downloadAttachmentFile(id: string, name: string) {
    const response = await downloadTaskAttachment(id);
    const url = URL.createObjectURL(response instanceof Blob ? response : new Blob([response]));
    const link = document.createElement('a');
    link.href = url;
    link.download = name;
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
    URL.revokeObjectURL(url);
  }

  async function downloadUploadFile(file: UploadFileInfo) {
    if (file.status === 'finished') {
      await downloadAttachmentFile(file.id, file.name);
    }
    return false;
  }

  function copyReply(task: TaskListItem) {
    legacyCopy(task.aiReply || '');
  }

  watch([keyword, status, assigneeFilter], reloadTasksByFilter);

  onMounted(async () => {
    await loadTasks();
    if (isTaskManager.value) {
      await Promise.all([loadAssigneeOptions(), loadCustomerOptions()]);
    }
  });
</script>

<style lang="less" scoped>
  .task-toolbar {
    display: flex;
    justify-content: space-between;
    align-items: center;
    padding: 10px 16px;
    min-height: 56px;
    border-bottom: 1px solid var(--text-n8);
    gap: 16px;
  }
  .task-toolbar__summary {
    display: flex;
    align-items: center;
    gap: 12px;
  }
  .task-list {
    overflow: auto;
    padding: 16px;
    min-height: 0;
    background: var(--text-n9);
    flex: 1;
  }
  .task-list__loading {
    display: flex;
    justify-content: center;
    align-items: center;
    width: 100%;
    min-height: 100%;
  }
  .task-card-list {
    display: flex;
    flex-direction: column;
    gap: 12px;
  }
  .task-card {
    display: flex;
    overflow: hidden;
    min-height: 112px;
    border: 1px solid var(--text-n8);
    border-left: 3px solid var(--text-n6);
    border-radius: 8px;
    background: #ffffff;
    transition: border-color 0.2s ease, box-shadow 0.2s ease, transform 0.2s ease;
    flex-direction: column;
  }
  .task-card:hover {
    border-top-color: var(--primary-1);
    border-right-color: var(--primary-1);
    border-bottom-color: var(--primary-1);
    box-shadow: 0 4px 14px rgb(28 65 68 / 8%);
    transform: translateY(-1px);
  }
  .task-card--pending {
    border-left-color: var(--warning-1);
  }
  .task-card--in_progress {
    border-left-color: var(--info-1);
  }
  .task-card--overdue {
    border-left-color: var(--error-1);
  }
  .task-card--completed {
    border-left-color: var(--success-1);
  }
  .task-card--expanded {
    border-top-color: var(--primary-1);
    border-right-color: var(--primary-1);
    border-bottom-color: var(--primary-1);
    box-shadow: 0 4px 14px rgb(28 65 68 / 8%);
  }
  .task-card__summary {
    display: flex;
    justify-content: space-between;
    align-items: stretch;
    padding: 18px 20px;
    min-height: 108px;
    cursor: pointer;
    gap: 24px;
  }
  .task-card__summary:focus-visible {
    outline: 2px solid var(--primary-1);
    outline-offset: -2px;
  }
  .task-card__main {
    display: flex;
    justify-content: space-between;
    min-width: 0;
    flex: 1;
    flex-direction: column;
    gap: 16px;
  }
  .task-card__heading {
    display: flex;
    align-items: center;
    min-width: 0;
    gap: 12px;
  }
  .task-card__title {
    overflow: hidden;
    margin: 0;
    min-width: 0;
    font-size: 16px;
    font-weight: 600;
    text-overflow: ellipsis;
    white-space: nowrap;
    color: var(--text-n1);
    line-height: 24px;
  }
  .task-card__meta {
    display: flex;
    font-size: 13px;
    color: var(--text-n3);
    flex-wrap: wrap;
    gap: 12px 28px;
    line-height: 20px;
  }
  .task-card__meta-label,
  .task-card__label {
    margin-right: 8px;
    color: var(--text-n4);
  }
  .task-card__schedule {
    display: flex;
    align-items: center;
    padding-left: 24px;
    min-width: 290px;
    border-left: 1px solid var(--text-n8);
    flex-shrink: 0;
    gap: 32px;
  }
  .task-card__schedule-item {
    display: flex;
    min-width: 116px;
    font-size: 13px;
    color: var(--text-n2);
    flex-direction: column;
    gap: 6px;
    line-height: 20px;
  }
  .task-card__schedule-item .task-card__label {
    margin-right: 0;
    font-size: 12px;
  }
  .task-card__management {
    display: flex;
    align-items: center;
    padding-left: 12px;
    border-left: 1px solid var(--text-n8);
    flex-shrink: 0;
    gap: 2px;
  }
  .task-card__management .n-button:hover {
    color: var(--primary-8);
  }
  .task-card__management .task-card__delete-button:hover {
    color: var(--error-1);
  }
  .task-detail {
    border-top: 1px solid var(--text-n8);
    background: var(--text-n10);
  }
  .task-assignment-info {
    display: flex;
    align-items: flex-start;
    padding: 14px 20px;
    font-size: 13px;
    border-bottom: 1px solid var(--text-n8);
    flex-wrap: wrap;
    gap: 10px 28px;
    line-height: 20px;
  }
  .task-assignment-info__item {
    display: flex;
    min-width: 180px;
    color: var(--text-n2);
    gap: 4px;
  }
  .task-assignment-info__item--description {
    flex: 1;
  }
  .task-assignment-info__item .task-card__label {
    flex-shrink: 0;
  }
  .task-assignment-info__files {
    display: flex;
    flex-wrap: wrap;
    gap: 6px;
  }
  .task-detail__content {
    display: grid;
    grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
    padding: 20px;
    gap: 0;
  }
  .task-detail__section {
    display: flex;
    min-width: 0;
    flex-direction: column;
  }
  .task-detail__section--report {
    padding-right: 24px;
  }
  .task-detail__section--report :deep(.n-upload) {
    display: flex;
    min-height: 0;
    flex: 1;
    flex-direction: column;
  }
  .task-detail__section--report :deep(.n-upload-dragger) {
    display: flex;
    justify-content: center;
    min-height: 0;
    flex: 1;
    flex-direction: column;
  }
  .task-detail__section--reply {
    padding-left: 24px;
    border-left: 1px solid var(--text-n8);
  }
  .task-detail__section--reply :deep(.n-input) {
    display: flex;
    margin-bottom: 9px;
    min-height: 0;
    flex: 1;
  }
  .task-detail__section--reply :deep(.n-input__textarea) {
    height: 100%;
  }
  .task-detail__section--reply :deep(.n-input__textarea-el) {
    height: 100% !important;
  }
  .task-detail__heading {
    display: flex;
    justify-content: space-between;
    align-items: flex-start;
    margin-bottom: 12px;
    gap: 16px;
  }
  .task-detail__heading h4 {
    margin: 0;
    font-size: 14px;
    font-weight: 600;
    color: var(--text-n1);
    line-height: 22px;
  }
  .task-detail__heading p {
    margin: 2px 0 0;
    font-size: 12px;
    color: var(--text-n4);
    line-height: 20px;
  }
  .task-detail__attachment-heading {
    display: flex;
    justify-content: space-between;
    align-items: center;
    margin: 16px 0 8px;
    font-size: 13px;
    color: var(--text-n2);
  }
  .task-detail__attachment-heading span:last-child {
    font-size: 12px;
    color: var(--text-n4);
  }
  .task-upload {
    display: flex;
    justify-content: center;
    align-items: center;
    padding: 12px 16px;
    min-height: 64px;
    color: var(--primary-8);
    gap: 12px;
  }
  .task-upload > div {
    display: flex;
    text-align: left;
    flex-direction: column;
    gap: 2px;
  }
  .task-upload strong {
    font-size: 13px;
    font-weight: 500;
    color: var(--text-n2);
    line-height: 20px;
  }
  .task-upload span {
    font-size: 12px;
    color: var(--text-n4);
    line-height: 18px;
  }
  .task-detail__actions {
    display: flex;
    justify-content: flex-end;
    align-items: center;
    padding: 14px 20px;
    border-top: 1px solid var(--text-n8);
    flex-wrap: wrap;
    gap: 10px;
  }
  .task-modal {
    width: min(680px, calc(100vw - 32px));
  }
  .task-form__row {
    display: grid;
    grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
    gap: 16px;
  }
  .task-form__upload {
    display: flex;
    justify-content: center;
    align-items: center;
    min-height: 56px;
    color: var(--primary-8);
    gap: 10px;
  }
  .task-modal__actions {
    display: flex;
    justify-content: flex-end;
    gap: 10px;
  }
  .task-empty {
    justify-content: center;
    align-items: center;
    min-height: 260px;
  }

  @media (max-width: 900px) {
    .task-card__summary {
      flex-direction: column;
      gap: 16px;
    }
    .task-card__schedule {
      padding-top: 16px;
      padding-left: 0;
      min-width: 0;
      border-top: 1px solid var(--text-n8);
      border-left: 0;
      gap: 24px;
    }
    .task-card__management {
      justify-content: flex-end;
      padding-top: 12px;
      padding-left: 0;
      border-top: 1px solid var(--text-n8);
      border-left: 0;
    }
    .task-detail__content {
      grid-template-columns: minmax(0, 1fr);
    }
    .task-detail__section--report {
      padding-right: 0;
      padding-bottom: 20px;
    }
    .task-detail__section--reply {
      padding-top: 20px;
      padding-left: 0;
      border-top: 1px solid var(--text-n8);
      border-left: 0;
    }
  }

  @media (max-width: 620px) {
    .task-toolbar {
      align-items: stretch;
      flex-direction: column;
    }
    .task-toolbar__summary {
      justify-content: space-between;
    }
    .task-toolbar > div {
      flex-wrap: wrap;
    }
    .task-search,
    .task-status-filter {
      width: calc(100% - 44px) !important;
    }
    .task-search {
      width: 100% !important;
    }
    .task-card__schedule {
      align-items: flex-start;
      flex-wrap: wrap;
    }
    .task-detail__actions .n-button {
      min-width: calc(50% - 5px);
      flex: 1;
    }
    .task-form__row {
      grid-template-columns: minmax(0, 1fr);
      gap: 0;
    }
  }
</style>
