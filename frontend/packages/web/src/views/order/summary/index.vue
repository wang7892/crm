<template>
  <div ref="orderSummaryCardRef" class="h-full">
    <CrmCard no-content-padding hide-footer>
      <div class="h-full px-[16px] pt-[16px]">
        <CrmTable
          ref="crmTableRef"
          v-bind="propsRes"
          table-row-key="orderNo"
          class="crm-order-summary-table"
          :fullscreen-target-ref="orderSummaryCardRef"
          @page-change="propsEvent.pageChange"
          @page-size-change="propsEvent.pageSizeChange"
          @sorter-change="propsEvent.sorterChange"
          @refresh="searchData"
        >
          <template #tableTop>
            <CrmSearchInput
              v-model:value="keyword"
              class="!w-[320px]"
              placeholder="order.summary.searchPlaceholder"
              @search="searchData"
            />
          </template>
        </CrmTable>
      </div>
    </CrmCard>
  </div>
</template>

<script setup lang="ts">
  import dayjs from 'dayjs';

  import { useI18n } from '@lib/shared/hooks/useI18n';
  import type { OrderSummaryItem } from '@lib/shared/models/order';

  import CrmCard from '@/components/pure/crm-card/index.vue';
  import CrmSearchInput from '@/components/pure/crm-search-input/index.vue';
  import CrmTable from '@/components/pure/crm-table/index.vue';
  import type { CrmDataTableColumn } from '@/components/pure/crm-table/type';
  import useTable from '@/components/pure/crm-table/useTable';

  import { getOrderSummaryList } from '@/api/modules';

  const { t } = useI18n();

  const orderSummaryCardRef = ref<HTMLElement | null>(null);
  const crmTableRef = ref<InstanceType<typeof CrmTable>>();
  const keyword = ref('');

  function formatDateTime(value?: number) {
    return value ? dayjs(value).format('YYYY-MM-DD HH:mm:ss') : '-';
  }

  const columns = computed<CrmDataTableColumn<OrderSummaryItem>[]>(() => [
    {
      title: t('order.orderNo'),
      key: 'orderNo',
      width: 180,
      sorter: true,
      sortOrder: false,
      fixed: 'left',
      ellipsis: {
        tooltip: true,
      },
    },
    {
      title: t('order.processor'),
      key: 'processor',
      width: 160,
      sorter: true,
      sortOrder: false,
      ellipsis: {
        tooltip: true,
      },
    },
    {
      title: t('order.owner'),
      key: 'owner',
      width: 160,
      sorter: true,
      sortOrder: false,
      ellipsis: {
        tooltip: true,
      },
      render: (row) => row.ownerName || row.owner || '-',
    },
    {
      title: t('order.merchandiser'),
      key: 'merchandiser',
      width: 160,
      sorter: true,
      sortOrder: false,
      ellipsis: {
        tooltip: true,
      },
      render: (row) => row.merchandiser || '-',
    },
    {
      title: t('order.orderTime'),
      key: 'orderTime',
      width: 180,
      sorter: true,
      sortOrder: false,
      render: (row) => formatDateTime(row.orderTime),
    },
    {
      title: t('order.quantity'),
      key: 'quantity',
      width: 120,
      sorter: true,
      sortOrder: false,
    },
    {
      title: t('order.unit'),
      key: 'unit',
      width: 100,
      sorter: true,
      sortOrder: false,
      ellipsis: {
        tooltip: true,
      },
    },
    {
      title: t('order.amount'),
      key: 'amount',
      width: 140,
      sorter: true,
      sortOrder: false,
    },
    {
      title: t('order.currency'),
      key: 'currency',
      width: 100,
      sorter: true,
      sortOrder: false,
      ellipsis: {
        tooltip: true,
      },
    },
  ]);

  const { propsRes, propsEvent, loadList, setLoadListParams } = useTable<OrderSummaryItem>(getOrderSummaryList, {
    columns: columns.value,
    containerClass: '.crm-order-summary-table',
  });

  function searchData(value?: string) {
    setLoadListParams({
      keyword: value ?? keyword.value,
    });
    loadList();
    crmTableRef.value?.scrollTo({ top: 0 });
  }

  watch(
    columns,
    (value) => {
      propsRes.value.columns = value;
    },
    { immediate: true }
  );

  onBeforeMount(() => {
    searchData();
  });
</script>
