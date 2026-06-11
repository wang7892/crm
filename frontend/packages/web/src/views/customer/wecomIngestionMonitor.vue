<template>
  <div class="h-full px-[16px] pt-[16px]">
    <CrmCard no-content-padding hide-footer class="h-full">
      <div class="flex h-[calc(100vh-140px)] min-h-[480px] flex-col gap-[12px] p-[16px]">
        <div class="text-[16px] font-medium text-[var(--text-1)]">{{ t('customer.wecomMonitor.title') }}</div>
        <div class="flex min-h-0 flex-1 gap-[12px] overflow-hidden rounded border border-[var(--border-1)]">
          <!-- 左侧：会话 -->
          <div class="flex w-[280px] shrink-0 flex-col border-r border-[var(--border-1)] bg-[var(--bg-1)]">
            <div
              class="border-b border-[var(--border-1)] px-[12px] py-[10px] text-[13px] font-medium text-[var(--text-2)]"
            >
              {{ t('customer.wecomMonitor.sessionList') }}
            </div>
            <n-scrollbar class="min-h-0 flex-1">
              <div v-if="!sessions.length" class="p-[16px] text-[13px] text-[var(--text-n4)]">
                {{ t('customer.wecomMonitor.emptySessions') }}
              </div>
              <div
                v-for="s in sessions"
                :key="s.sessionKey"
                class="session-row cursor-pointer border-b border-[var(--border-2)] px-[12px] py-[10px] hover:bg-[var(--fill-1)]"
                :class="{ 'session-row--active': s.sessionKey === activeSessionKey }"
                @click="selectSession(s.sessionKey)"
              >
                <div class="flex items-start gap-[6px]">
                  <div class="line-clamp-1 min-w-0 flex-1 text-[13px] font-medium">
                    {{ displaySessionTitle(s) }}
                  </div>
                  <n-popconfirm
                    :positive-text="t('customer.wecomMonitor.deleteConfirm')"
                    :negative-text="t('customer.wecomMonitor.deleteCancel')"
                    @positive-click="() => handleDeleteSession(s)"
                  >
                    <template #trigger>
                      <n-button
                        v-permission="['CUSTOMER_MANAGEMENT:UPDATE']"
                        text
                        type="error"
                        size="tiny"
                        :loading="deletingSessionKey === s.sessionKey"
                        @click.stop
                      >
                        <template #icon>
                          <n-icon><TrashOutline /></n-icon>
                        </template>
                      </n-button>
                    </template>
                    {{ t('customer.wecomMonitor.deleteSessionConfirm') }}
                  </n-popconfirm>
                </div>
                <div class="mt-[4px] line-clamp-2 text-[12px] opacity-80">
                  {{ s.lastPreview || '—' }}
                </div>
                <div class="mt-[4px] text-[11px] opacity-70">
                  {{ s.lastSendTime ? dayjs(s.lastSendTime).format('YYYY-MM-DD HH:mm') : '' }}
                </div>
              </div>
            </n-scrollbar>
            <div class="border-t border-[var(--border-1)] p-[8px]">
              <n-pagination
                v-model:page="sessionPage"
                :page-size="sessionPageSize"
                :item-count="sessionTotal"
                size="small"
                simple
                @update:page="loadSessions"
              />
            </div>
          </div>

          <!-- 中间：消息 -->
          <div class="flex min-w-0 flex-1 flex-col border-r border-[var(--border-1)]">
            <div
              class="border-b border-[var(--border-1)] px-[12px] py-[10px] text-[13px] font-medium text-[var(--text-2)]"
            >
              {{ t('customer.wecomMonitor.messages') }}
            </div>
            <div v-if="!activeSessionKey" class="p-[24px] text-[13px] text-[var(--text-n4)]">
              {{ t('customer.wecomMonitor.selectSession') }}
            </div>
            <template v-else>
              <n-scrollbar class="min-h-0 flex-1">
                <div
                  v-for="m in messages"
                  :key="m.id"
                  class="flex gap-[8px] border-b border-[var(--border-2)] px-[12px] py-[10px]"
                >
                  <n-checkbox
                    :checked="selectedIds.includes(m.id)"
                    :disabled="m.synced"
                    @update:checked="(v: boolean) => toggleMessage(m.id, v)"
                  />
                  <div class="min-w-0 flex-1">
                    <div class="flex flex-wrap items-center gap-[8px] text-[12px] text-[var(--text-n4)]">
                      <span>{{ formatDirection(m.messageDirection) }}</span>
                      <span>{{ m.msgType }}</span>
                      <span>{{ m.sendTime ? dayjs(m.sendTime).format('YYYY-MM-DD HH:mm:ss') : '' }}</span>
                      <n-tag v-if="m.synced" size="small" type="success">{{
                        t('customer.wecomMonitor.syncedTag')
                      }}</n-tag>
                    </div>
                    <div class="mt-[4px] whitespace-pre-wrap break-words text-[13px] text-[var(--text-1)]">
                      {{ m.contentText || `(${m.msgType})` }}
                    </div>
                  </div>
                </div>
              </n-scrollbar>
              <div class="border-t border-[var(--border-1)] p-[8px]">
                <n-pagination
                  v-model:page="messagePage"
                  :page-size="messagePageSize"
                  :item-count="messageTotal"
                  size="small"
                  simple
                  @update:page="loadMessages"
                />
              </div>
            </template>
          </div>

          <!-- 右侧：匹配与同步 -->
          <div class="flex w-[320px] shrink-0 flex-col bg-[var(--bg-1)]">
            <div
              class="border-b border-[var(--border-1)] px-[12px] py-[10px] text-[13px] font-medium text-[var(--text-2)]"
            >
              {{ t('customer.wecomMonitor.matchPanel') }}
            </div>
            <n-scrollbar class="min-h-0 flex-1">
              <div class="space-y-[12px] p-[12px]">
                <div>
                  <div class="mb-[4px] text-[12px] text-[var(--text-n4)]">{{
                    t('customer.wecomMonitor.wecomExternal')
                  }}</div>
                  <div class="text-[13px]">{{ activeContext?.wecomCustomerExternalUserid || '—' }}</div>
                </div>
                <div>
                  <div class="mb-[4px] text-[12px] text-[var(--text-n4)]">{{
                    t('customer.wecomMonitor.matchedCustomer')
                  }}</div>
                  <div class="text-[13px]">{{ activeContext?.matchedCustomerName || '—' }}</div>
                </div>
                <div>
                  <div class="mb-[6px] text-[12px] text-[var(--text-n4)]">{{
                    t('customer.wecomMonitor.overrideCustomer')
                  }}</div>
                  <n-select
                    v-model:value="customerOverrideId"
                    filterable
                    remote
                    clearable
                    :loading="customerSearchLoading"
                    :options="customerSelectOptions"
                    @search="handleCustomerSearch"
                  />
                </div>
                <div>
                  <div class="mb-[4px] text-[12px] text-[var(--text-n4)]">{{
                    t('customer.wecomMonitor.wecomStaff')
                  }}</div>
                  <div class="text-[13px]">{{ activeContext?.wecomStaffUserid || '—' }}</div>
                </div>
                <div>
                  <div class="mb-[4px] text-[12px] text-[var(--text-n4)]">{{
                    t('customer.wecomMonitor.matchedStaff')
                  }}</div>
                  <div class="text-[13px]">{{ activeContext?.matchedStaffName || '—' }}</div>
                </div>
                <div>
                  <div class="mb-[6px] text-[12px] text-[var(--text-n4)]">{{
                    t('customer.wecomMonitor.overrideOwner')
                  }}</div>
                  <CrmUserSelect
                    v-model:value="ownerOverrideId"
                    value-field="id"
                    label-field="name"
                    mode="remote"
                    :fetch-api="fetchUserOptions"
                  />
                </div>
                <div>
                  <div class="mb-[4px] text-[12px] text-[var(--text-n4)]">{{
                    t('customer.wecomMonitor.matchRule')
                  }}</div>
                  <div class="whitespace-pre-wrap text-[12px] leading-relaxed text-[var(--text-2)]">
                    {{ activeContext?.matchRuleSummary || '—' }}
                  </div>
                </div>
                <div>
                  <div class="mb-[4px] text-[12px] text-[var(--text-n4)]">{{
                    t('customer.wecomMonitor.followType')
                  }}</div>
                  <div class="text-[13px]">{{ t('customer.wecomMonitor.followTypeCustomer') }}</div>
                </div>
                <div>
                  <div class="mb-[4px] text-[12px] text-[var(--text-n4)]">{{
                    t('customer.wecomMonitor.selectedSummary')
                  }}</div>
                  <n-input :value="selectedSummary" type="textarea" readonly :rows="6" />
                </div>
              </div>
            </n-scrollbar>
            <div class="border-t border-[var(--border-1)] p-[12px]">
              <n-button
                v-permission="['CUSTOMER_MANAGEMENT:UPDATE']"
                type="primary"
                block
                :loading="syncing"
                :disabled="!canSync"
                @click="handleSync"
              >
                {{ t('customer.wecomMonitor.sync') }}
              </n-button>
            </div>
          </div>
        </div>
      </div>
    </CrmCard>
  </div>
