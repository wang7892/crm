<template>
  <div ref="pageRef" class="h-full">
    <CrmCard v-if="isCustomerPage" no-content-padding hide-footer>
      <div class="flex h-full flex-col px-[16px] pt-[16px]">
        <div class="mb-[16px] flex items-center justify-between gap-[16px]">
          <div class="flex min-w-0 items-center gap-[12px]">
            <n-button
              text
              class="!h-[32px] !w-[32px]"
              :aria-label="t('customerFollow.backToSpecialist')"
              @click="backToSpecialistList"
            >
              <template #icon>
                <n-icon size="20">
                  <ArrowBack />
                </n-icon>
              </template>
            </n-button>
            <div class="truncate text-[16px] font-semibold text-[var(--text-n1)]">{{ customerPageTitle }}</div>
            <div class="customer-source-filter shrink-0">
              <button
                v-for="item in customerSourceFilters"
                :key="item.value"
                type="button"
                class="customer-source-filter__button"
                :class="{ 'customer-source-filter__button--active': activeCustomerSource === item.value }"
                @click="handleCustomerSourceFilter(item.value)"
              >
                {{ t(item.labelKey) }}
              </button>
            </div>
          </div>
          <CrmSearchInput
            v-model:value="customerKeyword"
            class="!w-[280px]"
            placeholder="common.searchByName"
            @search="handleCustomerSearch"
          />
        </div>
        <n-data-table
          remote
          flex-height
          class="min-h-0 flex-1"
          :columns="customerColumns"
          :data="customerList"
          :loading="customerLoading"
          :pagination="customerPagination"
          :row-key="(row: FollowSpecialistCustomerItem) => row.customerId"
        />
      </div>
    </CrmCard>

    <CrmCard v-else no-content-padding hide-footer>
      <div class="flex h-full flex-col px-[16px] pt-[16px]">
        <div class="mb-[16px] flex items-center justify-between gap-[16px]">
          <div class="flex items-center gap-[12px]">
            <n-button v-permission="['SYS_ORGANIZATION:ADD']" type="primary" @click="openAddMemberDrawer">
              {{ t('org.addMember') }}
            </n-button>
          </div>
          <div class="flex items-center gap-[8px]">
            <CrmSearchInput
              v-model:value="specialistKeyword"
              class="!w-[280px]"
              placeholder="common.searchByNamePhone"
              @search="handleSpecialistSearch"
            />
            <n-popover
              v-model:show="columnSettingVisible"
              trigger="click"
              placement="bottom-end"
              class="crm-table-column-setting-popover"
            >
              <template #trigger>
                <n-button
                  :ghost="columnSettingVisible"
                  :type="columnSettingVisible ? 'primary' : 'default'"
                  class="outline--secondary px-[8px]"
                >
                  <CrmIcon
                    type="iconicon_set_up"
                    :class="`cursor-pointer ${columnSettingVisible ? 'text-[var(--primary-8)]' : ''}`"
                    :size="16"
                  />
                </n-button>
              </template>
              <div class="min-w-[180px] p-[8px]">
                <div class="mb-[8px] text-[12px] font-medium text-[var(--text-n1)]">
                  {{ t('crmTable.columnSetting.tableHeaderDisplaySettings') }}
                </div>
                <div class="flex flex-col gap-[8px]">
                  <n-checkbox
                    v-for="item in specialistColumnOptions"
                    :key="item.key"
                    :checked="specialistVisibleColumnKeys.includes(item.key)"
                    :disabled="item.required"
                    @update:checked="(checked) => updateSpecialistColumnVisible(item.key, checked)"
                  >
                    {{ item.label }}
                  </n-checkbox>
                </div>
              </div>
            </n-popover>
            <n-button type="default" class="outline--secondary px-[8px]" @click="toggleFullScreen">
              <CrmIcon
                class="text-[var(--text-n1)]"
                :type="isFullScreen ? 'iconicon_off_screen' : 'iconicon_full_screen_one'"
                :size="16"
              />
            </n-button>
            <n-button type="default" class="outline--secondary px-[8px]" @click="loadSpecialistList">
              <CrmIcon class="text-[var(--text-n1)]" type="iconicon_refresh" :size="16" />
            </n-button>
          </div>
        </div>
        <n-data-table
          remote
          flex-height
          class="min-h-0 flex-1"
          :columns="specialistColumns"
          :data="specialistList"
          :loading="specialistLoading"
          :pagination="specialistPagination"
          :row-key="(row: FollowSpecialistItem) => row.owner"
        />
      </div>
    </CrmCard>

    <AddMember
      v-model:show="showAddMemberDrawer"
      user-id=""
      active-dep-id=""
      @brash="handleAddMemberSuccess"
      @close="closeAddMemberDrawer"
    />
  </div>
