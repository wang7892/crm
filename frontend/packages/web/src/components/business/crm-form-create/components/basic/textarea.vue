<template>
  <n-form-item
    :label="props.fieldConfig.name"
    :path="props.path"
    :rule="props.fieldConfig.rules"
    :required="props.fieldConfig.rules.some((rule) => rule.key === 'required')"
    :label-placement="props.isSubTableField || props.isSubTableRender ? 'top' : props.formConfig?.labelPos"
    :show-label="!props.isSubTableRender && !props.isDefaultValueRender"
  >
    <template #label>
      <div v-if="props.fieldConfig.showLabel" class="flex h-[22px] items-center gap-[4px] whitespace-nowrap">
        <div class="one-line-text">{{ props.fieldConfig.name }}</div>
        <CrmIcon v-if="props.fieldConfig.resourceFieldId" type="iconicon_correlation" />
      </div>
      <div v-else class="h-[22px]"></div>
    </template>
    <div v-if="showAudioUpload" class="audio-tools-row">
      <n-button
        size="tiny"
        secondary
        :loading="audioUploading"
        :disabled="inputDisabled"
        @click.stop.prevent="handleAudioUploadClick"
      >
        上传音频
      </n-button>
      <n-select
        v-model:value="audioLanguage"
        class="audio-language-select"
        size="tiny"
        :disabled="audioUploading || inputDisabled"
        :options="audioLanguageOptions"
      />
    </div>
    <div
      v-if="props.fieldConfig.description"
      class="crm-form-create-item-desc"
      v-html="props.fieldConfig.description"
    ></div>
    <n-divider v-if="props.isSubTableField && !props.isSubTableRender" class="!my-0" />
    <n-input
      v-model:value="value"
      :maxlength="3000"
      :placeholder="props.fieldConfig.placeholder"
      :disabled="inputDisabled"
      :rows="props.isSubTableField ? 1 : undefined"
      type="textarea"
      clearable
      @update-value="($event) => emit('change', $event)"
    />
  </n-form-item>
  <input
    v-if="showAudioUpload"
    ref="audioInputRef"
    class="hidden"
    type="file"
    accept="audio/*,.mp3,.mp4,.mpeg,.mpga,.m4a,.wav,.webm,.aac,.ogg,.oga,.flac,.amr,.pcm"
    @change="handleAudioFileChange"
  />
</template>

