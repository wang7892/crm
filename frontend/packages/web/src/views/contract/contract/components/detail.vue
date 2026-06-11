<template>
  <CrmDrawer v-model:show="visible" resizable no-padding :width="800" :footer="false" :title="title">
    <template #titleRight>
      <CrmButtonGroup class="gap-[12px]" :list="buttonList" not-show-divider @select="handleButtonClick" />
    </template>
    <div class="h-full bg-[var(--text-n9)] p-[16px]">
      <CrmCard no-content-padding hide-footer auto-height class="mb-[16px]">
        <CrmTab v-model:active-tab="activeTab" no-content :tab-list="tabList" type="line" />
      </CrmCard>

      <CrmCard hide-footer :special-height="64" noContentBottomPadding>
        <!-- 需要用到 detailInfo 所以这里不用 v-if -->
        <div v-show="activeTab === 'contract'">
          <CrmFormDescription
            :form-key="FormDesignKeyEnum.CONTRACT_SNAPSHOT"
            :source-id="props.sourceId"
            :column="2"
            :refresh-key="refreshKey"
            label-width="auto"
            value-align="start"
            tooltip-position="top-start"
            readonly
            :isContractTableDetail="props.isContractTableDetail"
            @openCustomerDetail="emit('showCustomerDrawer', $event)"
            @openOpportunityDetail="openOpportunityDetail"
            @openQuotationDetail="openQuotationDetail"
            @init="handleInit"
          />
        </div>
        <InvoiceTable
          v-if="activeTab === 'invoice'"
          :sourceId="props.sourceId"
          :sourceName="title"
          is-contract-tab
          :readonly="getReadonlyInvoice"
          @open-business-title-drawer="showBusinessTitleDetail"
        />
        <OrderTable
          v-if="activeTab === 'order'"
          :formKey="FormDesignKeyEnum.CONTRACT_ORDER"
          :sourceId="props.sourceId"
          :sourceName="title"
          is-contract-tab
          :readonly="getReadonlyInvoice"
          @open-customer-drawer="emit('showCustomerDrawer', $event)"
        />
      </CrmCard>
    </div>
    <CrmFormCreateDrawer
      v-model:visible="formCreateDrawerVisible"
      :source-id="activeSourceId"
      :form-key="activeFormKey"
      :need-init-detail="needInitDetail"
      @saved="() => handleSaved()"
    />
    <QuotationDetailDrawer
      v-model:visible="showQuotationDetailDrawer"
      :source-id="activeQuotationSourceId"
      @edit="handleEditQuotation"
      @refresh="handleSaved()"
    />
    <OptOverviewDrawer
      v-model:show="showOptOverviewDrawer"
      :detail="activeOpportunity"
      @refresh="handleSaved()"
      @open-customer-drawer="emit('showCustomerDrawer', $event)"
    />
  </CrmDrawer>
</template>

