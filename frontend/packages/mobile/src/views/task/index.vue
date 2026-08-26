<template>
  <CrmPageWrapper :title="t('task.title')" :back-route-name="WorkbenchRouteEnum.WORKBENCH_INDEX">
    <div class="flex h-full min-h-0 flex-col overflow-hidden bg-[var(--text-n9)]">
      <div class="task-toolbar">
        <van-search
          v-model="keyword"
          shape="round"
          :placeholder="t('task.searchPlaceholder')"
          class="crm-search"
          @search="search"
          @clear="clearSearch"
        />
        <div class="task-filter-bar">
          <button
            type="button"
            class="task-filter-button"
            :class="{ active: Boolean(status) }"
            @click="showStatusSheet = true"
          >
            <van-icon name="filter-o" />
            <span>{{ currentStatusLabel }}</span>
            <van-icon name="arrow-down" />
          </button>
          <button
            v-if="isTaskManager"
            type="button"
            class="task-filter-button task-filter-button--assignee"
            :class="{ active: Boolean(assigneeFilter) }"
            :disabled="assigneeOptionsLoading"
            @click="openAssigneeFilter"
          >
            <van-icon name="contact-o" />
            <span>{{ currentAssigneeLabel }}</span>
            <van-icon name="arrow-down" />
          </button>
          <button v-if="isTaskManager" type="button" class="task-create-button" @click="openCreateTask">
            <van-icon name="plus" />
            <span>{{ t('task.manage.create') }}</span>
          </button>
        </div>
      </div>

      <CrmList
        ref="crmListRef"
        :keyword="keyword"
        :load-list-api="getTaskPage"
        :list-params="listParams"
        :item-gap="12"
        class="min-h-0 flex-1 p-[12px]"
      >
        <template #item="{ item }">
          <article class="task-card" :class="`task-card--${item.status.toLowerCase()}`" @click="goDetail(item)">
            <div class="task-card__heading">
              <h2 class="task-card__title">{{ item.name }}</h2>
              <van-tag :type="getStatusTagType(item.status)" plain>
                {{ getStatusLabel(item.status) }}
              </van-tag>
            </div>

            <div class="task-card__meta">
              <div class="task-card__meta-item">
                <span>{{ t('task.assignee') }}</span>
                <strong>{{ item.assigneeName || t('task.assigneePending') }}</strong>
              </div>
              <div v-if="item.customerName" class="task-card__meta-item">
                <span>{{ t('task.customer') }}</span>
                <strong>{{ item.customerName }}</strong>
              </div>
            </div>

            <div class="task-card__schedule">
              <span>{{ t('task.deadline') }}</span>
              <time>{{ formatDateTime(item.deadline) }}</time>
            </div>

            <div v-if="isTaskManager" class="task-card__management" @click.stop>
              <button
                v-if="item.status !== 'COMPLETED'"
                type="button"
                :aria-label="t('task.manage.reassign')"
                :title="t('task.manage.reassign')"
                @click="openReassignTask(item)"
              >
                <van-icon name="friends-o" />
                <span>{{ t('task.manage.reassign') }}</span>
              </button>
              <button
                type="button"
                :aria-label="t('task.manage.edit')"
                :title="t('task.manage.edit')"
                @click="openEditTask(item)"
              >
                <van-icon name="edit" />
                <span>{{ t('task.manage.edit') }}</span>
              </button>
              <button
                type="button"
                class="task-card__delete-button"
                :disabled="deletingTaskIds.includes(item.id)"
                :aria-label="t('task.manage.delete')"
                :title="t('task.manage.delete')"
                @click="confirmDeleteTask(item)"
              >
                <van-icon name="delete-o" />
                <span>{{ t('task.manage.delete') }}</span>
              </button>
            </div>
          </article>
        </template>
      </CrmList>
    </div>

    <van-action-sheet
      v-model:show="showStatusSheet"
      :title="t('task.filter.status')"
      :actions="statusActions"
      :cancel-text="t('task.manage.cancel')"
      close-on-click-action
      @select="selectStatus"
    />
    <van-action-sheet
      v-model:show="showAssigneeSheet"
      class="task-option-sheet"
      :title="t('task.filter.assignee')"
      :actions="assigneeActions"
      :cancel-text="t('task.manage.cancel')"
      close-on-click-action
      @select="selectAssigneeFilter"
    />

    <van-popup
      v-model:show="showTaskForm"
      position="bottom"
      round
      class="task-create-popup"
      :class="{ 'task-create-popup--compact': taskFormMode === 'REASSIGN' }"
    >
      <div class="task-create-popup__header">
        <button type="button" @click="showTaskForm = false">{{ t('task.manage.cancel') }}</button>
        <h2>{{ taskFormTitle }}</h2>
        <button type="button" class="primary" :disabled="taskFormSaving" @click="saveTaskForm">
          {{ taskFormConfirmText }}
        </button>
      </div>

      <div class="task-create-form">
        <van-cell-group inset>
          <template v-if="taskFormMode !== 'REASSIGN'">
            <van-field
              v-model="taskForm.name"
              required
              clearable
              :label="t('task.manage.name')"
              :placeholder="t('task.manage.namePlaceholder')"
              maxlength="255"
            />
          </template>
          <van-field
            :model-value="taskForm.assigneeName"
            required
            readonly
            :is-link="!taskForm.completed"
            :disabled="taskForm.completed"
            :label="t('task.assignee')"
            :placeholder="t('task.manage.assigneePlaceholder')"
            @click="openFormAssigneeSelector"
          />
          <template v-if="taskFormMode !== 'REASSIGN'">
            <van-field
              :model-value="taskForm.customerName"
              readonly
              is-link
              :label="t('task.customer')"
              :placeholder="t('task.manage.customerPlaceholder')"
              @click="openCustomerSelector"
            />
            <div class="task-create-form__datetime">
              <label for="task-deadline">{{ t('task.manage.deadline') }}<span>*</span></label>
              <input id="task-deadline" v-model="taskForm.deadline" type="datetime-local" />
            </div>
            <van-field
              v-model="taskForm.description"
              type="textarea"
              rows="3"
              autosize
              maxlength="2000"
              show-word-limit
              :label="t('task.manage.description')"
              :placeholder="t('task.manage.descriptionPlaceholder')"
            />
          </template>
        </van-cell-group>

        <section v-if="taskFormMode !== 'REASSIGN'" class="task-create-attachments">
          <div class="task-create-attachments__heading">
            <h3>{{ t('task.manage.attachments') }}</h3>
            <span>{{ taskForm.attachments.length }}/10</span>
          </div>
          <div v-if="taskForm.attachments.length" class="task-create-attachment-list">
            <div
              v-for="(file, index) in taskForm.attachments"
              :key="fileKey(file, index)"
              class="task-create-attachment"
            >
              <van-icon name="description-o" />
              <span>{{ file.name }}</span>
              <small>{{ formatFileSize(file.size) }}</small>
              <button type="button" :aria-label="t('task.attachment.remove')" @click="removeTaskAttachment(index)">
                <van-icon name="delete-o" />
              </button>
            </div>
          </div>
          <label class="task-create-upload">
            <van-icon name="plus" />
            <span>{{ t('task.manage.attachmentUpload') }}</span>
            <input type="file" multiple accept="*/*" @change="selectTaskAttachments" />
          </label>
          <p>{{ t('task.attachment.uploadTip') }}</p>
        </section>
      </div>
    </van-popup>

    <van-action-sheet
      v-model:show="showFormAssigneeSheet"
      class="task-option-sheet"
      :title="t('task.assignee')"
      :actions="formAssigneeActions"
      :cancel-text="t('task.manage.cancel')"
      close-on-click-action
      @select="selectFormAssignee"
    />
    <van-action-sheet
      v-model:show="showCustomerSheet"
      class="task-option-sheet"
      :title="t('task.customer')"
      :actions="customerActions"
      :cancel-text="t('task.manage.cancel')"
      close-on-click-action
      @select="selectCustomer"
    />
  </CrmPageWrapper>