<script setup lang="ts">
  import { NButton, NDivider, NFormItem, NInput, NSelect, useMessage } from 'naive-ui';

  import type { AiAgentAudioLanguage } from '@lib/shared/api/modules/aiAgent';
  import { FormDesignKeyEnum } from '@lib/shared/enums/formDesignEnum';
  import type { FormConfig } from '@lib/shared/models/system/module';

  import { getAiAgentAudioTranscription, transcribeAiAgentAudio } from '@/api/modules';

  import { FormCreateField } from '../../types';
  import type { SelectOption } from 'naive-ui';

  const props = defineProps<{
    fieldConfig: FormCreateField;
    formConfig?: FormConfig;
    formKey?: FormDesignKeyEnum;
    path: string;
    needInitDetail?: boolean; // 判断是否编辑情况
    isSubTableField?: boolean; // 是否是子表字段
    isSubTableRender?: boolean; // 是否是子表渲染
    isDefaultValueRender?: boolean; // 是否是默认值渲染
  }>();
  const emit = defineEmits<{
    (e: 'change', value: string): void;
  }>();

  const value = defineModel<string>('value', {
    default: '',
  });
  const Message = useMessage();

  const audioInputRef = ref<HTMLInputElement>();
  const audioUploading = ref(false);
  const audioPollingTimer = ref<number>();
  const audioLanguage = ref<AiAgentAudioLanguage>('zh');
  const AUDIO_POLL_INTERVAL_MS = 10_000;
  const AUDIO_MAX_POLL_COUNT = 180;
  const audioLanguageOptions: SelectOption[] = [
    { label: '普通话', value: 'zh' },
    { label: 'English', value: 'en' },
  ];
  const followRecordFormKeys = [
    FormDesignKeyEnum.FOLLOW_RECORD,
    FormDesignKeyEnum.FOLLOW_RECORD_CUSTOMER,
    FormDesignKeyEnum.FOLLOW_RECORD_CLUE,
    FormDesignKeyEnum.FOLLOW_RECORD_BUSINESS,
  ];
  const inputDisabled = computed(() => props.fieldConfig.editable === false || !!props.fieldConfig.resourceFieldId);
  const showAudioUpload = computed(
    () =>
      !props.isSubTableField &&
      !props.isSubTableRender &&
      !props.isDefaultValueRender &&
      followRecordFormKeys.includes(props.formKey as FormDesignKeyEnum) &&
      (props.fieldConfig.businessKey === 'content' || props.fieldConfig.id === 'content')
  );

  function handleAudioUploadClick() {
    audioInputRef.value?.click();
  }

  function mergeTranscribedText(text: string) {
    const current = value.value?.trim();
    const result = current ? `${current}\n${text}` : text;
    value.value = result.slice(0, 3000);
    emit('change', value.value);
  }

  function clearAudioPollingTimer() {
    if (audioPollingTimer.value) {
      window.clearTimeout(audioPollingTimer.value);
      audioPollingTimer.value = undefined;
    }
  }

  function waitForAudioTranscription(taskId: string, pollCount = 0) {
    if (pollCount >= AUDIO_MAX_POLL_COUNT) {
      return Promise.resolve(undefined);
    }
    return new Promise<Awaited<ReturnType<typeof getAiAgentAudioTranscription>> | undefined>((resolve, reject) => {
      audioPollingTimer.value = window.setTimeout(() => {
        audioPollingTimer.value = undefined;
        getAiAgentAudioTranscription(taskId, audioLanguage.value)
          .then((result) => {
            const status = result?.status;
            if (status === 'SUCCESS') {
              resolve(result);
              return;
            }
            if (status === 'FAILED') {
              reject(new Error('音频转写失败'));
              return;
            }
            waitForAudioTranscription(taskId, pollCount + 1)
              .then(resolve)
              .catch(reject);
          })
          .catch(reject);
      }, AUDIO_POLL_INTERVAL_MS);
    });
  }

  async function handleTranscriptionResult(taskId: string) {
    Message.info('音频已上传，正在转写，请稍候');
    const result = await waitForAudioTranscription(taskId);
    if (!result) {
      Message.warning('音频仍在转写中，请稍后重新上传或联系管理员查询任务状态');
      return;
    }
    const text = result.text?.trim();
    if (!text) {
      Message.warning('未识别到音频内容');
      return;
    }
    mergeTranscribedText(text);
    Message.success('音频识别完成');
  }

  async function handleAudioFileChange(event: Event) {
    const target = event.target as HTMLInputElement;
    const file = target.files?.[0];
    target.value = '';
    if (!file) {
      return;
    }
    try {
      audioUploading.value = true;
      const result = await transcribeAiAgentAudio(file, audioLanguage.value);
      const transcription = result?.data;
      const text = transcription?.text?.trim();
      if (transcription?.status === 'RUNNING' && transcription.taskId) {
        await handleTranscriptionResult(transcription.taskId);
        return;
      }
      if (!text) {
        Message.warning('未识别到音频内容');
        return;
      }
      mergeTranscribedText(text);
      Message.success('音频识别完成');
    } catch (error) {
      // Request interceptors show API errors; this only restores button state.
      // eslint-disable-next-line no-console
      console.log(error);
    } finally {
      clearAudioPollingTimer();
      audioUploading.value = false;
    }
  }

  onBeforeUnmount(() => {
    clearAudioPollingTimer();
  });

  watch(
    () => props.fieldConfig.defaultValue,
    (val) => {
      if (!props.needInitDetail) {
        value.value = val || value.value;
        emit('change', value.value);
      }
    },
    {
      immediate: true,
    }
  );
</script>

<style lang="less" scoped>
  .audio-tools-row {
    display: flex;
    align-items: center;
    gap: 6px;
    margin-bottom: 6px;
  }

  .audio-language-select {
    width: 92px;
  }
</style>