<script lang="ts" setup>
  import { useMessage } from 'naive-ui';

  import { FormDesignKeyEnum } from '@lib/shared/enums/formDesignEnum';
  import { useI18n } from '@lib/shared/hooks/useI18n';
  import { characterLimit } from '@lib/shared/method';
  import type { ContractItem } from '@lib/shared/models/contract';
  import { CollaborationType } from '@lib/shared/models/customer';

  import CrmButtonGroup from '@/components/pure/crm-button-group/index.vue';
  import CrmCard from '@/components/pure/crm-card/index.vue';
  import CrmDrawer from '@/components/pure/crm-drawer/index.vue';
  import CrmTab from '@/components/pure/crm-tab/index.vue';
  import CrmFormCreateDrawer from '@/components/business/crm-form-create-drawer/index.vue';
  import CrmFormDescription from '@/components/business/crm-form-description/index.vue';
  import InvoiceTable from '@/views/contract/invoice/components/invoiceTable.vue';
  import OptOverviewDrawer from '@/views/opportunity/components/optOverviewDrawer.vue';
  import QuotationDetailDrawer from '@/views/opportunity/components/quotation/detail.vue';
  import OrderTable from '@/views/order/order/components/orderTable.vue';

  import { deleteContract } from '@/api/modules';
  import useModal from '@/hooks/useModal';
  import { hasAnyPermission } from '@/utils/permission';

  const props = defineProps<{
    sourceId: string;
    isContractTableDetail?: boolean;
  }>();
  const emit = defineEmits<{
    (e: 'refresh'): void;
    (e: 'delete'): void;
    (e: 'showCustomerDrawer', params: { customerId: string; inCustomerPool: boolean; poolId: string }): void;
    (e: 'openBusinessTitleDrawer', params: { id: string }): void;
  }>();

  const visible = defineModel<boolean>('visible', {
    required: true,
  });

  const Message = useMessage();
  const { openModal } = useModal();
  const { t } = useI18n();
  const title = ref('');
  const detailInfo = ref();

  const activeTab = ref('contract');

  const tabList = computed(() =>
    [
      {
        name: 'contract',
        tab: t('module.contract'),
        permission: ['CONTRACT:READ'],
      },
      {
        name: 'invoice',
        tab: t('module.invoice'),
        permission: ['CONTRACT_INVOICE:READ'],
      },
      {
        name: 'order',
        tab: t('module.order'),
        permission: ['ORDER:READ'],
      },
    ].filter((item) => hasAnyPermission(item.permission))
  );

  const buttonList = computed(() => [
    {
      key: 'edit',
      label: t('common.edit'),
      permission: ['CONTRACT:UPDATE'],
      text: false,
      ghost: true,
      class: 'n-btn-outline-primary',
    },
    {
      label: t('common.delete'),
      key: 'delete',
      text: false,
      ghost: true,
      danger: true,
      class: 'n-btn-outline-primary',
      permission: ['CONTRACT:DELETE'],
    },
  ]);

  function handleInit(type?: CollaborationType, name?: string, detail?: Record<string, any>) {
    title.value = name || '';
    detailInfo.value = detail ?? {};
  }

  const formCreateDrawerVisible = ref(false);
  const needInitDetail = ref(true);
  const activeFormKey = ref(FormDesignKeyEnum.CONTRACT);
  const activeSourceId = ref('');

  function handleEdit() {
    needInitDetail.value = true;
    activeFormKey.value = FormDesignKeyEnum.CONTRACT;
    activeSourceId.value = props.sourceId;
    formCreateDrawerVisible.value = true;
  }

  const refreshKey = ref(0);
  function handleSaved() {
    refreshKey.value += 1;
    emit('refresh');
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
          visible.value = false;
          emit('delete');
        } catch (error) {
          // eslint-disable-next-line no-console
          console.error(error);
        }
      },
    });
  }

  // 回款
  const showQuotationDetailDrawer = ref(false);
  const activeQuotationSourceId = ref('');
  function openQuotationDetail(params: { id: string }) {
    showQuotationDetailDrawer.value = true;
    activeQuotationSourceId.value = params.id;
  }

  function handleEditQuotation(id: string) {
    activeFormKey.value = FormDesignKeyEnum.OPPORTUNITY_QUOTATION;
    activeSourceId.value = id;
    needInitDetail.value = true;
    formCreateDrawerVisible.value = true;
  }

  const showOptOverviewDrawer = ref<boolean>(false);
  const activeOpportunity = ref();
  function openOpportunityDetail(params: { id: string }) {
    showOptOverviewDrawer.value = true;
    activeOpportunity.value = {
      id: params.id,
    };
  }

  const getReadonlyInvoice = computed(() => false);

  async function handleButtonClick(actionKey: string) {
    switch (actionKey) {
      case 'edit':
        handleEdit();
        break;
      case 'delete':
        handleDelete(detailInfo.value);
        break;
      default:
        break;
    }
  }

  function showBusinessTitleDetail(params: { id: string }) {
    emit('openBusinessTitleDrawer', params);
  }
</script>