</template>

<script setup lang="ts">
  import { useRouter } from 'vue-router';
  import { closeToast, showConfirmDialog, showFailToast, showLoadingToast, showSuccessToast } from 'vant';
  import dayjs from 'dayjs';

  import { useI18n } from '@lib/shared/hooks/useI18n';
  import type { TaskAttachmentItem, TaskItem, TaskOptionItem, TaskStatus } from '@lib/shared/models/task';

  import CrmList from '@/components/pure/crm-list/index.vue';
  import CrmPageWrapper from '@/components/pure/crm-page-wrapper/index.vue';

  import {
    addTask,
    deleteTask,
    deleteTaskAttachment,
    getTaskAssigneeOptions,
    getTaskCustomerOptions,
    getTaskPage,
    reassignTask,
    updateTask,
    uploadTaskAttachments,
  } from '@/api/modules';
  import { TaskRouteEnum, WorkbenchRouteEnum } from '@/enums/routeEnum';
  import useUserStore from '@/store/modules/user';
  import { hasAnyPermission } from '@/utils/permission';

  interface SheetAction {
    name: string;
    value: string;
    color?: string;
  }

  type TaskFormMode = 'CREATE' | 'EDIT' | 'REASSIGN';

  interface TaskFormAttachment {
    id?: string;
    name: string;
    size: number;
    file?: File;
  }

  interface TaskForm {
    id: string;
    name: string;
    assigneeId: string;
    assigneeName: string;
    customerId: string;
    customerName: string;
    deadline: string;
    description: string;
    attachments: TaskFormAttachment[];
    persistedAttachmentIds: string[];
    completed: boolean;
  }

  const MAX_FILE_COUNT = 10;
  const MAX_FILE_SIZE = 50 * 1024 * 1024;

  defineOptions({
    name: TaskRouteEnum.TASK_INDEX,
  });

  const { t } = useI18n();
  const router = useRouter();
  const userStore = useUserStore();

  const crmListRef = ref<InstanceType<typeof CrmList>>();
  const keyword = ref('');
  const status = ref<TaskStatus | ''>('');
  const assigneeFilter = ref('');
  const assigneeOptions = ref<TaskOptionItem[]>([]);
  const customerOptions = ref<TaskOptionItem[]>([]);
  const showStatusSheet = ref(false);
  const showAssigneeSheet = ref(false);
  const showTaskForm = ref(false);
  const showFormAssigneeSheet = ref(false);
  const showCustomerSheet = ref(false);
  const taskFormMode = ref<TaskFormMode>('CREATE');
  const taskFormSaving = ref(false);
  const deletingTaskIds = ref<string[]>([]);
  const assigneeOptionsLoading = ref(false);
  const customerOptionsLoading = ref(false);

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

  const taskFormTitle = computed(() => {
    if (taskFormMode.value === 'REASSIGN') return t('task.manage.reassignTitle');
    return t(taskFormMode.value === 'EDIT' ? 'task.manage.editTitle' : 'task.manage.createTitle');
  });

  const taskFormConfirmText = computed(() => {
    if (taskFormMode.value === 'REASSIGN') return t('task.manage.reassignConfirm');
    return t(taskFormMode.value === 'EDIT' ? 'task.manage.save' : 'task.manage.confirm');
  });

  function defaultDeadline() {
    return dayjs().add(1, 'day').format('YYYY-MM-DDTHH:mm');
  }

  function createTaskForm(): TaskForm {
    return {
      id: '',
      name: '',
      assigneeId: '',
      assigneeName: '',
      customerId: '',
      customerName: '',
      deadline: defaultDeadline(),
      description: '',
      attachments: [],
      persistedAttachmentIds: [],
      completed: false,
    };
  }

  const taskForm = ref<TaskForm>(createTaskForm());

  const statusOptions = computed<Array<{ label: string; value: TaskStatus | '' }>>(() => [
    { label: t('task.allStatus'), value: '' },
    { label: t('task.status.pending'), value: 'PENDING' },
    { label: t('task.status.inProgress'), value: 'IN_PROGRESS' },
    { label: t('task.status.overdue'), value: 'OVERDUE' },
    { label: t('task.status.completed'), value: 'COMPLETED' },
  ]);

  const statusActions = computed<SheetAction[]>(() =>
    statusOptions.value.map((item) => ({
      name: item.label,
      value: item.value,
      color: status.value === item.value ? 'var(--primary-8)' : undefined,
    }))
  );

  const assigneeActions = computed<SheetAction[]>(() => [
    {
      name: t('task.filter.allAssignees'),
      value: '',
      color: assigneeFilter.value === '' ? 'var(--primary-8)' : undefined,
    },
    {
      name: t('task.filter.unassigned'),
      value: '__UNASSIGNED__',
      color: assigneeFilter.value === '__UNASSIGNED__' ? 'var(--primary-8)' : undefined,
    },
    ...assigneeOptions.value.map((item) => ({
      name: item.name,
      value: item.id,
      color: assigneeFilter.value === item.id ? 'var(--primary-8)' : undefined,
    })),
  ]);

  const formAssigneeActions = computed<SheetAction[]>(() =>
    assigneeOptions.value.map((item) => ({ name: item.name, value: item.id }))
  );

  const customerActions = computed<SheetAction[]>(() => [
    { name: t('task.manage.noCustomer'), value: '' },
    ...customerOptions.value.map((item) => ({ name: item.name, value: item.id })),
  ]);

  const currentStatusLabel = computed(() => {
    if (!status.value) return t('task.filter.status');
    return statusOptions.value.find((item) => item.value === status.value)?.label || t('task.filter.status');
  });

  const currentAssigneeLabel = computed(() => {
    if (!assigneeFilter.value) return t('task.filter.assignee');
    if (assigneeFilter.value === '__UNASSIGNED__') return t('task.filter.unassigned');
    return assigneeOptions.value.find((item) => item.id === assigneeFilter.value)?.name || t('task.filter.assignee');
  });

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

  function getStatusLabel(value: TaskStatus) {
    return t(statusLabelKey[value]);
  }

  function getStatusTagType(value: TaskStatus) {
    return statusTagType[value];
  }

  const listParams = computed(() => ({
    status: status.value || undefined,
    assigneeId: isTaskManager.value ? assigneeFilter.value || undefined : undefined,
  }));

  function formatDateTime(value?: number) {
    return value ? dayjs(value).format('YYYY-MM-DD HH:mm') : '-';
  }

  function formatFileSize(size: number) {
    if (size < 1024) return `${size} B`;
    if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KB`;
    return `${(size / 1024 / 1024).toFixed(1)} MB`;
  }

  function fileKey(attachment: TaskFormAttachment, index: number) {
    return attachment.id || `${attachment.name}-${attachment.size}-${attachment.file?.lastModified || 0}-${index}`;
  }

  function mapTaskAttachment(attachment: TaskAttachmentItem): TaskFormAttachment {
    return {
      id: attachment.id,
      name: attachment.originalName,
      size: attachment.sizeBytes,
    };
  }

  async function reload(showLoading = true) {
    if (showLoading) showLoadingToast(t('common.searching'));
    try {
      await crmListRef.value?.loadList(true);
    } finally {
      if (showLoading) closeToast();
    }
  }

  function search() {
    reload();
  }

  function clearSearch() {
    nextTick(() => reload());
  }

  function selectStatus(action: SheetAction) {
    status.value = action.value as TaskStatus | '';
    nextTick(() => reload());
  }

  function selectAssigneeFilter(action: SheetAction) {
    assigneeFilter.value = action.value;
    nextTick(() => reload());
  }

  function selectFormAssignee(action: SheetAction) {
    taskForm.value.assigneeId = action.value;
    taskForm.value.assigneeName = action.name;
  }

  function selectCustomer(action: SheetAction) {
    taskForm.value.customerId = action.value;
    taskForm.value.customerName = action.value ? action.name : '';
  }

  async function loadAssigneeOptions() {
    if (!isTaskManager.value || assigneeOptionsLoading.value) return false;
    assigneeOptionsLoading.value = true;
    showLoadingToast({ message: t('common.loading'), forbidClick: true });
    try {
      assigneeOptions.value = await getTaskAssigneeOptions();
    } catch {
      closeToast();
      showFailToast(t('task.filter.assigneeLoadFailed'));
      return false;
    } finally {
      assigneeOptionsLoading.value = false;
    }
    closeToast();
    return true;
  }

  async function loadCustomerOptions() {
    if (customerOptionsLoading.value) return false;
    customerOptionsLoading.value = true;
    showLoadingToast({ message: t('common.loading'), forbidClick: true });
    try {
      customerOptions.value = await getTaskCustomerOptions();
    } catch {
      closeToast();
      showFailToast(t('task.filter.customerLoadFailed'));
      return false;
    } finally {
      customerOptionsLoading.value = false;
    }
    closeToast();
    return true;
  }

  async function openAssigneeFilter() {
    const loaded = await loadAssigneeOptions();
    if (loaded || assigneeOptions.value.length) {
      showAssigneeSheet.value = true;
    }
  }

  async function openFormAssigneeSelector() {
    if (taskForm.value.completed) return;
    if (!assigneeOptions.value.length) {
      const loaded = await loadAssigneeOptions();
      if (!loaded) return;
    }
    showFormAssigneeSheet.value = true;
  }

  async function openCustomerSelector() {
    if (!customerOptions.value.length) {
      const loaded = await loadCustomerOptions();
      if (!loaded) return;
    }
    showCustomerSheet.value = true;
  }

  async function openCreateTask() {
    taskFormMode.value = 'CREATE';
    taskForm.value = createTaskForm();
    if (!assigneeOptions.value.length) {
      const loaded = await loadAssigneeOptions();
      if (!loaded) return;
    }
    showTaskForm.value = true;
  }

  function openEditTask(item: TaskItem) {
    taskFormMode.value = 'EDIT';
    taskForm.value = {
      id: item.id,
      name: item.name,
      assigneeId: item.assigneeId || '',
      assigneeName: item.assigneeName || '',
      customerId: item.customerId || '',
      customerName: item.customerName || '',
      deadline: dayjs(item.deadline).format('YYYY-MM-DDTHH:mm'),
      description: item.description || '',
      attachments: item.taskAttachments.map(mapTaskAttachment),
      persistedAttachmentIds: item.taskAttachments.map((attachment) => attachment.id),
      completed: item.status === 'COMPLETED',
    };
    showTaskForm.value = true;
  }

  async function openReassignTask(item: TaskItem) {
    taskFormMode.value = 'REASSIGN';
    taskForm.value = {
      ...createTaskForm(),
      id: item.id,
      assigneeId: item.assigneeId || '',
      assigneeName: item.assigneeName || '',
    };
    if (!assigneeOptions.value.length) {
      const loaded = await loadAssigneeOptions();
      if (!loaded) return;
    }
    showTaskForm.value = true;
  }

  function selectTaskAttachments(event: Event) {
    const input = event.target as HTMLInputElement;
    const files = Array.from(input.files || []);
    input.value = '';
    if (!files.length) return;
    if (taskForm.value.attachments.length + files.length > MAX_FILE_COUNT) {
      showFailToast(t('task.attachment.tooMany'));
      return;
    }
    if (files.some((file) => file.size > MAX_FILE_SIZE)) {
      showFailToast(t('task.attachment.tooLarge'));
      return;
    }
    taskForm.value.attachments.push(
      ...files.map((file) => ({
        name: file.name,
        size: file.size,
        file,
      }))
    );
  }

  function removeTaskAttachment(index: number) {
    taskForm.value.attachments.splice(index, 1);
  }

  async function syncTaskAttachments(taskId: string, form: TaskForm) {
    const retainedIds = form.attachments.flatMap((attachment) => (attachment.id ? [attachment.id] : []));
    const removedIds = form.persistedAttachmentIds.filter((id) => !retainedIds.includes(id));
    await Promise.all(removedIds.map((id) => deleteTaskAttachment(id)));

    const files = form.attachments.flatMap((attachment) => (attachment.file ? [attachment.file] : []));
    if (files.length) {
      await uploadTaskAttachments(taskId, 'TASK', files);
    }
  }

  async function saveTaskForm() {
    const form = taskForm.value;
    if (!form.assigneeId) {
      showFailToast(t('task.manage.assigneeRequired'));
      return;
    }

    if (taskFormMode.value === 'REASSIGN') {
      taskFormSaving.value = true;
      try {
        await reassignTask({ id: form.id, assigneeId: form.assigneeId });
        showTaskForm.value = false;
        await reload(false);
        showSuccessToast(t('task.manage.reassigned'));
      } finally {
        taskFormSaving.value = false;
      }
      return;
    }

    if (!form.name.trim()) {
      showFailToast(t('task.manage.nameRequired'));
      return;
    }
    const deadline = dayjs(form.deadline).valueOf();
    if (!form.deadline || !Number.isFinite(deadline)) {
      showFailToast(t('task.manage.deadlineRequired'));
      return;
    }

    taskFormSaving.value = true;
    try {
      const wasCreate = taskFormMode.value === 'CREATE';
      const payload = {
        name: form.name.trim(),
        assigneeId: form.assigneeId,
        customerId: form.customerId || undefined,
        description: form.description.trim() || undefined,
        deadline,
      };
      const savedTask = wasCreate ? await addTask(payload) : await updateTask({ id: form.id, ...payload });
      try {
        await syncTaskAttachments(savedTask.id, form);
      } catch {
        showTaskForm.value = false;
        await reload(false);
        showFailToast(t(wasCreate ? 'task.manage.createdAttachmentFailed' : 'task.manage.updatedAttachmentFailed'));
        return;
      }
      showTaskForm.value = false;
      await reload(false);
      showSuccessToast(t(wasCreate ? 'task.manage.created' : 'task.manage.updated'));
    } finally {
      taskFormSaving.value = false;
    }
  }

  async function confirmDeleteTask(item: TaskItem) {
    try {
      await showConfirmDialog({
        title: t('task.manage.delete'),
        message: t('task.manage.deleteConfirm'),
      });
    } catch {
      return;
    }

    deletingTaskIds.value.push(item.id);
    try {
      await deleteTask(item.id);
      await reload(false);
      showSuccessToast(t('task.manage.deleted'));
    } finally {
      deletingTaskIds.value = deletingTaskIds.value.filter((id) => id !== item.id);
    }
  }

  function goDetail(item: TaskItem) {
    router.push({
      name: TaskRouteEnum.TASK_DETAIL,
      query: { id: item.id },
    });
  }

  onActivated(async () => {
    await reload(false);
  });
</script>

<style lang="less" scoped>
  :deep(.crm-page-content) {
    overflow: hidden !important;
  }
  .task-toolbar {
    border-bottom: 1px solid var(--text-n8);
    background: var(--text-n10);
    flex-shrink: 0;
  }
  .task-toolbar :deep(.van-search) {
    padding: 8px 12px;
  }
  .task-filter-bar {
    display: flex;
    overflow-x: auto;
    padding: 0 12px 10px;
    gap: 8px;
    scrollbar-width: none;
  }
  .task-filter-bar::-webkit-scrollbar {
    display: none;
  }
  .task-filter-button,
  .task-create-button {
    display: flex;
    justify-content: center;
    align-items: center;
    padding: 0 11px;
    min-width: 92px;
    height: 36px;
    font-size: 13px;
    border: 1px solid var(--text-n7);
    border-radius: 6px;
    white-space: nowrap;
    color: var(--text-n2);
    background: var(--text-n10);
    flex: 0 0 auto;
    gap: 5px;
  }
  .task-filter-button--assignee {
    max-width: 156px;
  }
  .task-filter-button--assignee span {
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .task-filter-button.active {
    border-color: var(--primary-8);
    color: var(--primary-8);
    background: var(--primary-7);
  }
  .task-filter-button:disabled {
    opacity: 0.6;
  }
  :global(.task-option-sheet) {
    max-height: 80vh;
  }
  :global(.task-option-sheet .van-action-sheet__content) {
    overflow-y: auto;
    min-height: 0;
    overscroll-behavior: contain;
    touch-action: pan-y;
    -webkit-overflow-scrolling: touch;
  }
  .task-create-button {
    min-width: 104px;
    border-color: var(--primary-8);
    color: #ffffff;
    background: var(--primary-8);
  }
  .task-card {
    position: relative;
    padding: 14px 14px 13px 16px;
    border: 1px solid var(--text-n8);
    border-left: 3px solid var(--text-n6);
    border-radius: 8px;
    background: var(--text-n10);
  }
  .task-card:active {
    background: var(--text-n9);
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
  .task-card__heading {
    display: flex;
    align-items: flex-start;
    gap: 10px;
  }
  .task-card__title {
    margin: 0;
    min-width: 0;
    font-size: 16px;
    font-weight: 600;
    color: var(--text-n1);
    overflow-wrap: anywhere;
    line-height: 23px;
    flex: 1;
  }
  .task-card__heading :deep(.van-tag) {
    flex-shrink: 0;
    margin-top: 1px;
  }
  .task-card__meta {
    display: grid;
    margin-top: 12px;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 8px 12px;
  }
  .task-card__meta-item {
    display: flex;
    min-width: 0;
    flex-direction: column;
    gap: 3px;
  }
  .task-card__meta-item span,
  .task-card__schedule span {
    font-size: 12px;
    line-height: 18px;
    color: var(--text-n4);
  }
  .task-card__meta-item strong {
    overflow: hidden;
    font-size: 13px;
    font-weight: 400;
    line-height: 20px;
    text-overflow: ellipsis;
    white-space: nowrap;
    color: var(--text-n2);
  }
  .task-card__schedule {
    display: flex;
    justify-content: space-between;
    align-items: center;
    margin-top: 12px;
    padding-top: 10px;
    border-top: 1px solid var(--text-n8);
    gap: 12px;
  }
  .task-card__schedule time {
    font-size: 13px;
    line-height: 20px;
    color: var(--text-n2);
  }
  .task-card__management {
    display: flex;
    justify-content: flex-end;
    align-items: center;
    margin-top: 8px;
    gap: 4px;
  }
  .task-card__management button {
    display: flex;
    justify-content: center;
    align-items: center;
    overflow: hidden;
    padding: 0 10px;
    min-width: 0;
    height: 36px;
    font-size: 13px;
    border: 0;
    border-radius: 6px;
    white-space: nowrap;
    color: var(--text-n2);
    background: transparent;
    gap: 5px;
  }
  .task-card__management button:active {
    background: var(--text-n9);
  }
  .task-card__management button:disabled {
    opacity: 0.5;
  }
  .task-card__management button span {
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .task-card__management .task-card__delete-button {
    color: var(--error-1);
  }
  .task-create-popup {
    overflow: hidden;
    height: min(88vh, 760px);
    background: var(--text-n9);
  }
  .task-create-popup--compact {
    height: auto;
    max-height: 50vh;
  }
  .task-create-popup--compact .task-create-form {
    height: auto;
  }
  .task-create-popup__header {
    display: grid;
    align-items: center;
    padding: 0 16px;
    height: 52px;
    border-bottom: 1px solid var(--text-n8);
    background: var(--text-n10);
    grid-template-columns: 72px minmax(0, 1fr) 72px;
  }
  .task-create-popup__header h2 {
    overflow: hidden;
    margin: 0;
    font-size: 16px;
    text-align: center;
    text-overflow: ellipsis;
    white-space: nowrap;
    color: var(--text-n1);
  }
  .task-create-popup__header button {
    padding: 0;
    font-size: 14px;
    border: 0;
    text-align: left;
    color: var(--text-n3);
    background: transparent;
  }
  .task-create-popup__header button.primary {
    text-align: right;
    color: var(--primary-8);
  }
  .task-create-popup__header button:disabled {
    opacity: 0.5;
  }
  .task-create-form {
    overflow-y: auto;
    padding: 12px 0 28px;
    height: calc(100% - 52px);
  }
  .task-create-form :deep(.van-cell-group--inset) {
    margin: 0 12px;
    border: 1px solid var(--text-n8);
    border-radius: 8px;
  }
  .task-create-form :deep(.van-field__label) {
    width: 92px;
  }
  .task-create-form__datetime {
    display: flex;
    align-items: center;
    padding: 10px 16px;
    min-height: 48px;
    border-bottom: 1px solid var(--text-n8);
    gap: 12px;
  }
  .task-create-form__datetime label {
    width: 92px;
    font-size: 14px;
    color: var(--text-n2);
    flex-shrink: 0;
  }
  .task-create-form__datetime label span {
    margin-left: 2px;
    color: var(--error-1);
  }
  .task-create-form__datetime input {
    padding: 0;
    width: 100%;
    min-width: 0;
    height: 28px;
    border: 0;
    color: var(--text-n2);
    background: transparent;
    outline: 0;
    font: inherit;
  }
  .task-create-attachments {
    margin: 12px;
    padding: 14px 16px;
    border: 1px solid var(--text-n8);
    border-radius: 8px;
    background: var(--text-n10);
  }
  .task-create-attachments__heading {
    display: flex;
    justify-content: space-between;
    align-items: center;
  }
  .task-create-attachments__heading h3 {
    margin: 0;
    font-size: 14px;
    font-weight: 500;
    color: var(--text-n1);
  }
  .task-create-attachments__heading span,
  .task-create-attachments > p {
    font-size: 12px;
    color: var(--text-n4);
  }
  .task-create-attachment-list {
    display: flex;
    margin-top: 10px;
    flex-direction: column;
    gap: 8px;
  }
  .task-create-attachment {
    display: flex;
    align-items: center;
    padding: 8px 10px;
    min-width: 0;
    border: 1px solid var(--text-n8);
    border-radius: 6px;
    gap: 8px;
  }
  .task-create-attachment > span {
    overflow: hidden;
    min-width: 0;
    font-size: 13px;
    text-overflow: ellipsis;
    white-space: nowrap;
    color: var(--text-n2);
    flex: 1;
  }
  .task-create-attachment small {
    font-size: 11px;
    white-space: nowrap;
    color: var(--text-n4);
  }
  .task-create-attachment button {
    padding: 4px;
    font-size: 17px;
    border: 0;
    color: var(--error-1);
    background: transparent;
  }
  .task-create-upload {
    position: relative;
    display: flex;
    justify-content: center;
    align-items: center;
    overflow: hidden;
    margin-top: 12px;
    height: 44px;
    font-size: 14px;
    border: 1px dashed var(--primary-8);
    border-radius: 6px;
    color: var(--primary-8);
    background: var(--primary-7);
    gap: 6px;
  }
  .task-create-upload input {
    position: absolute;
    width: 1px;
    height: 1px;
    opacity: 0;
  }
  .task-create-attachments > p {
    margin: 8px 0 0;
    line-height: 18px;
  }
</style>