</template>

<script setup lang="ts">
  import { useRoute, useRouter } from 'vue-router';
  import { DataTableColumns, NButton, NCheckbox, NDataTable, NIcon, NPopover } from 'naive-ui';
  import { ArrowBack } from '@vicons/ionicons5';
  import dayjs from 'dayjs';

  import { useI18n } from '@lib/shared/hooks/useI18n';
  import type {
    FollowSpecialistCustomerItem,
    FollowSpecialistCustomerPageParams,
    FollowSpecialistItem,
    FollowSpecialistPageParams,
  } from '@lib/shared/models/follow';

  import CrmCard from '@/components/pure/crm-card/index.vue';
  import CrmIcon from '@/components/pure/crm-icon-font/index.vue';
  import CrmSearchInput from '@/components/pure/crm-search-input/index.vue';
  import CrmAvatar from '@/components/business/crm-avatar/index.vue';
  import AddMember from '@/views/system/org/components/addMember.vue';

  import { getFollowSpecialistCustomerPage, getFollowSpecialistPage } from '@/api/modules';
  import useFullScreen from '@/hooks/useFullScreen';

  import { CustomerRouteEnum } from '@/enums/routeEnum';

  import type { LocationQueryRaw } from 'vue-router';

  const { t } = useI18n();
  const route = useRoute();
  const router = useRouter();
  const pageRef = ref<HTMLElement | null>(null);
  const { isFullScreen, toggleFullScreen } = useFullScreen(pageRef, true);

  const specialistKeyword = ref('');
  const specialistLoading = ref(false);
  const specialistList = ref<FollowSpecialistItem[]>([]);
  const showAddMemberDrawer = ref(false);
  const columnSettingVisible = ref(false);
  const specialistVisibleColumnKeys = ref([
    'index',
    'ownerName',
    'departmentName',
    'phone',
    'customerCount',
    'recordCount',
    'latestFollowTime',
    'operation',
  ]);
  const specialistPager = reactive({
    current: 1,
    pageSize: 30,
    total: 0,
  });

  const customerKeyword = ref('');
  const customerLoading = ref(false);
  const customerList = ref<FollowSpecialistCustomerItem[]>([]);
  const customerPager = reactive({
    current: 1,
    pageSize: 30,
    total: 0,
  });

  function getQueryText(value: unknown) {
    if (Array.isArray(value)) {
      return value[0] ? String(value[0]) : '';
    }
    return value ? String(value) : '';
  }

  const isCustomerPage = computed(() => route.name === CustomerRouteEnum.CUSTOMER_FOLLOW_RECORD_CUSTOMER);
  const activeOwner = computed(() => getQueryText(route.query.owner));
  const activeOwnerName = computed(() => getQueryText(route.query.ownerName));
  const activeCustomerSource = computed(() => getQueryText(route.query.customerSource));
  const customerSourceFilters = [
    { labelKey: 'customerFollow.companyCustomer', value: '公司客户' },
    { labelKey: 'customerFollow.exhibitionCustomer', value: '展会客户' },
  ] as const;

  function formatTime(time?: number) {
    return time ? dayjs(time).format('YYYY-MM-DD HH:mm:ss') : '-';
  }

  function renderText(text?: string | number) {
    return text || text === 0 ? String(text) : '-';
  }

  const specialistColumnOptions = computed(() => [
    { key: 'index', label: t('customerFollow.index'), required: true },
    { key: 'ownerName', label: t('customerFollow.specialist'), required: true },
    { key: 'departmentName', label: t('opportunity.department') },
    { key: 'phone', label: t('common.phoneNumber') },
    { key: 'customerCount', label: t('customerFollow.customerCount') },
    { key: 'recordCount', label: t('customerFollow.recordCount') },
    { key: 'latestFollowTime', label: t('customerFollow.latestFollowTime') },
    { key: 'operation', label: t('common.operation'), required: true },
  ]);

  function updateSpecialistColumnVisible(key: string, checked: boolean) {
    if (checked) {
      specialistVisibleColumnKeys.value = Array.from(new Set([...specialistVisibleColumnKeys.value, key]));
      return;
    }
    specialistVisibleColumnKeys.value = specialistVisibleColumnKeys.value.filter((item) => item !== key);
  }

  function openAddMemberDrawer() {
    showAddMemberDrawer.value = true;
  }

  function closeAddMemberDrawer() {
    showAddMemberDrawer.value = false;
  }

  async function loadSpecialistList() {
    try {
      specialistLoading.value = true;
      const params: FollowSpecialistPageParams = {
        current: specialistPager.current,
        pageSize: specialistPager.pageSize,
        keyword: specialistKeyword.value,
      };
      const res = await getFollowSpecialistPage(params);
      specialistList.value = res.list || [];
      specialistPager.total = res.total || 0;
    } finally {
      specialistLoading.value = false;
    }
  }

  function handleAddMemberSuccess() {
    loadSpecialistList();
  }

  function handleSpecialistSearch(keyword: string) {
    specialistKeyword.value = keyword;
    specialistPager.current = 1;
    loadSpecialistList();
  }

  async function loadCustomerList() {
    if (!activeOwner.value) {
      customerList.value = [];
      customerPager.total = 0;
      return;
    }
    try {
      customerLoading.value = true;
      const params: FollowSpecialistCustomerPageParams = {
        owner: activeOwner.value,
        current: customerPager.current,
        pageSize: customerPager.pageSize,
        keyword: customerKeyword.value,
      };
      if (activeCustomerSource.value) {
        params.customerSource = activeCustomerSource.value;
      }
      const res = await getFollowSpecialistCustomerPage(params);
      customerList.value = res.list || [];
      customerPager.total = res.total || 0;
    } finally {
      customerLoading.value = false;
    }
  }

  function handleCustomerSearch(keyword: string) {
    customerKeyword.value = keyword;
    customerPager.current = 1;
    loadCustomerList();
  }

  function handleCustomerSourceFilter(customerSource: string) {
    customerPager.current = 1;
    const query: LocationQueryRaw = {
      ...route.query,
      owner: activeOwner.value,
      ownerName: activeOwnerName.value,
    };
    if (activeCustomerSource.value === customerSource) {
      delete query.customerSource;
    } else {
      query.customerSource = customerSource;
    }
    router.replace({
      name: CustomerRouteEnum.CUSTOMER_FOLLOW_RECORD_CUSTOMER,
      query,
    });
  }

  function openCustomerPage(row: FollowSpecialistItem) {
    customerKeyword.value = '';
    customerPager.current = 1;
    router.push({
      name: CustomerRouteEnum.CUSTOMER_FOLLOW_RECORD_CUSTOMER,
      query: {
        owner: row.owner,
        ownerName: row.ownerName,
      },
    });
  }

  function backToSpecialistList() {
    router.push({
      name: CustomerRouteEnum.CUSTOMER_FOLLOW_RECORD_INDEX,
    });
  }

  function openCustomerDetail(row: FollowSpecialistCustomerItem) {
    const query: Record<string, string> = {
      id: row.customerId,
      source: 'followSpecialist',
      owner: activeOwner.value,
      ownerName: activeOwnerName.value,
    };
    if (activeCustomerSource.value) {
      query.customerSource = activeCustomerSource.value;
    }
    router.push({
      name: CustomerRouteEnum.CUSTOMER_INDEX,
      query,
    });
  }

  const customerPageTitle = computed(() => {
    return activeOwnerName.value || t('customerFollow.specialist');
  });

  const specialistPagination = computed(() => ({
    page: specialistPager.current,
    pageSize: specialistPager.pageSize,
    itemCount: specialistPager.total,
    showSizePicker: true,
    showQuickJumper: true,
    pageSizes: [10, 20, 30, 50, 100],
    onUpdatePage: (page: number) => {
      specialistPager.current = page;
      loadSpecialistList();
    },
    onUpdatePageSize: (pageSize: number) => {
      specialistPager.pageSize = pageSize;
      specialistPager.current = 1;
      loadSpecialistList();
    },
  }));

  const customerPagination = computed(() => ({
    page: customerPager.current,
    pageSize: customerPager.pageSize,
    itemCount: customerPager.total,
    showSizePicker: true,
    showQuickJumper: true,
    pageSizes: [10, 20, 30, 50, 100],
    onUpdatePage: (page: number) => {
      customerPager.current = page;
      loadCustomerList();
    },
    onUpdatePageSize: (pageSize: number) => {
      customerPager.pageSize = pageSize;
      customerPager.current = 1;
      loadCustomerList();
    },
  }));

  const specialistColumns = computed<DataTableColumns<FollowSpecialistItem>>(() => {
    const columns = [
      {
        title: t('customerFollow.index'),
        key: 'index',
        width: 80,
        render: (_row, index) => (specialistPager.current - 1) * specialistPager.pageSize + index + 1,
      },
      {
        title: t('customerFollow.specialist'),
        key: 'ownerName',
        minWidth: 180,
        render: (row) =>
          h('div', { class: 'flex items-center gap-[8px]' }, [
            h(CrmAvatar, { size: 28, word: row.ownerName }),
            h(
              NButton,
              {
                text: true,
                type: 'primary',
                onClick: () => openCustomerPage(row),
              },
              { default: () => renderText(row.ownerName) }
            ),
          ]),
      },
      {
        title: t('opportunity.department'),
        key: 'departmentName',
        minWidth: 140,
        render: (row) => renderText(row.departmentName),
      },
      {
        title: t('common.phoneNumber'),
        key: 'phone',
        minWidth: 140,
        render: (row) => renderText(row.phone),
      },
      {
        title: t('customerFollow.customerCount'),
        key: 'customerCount',
        width: 120,
        render: (row) => renderText(row.customerCount),
      },
      {
        title: t('customerFollow.recordCount'),
        key: 'recordCount',
        width: 120,
        render: (row) => renderText(row.recordCount),
      },
      {
        title: t('customerFollow.latestFollowTime'),
        key: 'latestFollowTime',
        width: 180,
        render: (row) => formatTime(row.latestFollowTime),
      },
      {
        title: t('common.operation'),
        key: 'operation',
        width: 120,
        fixed: 'right',
        render: (row) =>
          h(
            NButton,
            {
              text: true,
              type: 'primary',
              onClick: () => openCustomerPage(row),
            },
            { default: () => t('customerFollow.viewCustomer') }
          ),
      },
    ] satisfies DataTableColumns<FollowSpecialistItem>;
    return columns.filter((column) => specialistVisibleColumnKeys.value.includes(column.key));
  });

  const customerColumns = computed<DataTableColumns<FollowSpecialistCustomerItem>>(() => [
    {
      title: t('customerFollow.index'),
      key: 'index',
      width: 80,
      render: (_row, index) => (customerPager.current - 1) * customerPager.pageSize + index + 1,
    },
    {
      title: t('opportunity.customerName'),
      key: 'customerName',
      minWidth: 180,
      render: (row) =>
        h(
          NButton,
          {
            text: true,
            type: 'primary',
            onClick: () => openCustomerDetail(row),
          },
          { default: () => renderText(row.customerName) }
        ),
    },
    {
      title: t('customer.contact'),
      key: 'contactName',
      minWidth: 140,
      render: (row) => renderText(row.contactName),
    },
    {
      title: t('common.phoneNumber'),
      key: 'contactPhone',
      minWidth: 140,
      render: (row) => renderText(row.contactPhone),
    },
    {
      title: t('customerFollow.recordCount'),
      key: 'recordCount',
      width: 120,
      render: (row) => renderText(row.recordCount),
    },
    {
      title: t('customerFollow.latestFollowTime'),
      key: 'latestFollowTime',
      width: 180,
      render: (row) => formatTime(row.latestFollowTime),
    },
  ]);

  watch(
    () => [route.name, route.query.owner],
    () => {
      if (isCustomerPage.value) {
        customerKeyword.value = '';
        customerPager.current = 1;
        loadCustomerList();
        return;
      }
      loadSpecialistList();
    },
    { immediate: true }
  );

  watch(
    () => route.query.customerSource,
    () => {
      if (isCustomerPage.value) {
        customerPager.current = 1;
        loadCustomerList();
      }
    }
  );
</script>

<style scoped lang="less">
  .customer-source-filter {
    display: flex;
    align-items: center;
    gap: 10px;
  }
  .customer-source-filter__button {
    padding: 0 10px;
    min-width: 68px;
    height: 26px;
    font-size: 13px;
    border: 1px solid var(--primary-8);
    border-radius: 3px;
    color: var(--primary-8);
    background: #ffffff;
    transition: color 0.2s, border-color 0.2s, background-color 0.2s;
    line-height: 24px;
    cursor: pointer;
    &:hover,
    &--active {
      border-color: var(--primary-8);
      color: var(--primary-8);
      background: var(--primary-6);
    }
  }
</style>
