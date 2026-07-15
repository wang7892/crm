<template>
  <CrmTable
    ref="crmTableRef"
    v-model:checked-row-keys="checkedRowKeys"
    v-bind="propsRes"
    class="crm-contract-table"
    :not-show-table-filter="isAdvancedSearchMode"
    :action-config="actionConfig"
    :fullscreen-target-ref="props.fullscreenTargetRef"
    @page-change="propsEvent.pageChange"
    @page-size-change="propsEvent.pageSizeChange"
    @sorter-change="propsEvent.sorterChange"
    @filter-change="filterChange"
    @batch-action="handleBatchAction"
    @refresh="searchData"
  >
    <template #actionLeft>
      <div class="flex items-center gap-[12px]">
        <n-button v-permission="['CONTRACT:ADD']" type="primary" @click="handleNewClick">
          {{ t('contract.new') }}
        </n-button>
        <CrmImportButton
          v-if="hasAnyPermission(['CONTRACT:IMPORT'])"
          :api-type="FormDesignKeyEnum.CONTRACT"
          :title="t('module.contract')"
          @import-success="() => searchData()"
        />
        <n-button
          v-permission="['CONTRACT:EXPORT']"
          type="primary"
          ghost
          class="n-btn-outline-primary"
          :disabled="propsRes.data.length === 0"
          @click="handleExportAllClick"
        >
          {{ t('common.exportAll') }}
        </n-button>
      </div>
    </template>
    <template #actionRight>
      <CrmAdvanceFilter
        ref="tableAdvanceFilterRef"
        v-model:keyword="keyword"
        :custom-fields-config-list="customFieldsFilterConfig"
        :filter-config-list="filterConfigList"
        @adv-search="handleAdvSearch"
        @keyword-search="searchData"
      />
    </template>
    <template #view>
      <CrmViewSelect
        v-model:active-tab="activeTab"
        :type="FormDesignKeyEnum.CONTRACT"
        :custom-fields-config-list="customFieldsFilterConfig"
        :default-active-tab="CustomerSearchTypeEnum.ALL"
        :filter-config-list="filterConfigList"
        :ignore-cached-active-tabs="[CustomerSearchTypeEnum.SELF]"
        :advanced-original-form="advancedOriginalForm"
        :route-name="ContractRouteEnum.CONTRACT_INDEX"
        @refresh-table-data="searchData"
      />
    </template>
    <template #totalRight>
      <div class="ml-[24px]">
        {{ t('opportunity.averageAmount') }}
        <span class="ml-[4px]">
          {{ abbreviateNumber(totalAmountInfo?.averageAmount, '').value }}
          <span class="unit">
            {{ abbreviateNumber(totalAmountInfo?.averageAmount, '').unit }}
          </span>
        </span>
      </div>
      <div class="ml-[24px]">
        {{ t('opportunity.totalAmount') }}
        <span class="ml-[4px]">
          {{ abbreviateNumber(totalAmountInfo?.amount, '').value }}
          <span class="unit">
            {{ abbreviateNumber(totalAmountInfo?.amount, '').unit }}
          </span>
        </span>
      </div>
    </template>
  </CrmTable>

  <CrmFormCreateDrawer
    v-model:visible="formCreateDrawerVisible"
    :form-key="activeFormKey"
    :source-id="activeSourceId"
    :need-init-detail="needInitDetail"
    @saved="handleFormCreateSaved"
  />

  <CrmTableExportModal
    v-model:show="showExportModal"
    :params="exportParams"
    :export-columns="exportColumns"
    :is-export-all="isExportAll"
    type="contract"
    @create-success="handleExportCreateSuccess"
  />

  <DetailDrawer
    v-model:visible="showDetailDrawer"
    :sourceId="activeSourceId"
    isContractTableDetail
    @refresh="searchData(undefined, activeSourceId)"
    @delete="removeItemFromList(activeSourceId)"
    @showCustomerDrawer="showCustomerDrawer"
    @open-business-title-drawer="handleOpenBusinessTitleDrawer"
  />

  <businessTitleDrawer v-model:visible="showBusinessTitleDetailDrawer" :source-id="activeBusinessTitleSourceId" />
</template>

