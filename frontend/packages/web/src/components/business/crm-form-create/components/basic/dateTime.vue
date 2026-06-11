<template>
  <n-form-item
    :label="props.fieldConfig.name"
    :path="props.path"
    :rule="props.fieldConfig.rules"
    :required="props.fieldConfig.rules.some((rule) => rule.key === 'required')"
    :label-placement="props.isSubTableField || props.isSubTableRender ? 'top' : props.formConfig?.labelPos"
    :show-label="!props.isSubTableRender && !props.isDescriptionRender"
  >
    <template #label>
      <div v-if="props.fieldConfig.showLabel" class="flex h-[22px] items-center gap-[4px] whitespace-nowrap">
        <div class="one-line-text">{{ props.fieldConfig.name }}</div>
        <CrmIcon v-if="props.fieldConfig.resourceFieldId" type="iconicon_correlation" />
      </div>
      <div v-else class="h-[22px]"></div>
    </template>
    <div
      v-if="props.fieldConfig.description"
      class="crm-form-create-item-desc"
      v-html="props.fieldConfig.description"
    ></div>
    <n-divider v-if="props.isSubTableField && !props.isSubTableRender" class="!my-0" />
    <n-date-picker
      v-model:value="value"
      :type="props.fieldConfig.dateType"
      :format="dateFormat"
      :placeholder="props.fieldConfig.placeholder"
      :disabled="props.fieldConfig.editable === false || props.disabled || !!props.fieldConfig.resourceFieldId"
      :input-readonly="false"
      class="w-full"
      :status="props.feedback ? 'error' : undefined"
      @update-value="handleUpdateValue"
      @blur="handleManualInputCommit"
      @keydown.enter.capture="handleManualInputCommit"
    >
    </n-date-picker>
  </n-form-item>
</template>

<script setup lang="ts">
  import { NDatePicker, NDivider, NFormItem } from 'naive-ui';

  import type { FormConfig } from '@lib/shared/models/system/module';

  import { FormCreateField } from '../../types';

  const props = defineProps<{
    fieldConfig: FormCreateField;
    formConfig?: FormConfig;
    path: string;
    needInitDetail?: boolean; // 判断是否编辑情况
    disabled?: boolean;
    isSubTableField?: boolean; // 是否是子表字段
    isSubTableRender?: boolean; // 是否是子表渲染
    isDescriptionRender?: boolean; // 是否是描述渲染
    feedback?: string;
  }>();
  const emit = defineEmits<{
    (e: 'change', value: null | number | (string | number)[]): void;
  }>();

  const value = defineModel<null | number | [number, number]>('value', {
    default: null,
  });

  const dateFormat = computed(() => {
    if (props.fieldConfig.dateType === 'month') {
      return 'yyyy-MM';
    }
    if (props.fieldConfig.dateType === 'date') {
      return 'yyyy-MM-dd';
    }
    return 'yyyy-MM-dd HH:mm:ss';
  });

  function handleUpdateValue(val: null | number | (string | number)[]) {
    emit('change', val);
  }

  function normalizeManualDateInput(input: string) {
    return input
      .trim()
      .replace(/[\u5e74/.]/g, '-')
      .replace(/\u6708/g, '-')
      .replace(/[\u65e5\u53f7]/g, '')
      .replace(/T/g, ' ')
      .replace(/\s+/g, ' ');
  }

  function getValidTimestamp(year: number, month: number, day: number, hour = 0, minute = 0, second = 0) {
    const date = new Date(year, month - 1, day, hour, minute, second);
    if (
      date.getFullYear() !== year ||
      date.getMonth() !== month - 1 ||
      date.getDate() !== day ||
      date.getHours() !== hour ||
      date.getMinutes() !== minute ||
      date.getSeconds() !== second
    ) {
      return undefined;
    }
    return date.getTime();
  }

  function parseManualDateInput(input: string) {
    const normalized = normalizeManualDateInput(input);
    if (!normalized) {
      return null;
    }

    if (/^\d{13}$/.test(normalized) || /^\d{10}$/.test(normalized)) {
      const timestamp = Number(normalized.length === 10 ? `${normalized}000` : normalized);
      return Number.isNaN(new Date(timestamp).getTime()) ? undefined : timestamp;
    }

    if (/^\d{8}$/.test(normalized)) {
      return getValidTimestamp(
        Number(normalized.slice(0, 4)),
        Number(normalized.slice(4, 6)),
        Number(normalized.slice(6, 8))
      );
    }

    if (props.fieldConfig.dateType === 'month') {
      const monthMatch = normalized.match(/^(\d{4})-(\d{1,2})$/);
      if (!monthMatch) {
        return undefined;
      }
      return getValidTimestamp(Number(monthMatch[1]), Number(monthMatch[2]), 1);
    }

    const dateMatch = normalized.match(/^(\d{4})-(\d{1,2})-(\d{1,2})(?: (\d{1,2})(?::(\d{1,2})(?::(\d{1,2}))?)?)?$/);
    if (!dateMatch) {
      return undefined;
    }

    const [, year, month, day, hour = '0', minute = '0', second = '0'] = dateMatch;
    return getValidTimestamp(
      Number(year),
      Number(month),
      Number(day),
      props.fieldConfig.dateType === 'datetime' ? Number(hour) : 0,
      props.fieldConfig.dateType === 'datetime' ? Number(minute) : 0,
      props.fieldConfig.dateType === 'datetime' ? Number(second) : 0
    );
  }

  function handleManualInputCommit(event: FocusEvent | KeyboardEvent) {
    const target = event.target as HTMLInputElement | null;
    if (target?.tagName !== 'INPUT') {
      return;
    }
    const timestamp = parseManualDateInput(target.value);
    if (timestamp === undefined) {
      return;
    }
    value.value = timestamp;
    emit('change', timestamp);
  }

  watch(
    () => props.fieldConfig.defaultValue,
    (val) => {
      if (!props.needInitDetail) {
        value.value = val !== undefined ? val : value.value;
        emit('change', value.value);
      }
    },
    {
      immediate: true,
    }
  );

  watch(
    () => props.fieldConfig.dateDefaultType,
    (val) => {
      if (val === 'current') {
        value.value = new Date().getTime();
      } else if (val === 'custom' && props.fieldConfig.defaultValue === null) {
        value.value = null;
      }
      emit('change', value.value);
    },
    {
      immediate: true,
    }
  );
</script>

<style lang="less" scoped></style>