</template>

<script setup lang="ts">
  import { computed, onMounted, ref } from 'vue';
  import {
    NButton,
    NCheckbox,
    NIcon,
    NInput,
    NPagination,
    NPopconfirm,
    NScrollbar,
    NSelect,
    NTag,
    useMessage,
  } from 'naive-ui';
  import { TrashOutline } from '@vicons/ionicons5';
  import { debounce } from 'lodash-es';
  import dayjs from 'dayjs';

  import type { WecomMessageItem, WecomSessionItem } from '@lib/shared/api/modules/wecomIngestion';
  import { useI18n } from '@lib/shared/hooks/useI18n';

  import CrmCard from '@/components/pure/crm-card/index.vue';
  import CrmUserSelect from '@/components/business/crm-user-select/index.vue';

  import {
    deleteWecomSession,
    getCustomerOptions,
    getUserOptions,
    getWecomMessagePage,
    getWecomSessionPage,
    syncWecomToFollow,
  } from '@/api/modules';

  const { t } = useI18n();
  const Message = useMessage();

  const sessions = ref<WecomSessionItem[]>([]);
  const sessionTotal = ref(0);
  const sessionPage = ref(1);
  const sessionPageSize = ref(20);

  const activeSessionKey = ref('');
  const messages = ref<WecomMessageItem[]>([]);
  const messageTotal = ref(0);
  const messagePage = ref(1);
  const messagePageSize = ref(30);

  const selectedIds = ref<string[]>([]);
  const customerOverrideId = ref<string | null>(null);
  const ownerOverrideId = ref<string | null>(null);
  const customerSelectOptions = ref<{ label: string; value: string }[]>([]);
  const customerSearchLoading = ref(false);
  const syncing = ref(false);
  const deletingSessionKey = ref('');

  const activeSession = computed(() => sessions.value.find((s) => s.sessionKey === activeSessionKey.value));

  const activeContext = computed(() => {
    if (!messages.value.length) {
      return activeSession.value;
    }
    const picked = messages.value.find((m) => selectedIds.value.includes(m.id));
    return picked || activeSession.value;
  });

  const selectedSummary = computed(() => {
    const lines: string[] = [];
    const map = new Map(messages.value.map((m) => [m.id, m] as const));
    selectedIds.value.forEach((id) => {
      const m = map.get(id);
      if (!m || m.synced) return;
      const head = m.sendTime ? dayjs(m.sendTime).format('YYYY-MM-DD HH:mm') : '';
      lines.push(`[${head}] ${m.messageDirection || ''} ${m.contentText || ''}`);
    });
    return lines.join('\n');
  });

  const canSync = computed(
    () =>
      !!activeSessionKey.value &&
      selectedIds.value.length > 0 &&
      messages.value.some((m) => selectedIds.value.includes(m.id) && !m.synced)
  );

  function displaySessionTitle(s: WecomSessionItem) {
    if (s.matchedCustomerName) {
      return s.matchedCustomerName;
    }
    if (String(s.chatType || '').toLowerCase() === 'room' || s.roomid || s.sessionKey.startsWith('room:')) {
      return `${t('customer.wecomMonitor.roomChat')} ${s.roomid || s.sessionKey}`;
    }
    return s.wecomCustomerExternalUserid || s.sessionKey;
  }

  function formatDirection(dir?: string) {
    if (!dir) return '';
    return dir.toUpperCase() === 'INBOUND'
      ? t('customer.wecomMonitor.directionInbound')
      : t('customer.wecomMonitor.directionOutbound');
  }

  async function loadMessages() {
    if (!activeSessionKey.value) return;
    const res = await getWecomMessagePage({
      current: messagePage.value,
      pageSize: messagePageSize.value,
      sessionKey: activeSessionKey.value,
    });
    messages.value = res.list || [];
    messageTotal.value = res.total || 0;
  }

  async function selectSession(key: string) {
    activeSessionKey.value = key;
    selectedIds.value = [];
    messagePage.value = 1;
    customerOverrideId.value = null;
    ownerOverrideId.value = null;
    await loadMessages();
  }

  async function loadSessions() {
    const res = await getWecomSessionPage({
      current: sessionPage.value,
      pageSize: sessionPageSize.value,
    });
    sessions.value = res.list || [];
    sessionTotal.value = res.total || 0;
    if (!activeSessionKey.value && sessions.value.length) {
      await selectSession(sessions.value[0].sessionKey);
    }
  }

  async function handleDeleteSession(session: WecomSessionItem) {
    if (!session?.sessionKey || deletingSessionKey.value) {
      return;
    }
    deletingSessionKey.value = session.sessionKey;
    try {
      await deleteWecomSession({ sessionKey: session.sessionKey });
      Message.success(t('customer.wecomMonitor.deleteSessionSuccess'));
      if (activeSessionKey.value === session.sessionKey) {
        activeSessionKey.value = '';
        messages.value = [];
        messageTotal.value = 0;
        selectedIds.value = [];
      }
      if (sessions.value.length === 1 && sessionPage.value > 1) {
        sessionPage.value -= 1;
      }
      await loadSessions();
    } finally {
      deletingSessionKey.value = '';
    }
  }

  function toggleMessage(id: string, checked: boolean) {
    if (checked) {
      if (!selectedIds.value.includes(id)) {
        selectedIds.value = [...selectedIds.value, id];
      }
    } else {
      selectedIds.value = selectedIds.value.filter((x) => x !== id);
    }
  }

  const handleCustomerSearch = debounce(async (keyword: string) => {
    customerSearchLoading.value = true;
    try {
      const res = await getCustomerOptions({ keyword, current: 1, pageSize: 50 });
      customerSelectOptions.value = (res.list || []).map((c) => ({
        label: c.name,
        value: String(c.id),
      }));
    } finally {
      customerSearchLoading.value = false;
    }
  }, 300);

  async function fetchUserOptions(params: Record<string, any>) {
    const list: any = await getUserOptions();
    const kw = String(params.keyword || '').toLowerCase();
    const arr = Array.isArray(list) ? list : list?.data || [];
    return arr.filter(
      (u: any) =>
        !kw ||
        String(u.name || '')
          .toLowerCase()
          .includes(kw)
    );
  }

  async function handleSync() {
    if (!canSync.value) {
      Message.warning(t('customer.wecomMonitor.selectMessages'));
      return;
    }
    const ids = selectedIds.value.filter((id) => {
      const m = messages.value.find((x) => x.id === id);
      return m && !m.synced;
    });
    if (!ids.length) {
      Message.warning(t('customer.wecomMonitor.selectMessages'));
      return;
    }
    syncing.value = true;
    try {
      await syncWecomToFollow({
        eventIds: ids,
        customerId: customerOverrideId.value || undefined,
        ownerUserId: ownerOverrideId.value || undefined,
      });
      Message.success(t('customer.wecomMonitor.syncSuccess'));
      selectedIds.value = [];
      await loadMessages();
      await loadSessions();
    } finally {
      syncing.value = false;
    }
  }

  onMounted(() => {
    handleCustomerSearch('');
  });

  loadSessions();
</script>

<style scoped lang="less">
  .session-row--active {
    color: #ffffff;
    background-color: var(--primary-1);
  }
  .session-row--active .opacity-80,
  .session-row--active .opacity-70 {
    opacity: 0.95;
  }
</style>