<script setup lang="ts">
  import { useRoute } from 'vue-router';
  import { DataTableRowKey, NButton, useMessage } from 'naive-ui';

  import { CustomerSearchTypeEnum } from '@lib/shared/enums/customerEnum';
  import { FieldTypeEnum, FormDesignKeyEnum } from '@lib/shared/enums/formDesignEnum';
  import { useI18n } from '@lib/shared/hooks/useI18n';
  import useLocale from '@lib/shared/locale/useLocale';
  import { abbreviateNumber, characterLimit } from '@lib/shared/method';
  import { ExportTableColumnItem } from '@lib/shared/models/common';
  import type { ContractItem } from '@lib/shared/models/contract';

  import CrmAdvanceFilter from '@/components/pure/crm-advance-filter/index.vue';
  import { FilterForm, FilterFormItem, FilterResult } from '@/components/pure/crm-advance-filter/type';
  import type { ActionsItem } from '@/components/pure/crm-more-action/type';
  import CrmNameTooltip from '@/components/pure/crm-name-tooltip/index.vue';
  import CrmTable from '@/components/pure/crm-table/index.vue';
  import CrmTableButton from '@/components/pure/crm-table-button/index.vue';
  import CrmFormCreateDrawer from '@/components/business/crm-form-create-drawer/index.vue';
  import CrmImportButton from '@/components/business/crm-import-button/index.vue';
  import CrmOperationButton from '@/components/business/crm-operation-button/index.vue';
  import CrmTableExportModal from '@/components/business/crm-table-export-modal/index.vue';
  import CrmViewSelect from '@/components/business/crm-view-select/index.vue';
  import businessTitleDrawer from '../../businessTitle/components/detail.vue';
  import DetailDrawer from './detail.vue';

  import { deleteContract, getContractStatistic } from '@/api/modules';
  import { baseFilterConfigList } from '@/config/clue';
  import useFormCreateTable from '@/hooks/useFormCreateTable';
  import useModal from '@/hooks/useModal';
  // import useViewChartParams, { STORAGE_VIEW_CHART_KEY, ViewChartResult } from '@/hooks/useViewChartParams';
  import { getExportColumns } from '@/utils/export';
  import { hasAllPermission, hasAnyPermission } from '@/utils/permission';

  import { ContractRouteEnum } from '@/enums/routeEnum';

  const props = defineProps<{
    fullscreenTargetRef?: HTMLElement | null;
  }>();
  const emit = defineEmits<{
    (
      e: 'openCustomerDrawer',
      params: { customerId: string; inCustomerPool: boolean; poolId: string },
      readonly: boolean
    ): void;
  }>();

  const { t } = useI18n();
  const Message = useMessage();
  const { currentLocale } = useLocale(Message.loading);
  const { openModal } = useModal();
  const route = useRoute();

  const activeTab = ref();
  const keyword = ref('');

  // 操作
  const checkedRowKeys = ref<DataTableRowKey[]>([]);
  const tableRefreshId = ref(0);
  const tableRemoveRefreshId = ref('');

  const formCreateDrawerVisible = ref(false);
  const activeSourceId = ref('');
  const needInitDetail = ref(false);
  const activeFormKey = ref(FormDesignKeyEnum.CONTRACT);

  function handleNewClick() {
    needInitDetail.value = false;
    activeFormKey.value = FormDesignKeyEnum.CONTRACT;
    activeSourceId.value = '';
    formCreateDrawerVisible.value = true;
  }

  const showExportModal = ref<boolean>(false);
  const isExportAll = ref(false);

  function handleExportAllClick() {
    isExportAll.value = true;
    showExportModal.value = true;
  }
  function handleExportCreateSuccess() {
    checkedRowKeys.value = [];
  }

  function handleBatchAction(item: ActionsItem) {
    switch (item.key) {
      case 'exportChecked':
        isExportAll.value = false;
        showExportModal.value = true;
        break;
      default:
        break;
    }
  }

  function getOperationGroupList() {
    return [
      {
        label: t('common.edit'),
        key: 'edit',
        permission: ['CONTRACT:UPDATE'],
      },
      {
        label: t('common.delete'),
        key: 'delete',
        permission: ['CONTRACT:DELETE'],
      },
    ].filter((item) => hasAllPermission(item.permission));
  }

  const showDetailDrawer = ref(false);

  function handleEdit(id: string) {
    activeFormKey.value = FormDesignKeyEnum.CONTRACT;
    activeSourceId.value = id;
    needInitDetail.value = true;
    formCreateDrawerVisible.value = true;
  }

  function handleDelete(row: ContractItem) {
    openModal({
      type: 'error',
      title: t('common.deleteConfirmTitle', { name: characterLimit(row.name) }),
      content: t('common.deleteConfirmContent'),
      positiveText: t('common.confirmDelete'),
      negativeText: t('common.cancel'),
      onPositiveClick: async () => {
        try {
          await deleteContract(row.id);
          Message.success(t('common.deleteSuccess'));
          tableRemoveRefreshId.value = row.id;
        } catch (error) {
          // eslint-disable-next-line no-console
          console.error(error);
        }
      },
    });
  }

  async function handleActionSelect(row: ContractItem, actionKey: string) {
    switch (actionKey) {
      case 'edit':
        handleEdit(row.id);
        break;
      case 'delete':
        handleDelete(row);
        break;
      default:
        break;
    }
  }

  function showCustomerDrawer(params: { customerId: string; inCustomerPool: boolean; poolId: string }) {
    emit(
      'openCustomerDrawer',
      {
        customerId: params.customerId,
        inCustomerPool: params.inCustomerPool,
        poolId: params.poolId || '',
      },
      false
    );
  }

  const operationGroupList = getOperationGroupList();

  const { useTableRes, customFieldsFilterConfig, fieldList } = await useFormCreateTable({
    formKey: FormDesignKeyEnum.CONTRACT,
    operationColumn: operationGroupList.length
      ? {
          key: 'operation',
          width: currentLocale.value === 'en-US' ? 180 : 150,
          fixed: 'right',
          render: (row: ContractItem) =>
            h(CrmOperationButton, {
              groupList: operationGroupList,
              onSelect: (key: string) => handleActionSelect(row, key),
            }),
        }
      : undefined,
    specialRender: {
      name: (row: ContractItem) => {
        return h(
          CrmTableButton,
          {
            onClick: () => {
              activeSourceId.value = row.id;
              showDetailDrawer.value = true;
            },
          },
          { default: () => row.name, trigger: () => row.name }
        );
      },
      customerId: (row: ContractItem) => {
        return !row.customerName ||
          (!row.inCustomerPool && !hasAnyPermission(['CUSTOMER_MANAGEMENT:READ'])) ||
          (row.inCustomerPool && !hasAnyPermission(['CUSTOMER_MANAGEMENT_POOL:READ']))
          ? h(
              CrmNameTooltip,
              { text: row.customerName },
              {
                default: () => row.customerName,
              }
            )
          : h(
              CrmTableButton,
              {
                onClick: () => {
                  showCustomerDrawer(row);
                },
              },
              { default: () => row.customerName, trigger: () => row.customerName }
            );
      },
    },
    permission: ['CONTRACT:EXPORT'],
    containerClass: '.crm-contract-table',
  });
  const {
    propsRes,
    propsEvent,
    tableQueryParams,
    filterItem,
    advanceFilter,
    loadList,
    setLoadListParams,
    setAdvanceFilter,
  } = useTableRes;

  const statisticInfo = ref({ amount: 0, averageAmount: 0 });
  async function getStatistic(_keyword?: string) {
    try {
      const res = await getContractStatistic({
        keyword: _keyword ?? keyword.value,
        viewId: activeTab.value,
        combineSearch: advanceFilter,
        filters: filterItem.value,
      });
      statisticInfo.value = res;
    } catch (error) {
      // eslint-disable-next-line no-console
      console.error(error);
    }
  }

  const totalAmountInfo = computed(() => {
    if (checkedRowKeys.value.length > 0) {
      const amount = propsRes.value.data
        .filter((item: ContractItem) => checkedRowKeys.value.includes(item.id))
        .reduce((total: number, item: ContractItem) => total + (item.amount || 0), 0);
      return {
        averageAmount: amount / checkedRowKeys.value.length,
        amount,
      };
    }
    return {
      averageAmount: statisticInfo.value?.averageAmount ?? 0,
      amount: statisticInfo.value?.amount ?? 0,
    };
  });

  function filterChange(val: any) {
    propsEvent.value.filterChange(val);
    getStatistic();
  }

  const exportColumns = computed<ExportTableColumnItem[]>(() =>
    getExportColumns(propsRes.value.columns, customFieldsFilterConfig.value as FilterFormItem[], fieldList.value)
  );
  const exportParams = computed(() => {
    return {
      ...tableQueryParams.value,
      ids: checkedRowKeys.value,
    };
  });

  const actionConfig = computed(() => {
    return {
      baseAction: [
        {
          label: t('common.exportChecked'),
          key: 'exportChecked',
          permission: ['CONTRACT:EXPORT'],
        },
      ],
    };
  });

  // 表格
  const filterConfigList = computed<FilterFormItem[]>(() => [
    {
      title: t('opportunity.department'),
      dataIndex: 'departmentId',
      type: FieldTypeEnum.TREE_SELECT,
      treeSelectProps: {
        labelField: 'name',
        keyField: 'id',
        multiple: true,
        clearFilterAfterSelect: false,
        type: 'department',
        checkable: true,
        showContainChildModule: true,
        containChildIds: [],
      },
    },
    ...baseFilterConfigList,
  ]);

  const crmTableRef = ref<InstanceType<typeof CrmTable>>();

  const isAdvancedSearchMode = ref(false);
  const advancedOriginalForm = ref<FilterForm | undefined>();
  function handleAdvSearch(filter: FilterResult, isAdvancedMode: boolean, originalForm?: FilterForm) {
    keyword.value = '';
    advancedOriginalForm.value = originalForm;
    isAdvancedSearchMode.value = isAdvancedMode;
    setAdvanceFilter(filter);
    loadList();
    getStatistic();
    crmTableRef.value?.scrollTo({ top: 0 });
  }

  function searchData(val?: string, refreshId?: string) {
    setLoadListParams({ keyword: val ?? keyword.value, viewId: activeTab.value });
    loadList(false, refreshId);
    getStatistic(val);
    if (!refreshId) {
      crmTableRef.value?.scrollTo({ top: 0 });
    }
  }

  watch(
    () => tableRefreshId.value,
    () => {
      checkedRowKeys.value = [];
      searchData();
    }
  );

  const showBusinessTitleDetailDrawer = ref(false);
  const activeBusinessTitleSourceId = ref<string>('');
  function handleOpenBusinessTitleDrawer(params: { id: string }) {
    activeBusinessTitleSourceId.value = params.id;
    showBusinessTitleDetailDrawer.value = true;
  }

  function handleFormCreateSaved(res: any) {
    if (needInitDetail.value) {
      searchData(undefined, res.id);
    } else {
      searchData();
    }
  }

  function removeItemFromList(id: string) {
    propsRes.value.data = propsRes.value.data.filter((item) => item.id !== id);
    propsRes.value.crmPagination = {
      ...propsRes.value.crmPagination,
      itemCount: (propsRes.value.crmPagination?.itemCount ?? 1) - 1,
    };
  }

  watch(
    () => tableRemoveRefreshId.value,
    (val) => {
      if (val) {
        removeItemFromList(val);
      }
    }
  );

  // 先不上
  // function handleGeneratedChart(res: FilterResult, form: FilterForm) {
  //   advancedOriginalForm.value = form;
  //   setAdvanceFilter(res);
  //   tableAdvanceFilterRef.value?.setAdvancedFilter(res, true);
  //   searchData();
  // }

  // const { initTableViewChartParams, getChartViewId } = useViewChartParams();

  // function viewChartCallBack(params: ViewChartResult) {
  //   const { viewId, formModel, filterResult } = params;
  //   tableAdvanceFilterRef.value?.initFormModal(formModel, true);
  //   setAdvanceFilter(filterResult);
  //   activeTab.value = viewId;
  // }

  watch(
    () => activeTab.value,
    (val) => {
      if (val) {
        checkedRowKeys.value = [];
        setLoadListParams({ keyword: keyword.value, viewId: activeTab.value });
        // initTableViewChartParams(viewChartCallBack);
        crmTableRef.value?.setColumnSort(val);
        getStatistic();
      }
    },
    { immediate: true }
  );

  onMounted(async () => {
    if (route.query.id) {
      activeSourceId.value = route.query.id as string;
      showDetailDrawer.value = true;
    }
  });

  // onBeforeUnmount(() => {
  //   sessionStorage.removeItem(STORAGE_VIEW_CHART_KEY);
  // });
</script>
