<template>
  <CrmList
    v-if="listData.length"
    v-model:data="listData"
    :virtual-scroll-height="props.virtualScrollHeight"
    :key-field="props.keyField"
    :item-height="100"
    mode="remote"
    @reach-bottom="emit('reachBottom')"
  >
    <template #item="{ item }">
      <div class="crm-follow-record-item">
        <div class="crm-follow-time-line">
          <div :class="`crm-follow-time-dot ${getFutureClass(item)}`"></div>
          <div class="crm-follow-time-line"></div>
        </div>
        <div class="mb-[24px] flex w-full flex-col gap-[16px]">
          <div class="crm-follow-record-title h-[32px]">
            <div class="flex items-center gap-[16px]">
              <slot name="titleLeft" :item="item"></slot>
              <StatusTagSelect
                v-if="item.status"
                v-model:status="item.status"
                :disabled="!props.getDisabledFun?.(item) || !!item.converted"
                @change="() => emit('change', item)"
              />
              <CrmTag v-if="item.status && item.converted"> {{ t('common.hasConvertToRecord') }} </CrmTag>
              <div class="text-[var(--text-n1)]">{{ getShowTime(item) }}</div>
              <div class="crm-follow-record-method">
                {{ (props.type === 'followRecord' ? item.followMethod : item.method) ?? '-' }}
              </div>
            </div>

            <slot name="headerAction" :item="item"></slot>
          </div>

          <div class="crm-follow-record-base-info">
            <CrmDetailCard :description="props.getDescriptionFun(item)">
              <!-- TODO 先不要了 xinxin -->
              <!-- <template #prefix>
                <div class="flex items-center gap-[8px]">
                  <CrmAvatar :is-user="item.owner === userStore.userInfo.id" :size="24" :word="item.ownerName" />
                  <n-tooltip :delay="300">
                    <template #trigger>
                      <div class="one-line-text max-w-[300px]">{{ item.ownerName }} </div>
                    </template>
                    {{ item.ownerName || '-' }}
                  </n-tooltip>
                </div>
              </template> -->
              <template v-for="ele in props.getDescriptionFun(item)" :key="ele.key" #[ele.key]="{ item: descItem }">
                <slot
                  v-if="['customerName', 'clueName'].includes(ele.key) && !props.disabledOpenDetail"
                  name="customerName"
                >
                  <CrmTableButton @click="goDetail(ele.key, item)">
                    {{ ele.value }}
                    <template #trigger> {{ ele.value }} </template>
                  </CrmTableButton>
                </slot>
                <template v-else-if="ele.key === 'updateUserName'">
                  <span>{{ descItem.value }}</span>
                  <template v-if="hasAttachment(item)">
                    <span class="attachment-links">
                      {{ getAttachmentGroupLabel(item) }}
                      <template v-for="(url, index) in getAttachmentUrls(item)" :key="url">
                        <a :href="url" target="_blank" rel="noopener noreferrer">
                          {{ getAttachmentLabel(index, item) }}
                        </a>
                        <span v-if="index < getAttachmentUrls(item).length - 1">，</span>
                      </template>
                    </span>
                  </template>
                </template>
                <slot v-else :name="ele.key" :desc-item="descItem" :item="item"></slot>
              </template>
            </CrmDetailCard>
          </div>
          <div
            class="crm-follow-record-content"
            :class="{ 'crm-follow-record-content--wecom': isWecomMonitorContent(item.content) }"
            @click="handleWecomContentClick"
            @wheel="(event) => handleWecomContentWheel(event, item)"
            v-html="renderFollowContent(item)"
          ></div>
        </div>
      </div>
    </template>
  </CrmList>
  <div v-else class="w-full p-[16px] text-center text-[var(--text-n4)]">
    {{ props.emptyText }}
  </div>
</template>

<script setup lang="ts">
  import { onBeforeUnmount } from 'vue';
  import dayjs from 'dayjs';

  import { CustomerFollowPlanStatusEnum } from '@lib/shared/enums/customerEnum';
  import { useI18n } from '@lib/shared/hooks/useI18n';
  import type { CustomerFollowPlanListItem, FollowDetailItem, WecomFollowMediaItem } from '@lib/shared/models/customer';

  import type { Description } from '@/components/pure/crm-detail-card/index.vue';
  import CrmDetailCard from '@/components/pure/crm-detail-card/index.vue';
  import CrmList from '@/components/pure/crm-list/index.vue';
  import CrmTableButton from '@/components/pure/crm-table-button/index.vue';
  import CrmTag from '@/components/pure/crm-tag/index.vue';
  import StatusTagSelect from './statusTagSelect.vue';

  import useOpenNewPage from '@/hooks/useOpenNewPage';

  import { ClueRouteEnum, CustomerRouteEnum } from '@/enums/routeEnum';

  const { t } = useI18n();
  const props = defineProps<{
    type: 'followRecord' | 'followPlan';
    keyField: string;
    getDescriptionFun: (item: FollowDetailItem) => Description[];
    getDisabledFun?: (item: FollowDetailItem) => boolean;
    virtualScrollHeight: string;
    emptyText?: string;
    disabledOpenDetail?: boolean;
  }>();

  const emit = defineEmits<{
    (e: 'reachBottom'): void;
    (e: 'change', item: FollowDetailItem): void;
  }>();

  const listData = defineModel<FollowDetailItem[]>('data', {
    default: [],
  });

  type WecomMediaCursor = 'image' | 'video' | 'emotion' | 'voice' | 'file';
  let activeVoiceAudio: HTMLAudioElement | null = null;
  let activeVoiceButton: HTMLElement | null = null;

  const wecomMessageTypeMap: Record<string, string> = {
    text: '文字',
    image: '图片',
    voice: '语音',
    video: '视频',
    file: '文件',
    link: '链接',
    location: '位置',
    emotion: '表情',
    external_redpacket: '红包',
    redpacket: '红包',
    card: '名片',
    mixed: '混合消息',
    chatrecord: '聊天记录',
    revoke: '撤回消息',
    agree: '同意会话存档',
    disagree: '不同意会话存档',
    meeting_voice_call: '音频通话',
    voiptext: '音视频通话',
    voip_doc_share: '文档共享',
    docmsg: '文档',
    markdown: 'Markdown',
    news: '图文消息',
    calendar: '日程',
    collect: '填表',
    vote: '投票',
    todo: '待办',
    meeting: '会议',
    weapp: '小程序',
    sphfeed: '视频号',
  };

  const wecomInlineEmojiMap: Record<string, string> = {
    微笑: '😊',
    撇嘴: '😒',
    色: '😍',
    发呆: '😳',
    得意: '😎',
    流泪: '😢',
    害羞: '😊',
    闭嘴: '🤐',
    睡: '😴',
    大哭: '😭',
    尴尬: '😅',
    发怒: '😡',
    调皮: '😜',
    呲牙: '😁',
    惊讶: '😲',
    难过: '😞',
    酷: '😎',
    冷汗: '😰',
    抓狂: '😫',
    吐: '🤮',
    偷笑: '🤭',
    可爱: '☺️',
    白眼: '🙄',
    傲慢: '😤',
    饥饿: '😋',
    困: '😪',
    惊恐: '😱',
    流汗: '😓',
    憨笑: '😄',
    悠闲: '🙂',
    奋斗: '💪',
    疑问: '❓',
    嘘: '🤫',
    晕: '😵',
    衰: '😞',
    敲打: '🔨',
    再见: '👋',
    擦汗: '😅',
    抠鼻: '👃',
    鼓掌: '👏',
    坏笑: '😏',
    哈欠: '🥱',
    鄙视: '😒',
    委屈: '🥺',
    快哭了: '😢',
    阴险: '😈',
    亲亲: '😘',
    吓: '😱',
    可怜: '🥺',
    强: '👍',
    弱: '👎',
    握手: '🤝',
    胜利: '✌️',
    抱拳: '🙏',
    勾引: '☝️',
    拳头: '✊',
    OK: '👌',
    爱心: '❤️',
    心碎: '💔',
    玫瑰: '🌹',
    凋谢: '🥀',
    蛋糕: '🎂',
    闪电: '⚡',
    炸弹: '💣',
    咖啡: '☕',
    啤酒: '🍺',
    饭: '🍚',
  };

  function getFutureClass(item: FollowDetailItem) {
    if (props.type === 'followPlan') {
      const isNotFuture = [CustomerFollowPlanStatusEnum.CANCELLED, CustomerFollowPlanStatusEnum.COMPLETED].includes(
        (item as CustomerFollowPlanListItem).status
      );
      return isNotFuture ? '' : 'crm-follow-dot-future';
    }

    return new Date(item.followTime).getTime() > Date.now() ? 'crm-follow-dot-future' : '';
  }

  function getShowTime(item: FollowDetailItem) {
    const time = 'estimatedTime' in item ? item.estimatedTime : item.followTime;
    return time ? dayjs(time).format('YYYY-MM-DD') : '-';
  }

  const { openNewPage } = useOpenNewPage();
  function goDetail(key: string, item: FollowDetailItem) {
    if (key === 'clueName') {
      if (item.poolId) {
        openNewPage(ClueRouteEnum.CLUE_MANAGEMENT_POOL, {
          id: item.clueId,
          name: item.clueName,
          poolId: item.poolId,
        });
      } else {
        openNewPage(ClueRouteEnum.CLUE_MANAGEMENT, {
          id: item.clueId,
          transitionType: undefined,
          name: item.clueName,
        });
      }
    } else if (item.poolId) {
      openNewPage(CustomerRouteEnum.CUSTOMER_OPEN_SEA, {
        id: item.customerId,
        poolId: item.poolId,
      });
    } else {
      openNewPage(CustomerRouteEnum.CUSTOMER_INDEX, {
        id: item.customerId,
      });
    }
  }

  function escapeHtml(input: string) {
    return input
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#39;');
  }

  function escapeAttribute(input: string) {
    return escapeHtml(input);
  }

  function linkifyEscapedText(escaped: string) {
    return escaped.replace(/(https?:\/\/[^\s<]+)/g, '<a href="$1" target="_blank" rel="noopener noreferrer">$1</a>');
  }

  function isWecomMonitorContent(content?: string) {
    const text = content ?? '';
    return (
      /^\s*\[\d{10,}\]\s+(INBOUND|OUTBOUND)\s+\S+/im.test(text) ||
      /^\s*\[\d{4}-\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\]\s+.+?\s+(文本|图片|语音|视频|文件|表情|红包|语音通话|视频通话)/im.test(
        text
      ) ||
      /^\s*\[(image|video|emotion|voice|file|redpacket|voiptext|meeting_voice_call|meeting_video_call|voice_call|video_call|voip_doc_share)\]/im.test(
        text
      )
    );
  }

  function formatBeijingTime(timestamp: number) {
    if (!Number.isFinite(timestamp)) {
      return '';
    }
    const date = new Date(timestamp + 8 * 60 * 60 * 1000);
    const pad = (num: number) => String(num).padStart(2, '0');
    return [
      `${date.getUTCFullYear()}-${pad(date.getUTCMonth() + 1)}-${pad(date.getUTCDate())}`,
      `${pad(date.getUTCHours())}:${pad(date.getUTCMinutes())}:${pad(date.getUTCSeconds())}`,
    ].join(' ');
  }

  function getWecomSenderName(direction: string, item: FollowDetailItem) {
    if (direction.toUpperCase() === 'OUTBOUND') {
      return item.ownerName || item.updateUserName || item.createUserName || '联系专员';
    }
    return item.customerName || item.contactName || item.clueName || '客户';
  }

  function formatWecomMonitorLine(line: string, item: FollowDetailItem) {
    const oldMatch = line.match(/^\s*\[(\d{10,})\]\s+(INBOUND|OUTBOUND)\s+(\S+)(.*)$/i);
    if (oldMatch) {
      const [, timestamp, direction, messageType, rest] = oldMatch;
      const senderName = getWecomSenderName(direction, item);
      const typeText = wecomMessageTypeMap[messageType.toLowerCase()] ?? messageType;
      return `${formatBeijingTime(Number(timestamp))} ${senderName} ${typeText}${rest ?? ''}`;
    }

    const newMatch = line.match(
      /^\s*\[(\d{4}-\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2})\]\s+(.+?)\s+(文本|图片|语音|视频|文件|表情|红包|语音通话|视频通话)(.*)$/i
    );
    if (!newMatch) {
      return line;
    }
    const [, timestamp, senderText, messageType, rest] = newMatch;
    const ownerName = item.ownerName || item.updateUserName || item.createUserName || '联系专员';
    const customerName = item.customerName || item.contactName || item.clueName || '客户';
    const normalizedSender = senderText
      .replace(/联系专员/g, ownerName)
      .replace(/客户/g, customerName)
      .replace(/专员/g, ownerName);
    return `[${timestamp}] ${normalizedSender} ${messageType}${rest ?? ''}`;
  }

  function replaceWecomInlineEmoji(text: string) {
    return text.replace(/\[([^[\]\r\n]{1,12})\]/g, (raw, name: string) => wecomInlineEmojiMap[name] ?? raw);
  }

  function renderWecomTextLine(line: string, item: FollowDetailItem) {
    return linkifyEscapedText(escapeHtml(replaceWecomInlineEmoji(formatWecomMonitorLine(line, item))));
  }

  function getAvailableWecomMedia(item: FollowDetailItem, mediaType: string) {
    return (item.wecomMediaList ?? []).filter((media) => media.msgMediaType?.toLowerCase() === mediaType);
  }

  function takeNextWecomMedia(
    item: FollowDetailItem,
    mediaType: string,
    cursorMap: Partial<Record<WecomMediaCursor, number>>
  ) {
    const key = mediaType as WecomMediaCursor;
    const mediaList = getAvailableWecomMedia(item, mediaType);
    const index = cursorMap[key] ?? 0;
    cursorMap[key] = index + 1;
    return mediaList[index];
  }

  function getSafeMediaPreviewUrl(media?: WecomFollowMediaItem) {
    const url = media?.previewUrl?.trim();
    if (!url) {
      return '';
    }
    if (url.startsWith('/') || /^https?:\/\//i.test(url)) {
      return url;
    }
    return '';
  }

  function formatMediaSize(size?: number) {
    if (!size) {
      return '';
    }
    if (size >= 1024 * 1024) {
      return `${(size / 1024 / 1024).toFixed(1)} MB`;
    }
    if (size >= 1024) {
      return `${(size / 1024).toFixed(1)} KB`;
    }
    return `${size} B`;
  }

  function formatMediaDuration(durationMs?: number) {
    if (!durationMs) {
      return '';
    }
    const seconds = Math.round(durationMs / 1000);
    return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`;
  }

  function getWecomSummaryValue(summary: string, key: string) {
    const escapedKey = key.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    const match = summary.trim().match(new RegExp(`(?:^|,\\s*)${escapedKey}=([\\s\\S]*?)(?=,\\s*\\w+=|$)`));
    return match?.[1]?.trim() ?? '';
  }

  function getWecomSummaryAnyValue(summary: string, keys: string[]) {
    return keys.map((key) => getWecomSummaryValue(summary, key)).find(Boolean) ?? '';
  }

  function getSummaryDurationMs(detailText: string) {
    const value = getWecomSummaryAnyValue(detailText, ['duration_ms', 'duration', 'voice_play_length', 'play_length']);
    const raw = Number(value);
    if (!Number.isFinite(raw) || raw <= 0) {
      return undefined;
    }
    return raw < 10000 ? raw * 1000 : raw;
  }

  function getVoiceDurationSeconds(media: WecomFollowMediaItem | undefined, detailText: string) {
    const durationMs = media?.durationMs ?? getSummaryDurationMs(detailText);
    if (!durationMs) {
      return 1;
    }
    return Math.max(1, Math.round(durationMs / 1000));
  }

  function getVoiceBubbleWidth(seconds: number) {
    return Math.min(240, Math.max(74, 56 + seconds * 5));
  }

  function firstNonBlank(values: Array<string | undefined | null>) {
    return values.map((value) => value?.trim()).find(Boolean) ?? '';
  }

  function getWecomFileName(media: WecomFollowMediaItem | undefined, detailText: string) {
    return firstNonBlank([
      media?.fileName,
      getWecomSummaryAnyValue(detailText, ['filename', 'file', 'name']),
      '企业微信文件',
    ]);
  }

  function getWecomFileSize(media: WecomFollowMediaItem | undefined, detailText: string) {
    const rawSize = media?.sizeBytes ?? Number(getWecomSummaryAnyValue(detailText, ['filesize', 'size']));
    return Number.isFinite(rawSize) && rawSize > 0 ? rawSize : undefined;
  }

  function getFileExtension(fileName: string) {
    const index = fileName.lastIndexOf('.');
    if (index < 0 || index >= fileName.length - 1) {
      return '';
    }
    return fileName.slice(index + 1).toLowerCase();
  }

  function getWecomFileBadgeType(fileName: string) {
    const extension = getFileExtension(fileName);
    if (['xls', 'xlsx', 'csv'].includes(extension)) {
      return 'excel';
    }
    if (['doc', 'docx'].includes(extension)) {
      return 'word';
    }
    if (extension === 'pdf') {
      return 'pdf';
    }
    if (['ppt', 'pptx'].includes(extension)) {
      return 'ppt';
    }
    return 'file';
  }

  function getWecomFileBadgeText(fileName: string) {
    const badgeType = getWecomFileBadgeType(fileName);
    const textMap: Record<string, string> = {
      excel: 'X',
      word: 'W',
      pdf: 'PDF',
      ppt: 'P',
      file: 'F',
    };
    return textMap[badgeType];
  }

  function formatFileCardSize(size?: number) {
    if (!size) {
      return '未知大小';
    }
    if (size >= 1024 * 1024) {
      return `${(size / 1024 / 1024).toFixed(1)}M`;
    }
    if (size >= 1024) {
      return `${(size / 1024).toFixed(1)}K`;
    }
    return `${size}B`;
  }

  function renderWecomFileCard(media: WecomFollowMediaItem | undefined, detailText: string, url: string) {
    const name = getWecomFileName(media, detailText);
    const size = formatFileCardSize(getWecomFileSize(media, detailText));
    const badgeType = getWecomFileBadgeType(name);
    const badgeText = getWecomFileBadgeText(name);
    const title = escapeAttribute(name);
    const disabled = !url;
    const tag = disabled ? 'div' : 'a';
    const hrefAttrs = disabled
      ? 'aria-disabled="true"'
      : `href="${escapeAttribute(url)}" target="_blank" rel="noopener noreferrer"`;
    const status = disabled ? '<div class="wecom-file-status">文件暂未落盘</div>' : '';
    return `<${tag} class="wecom-file-card${
      disabled ? ' wecom-file-card--disabled' : ''
    }" ${hrefAttrs} title="${title}"><span class="wecom-file-main"><span class="wecom-file-name">${escapeHtml(
      name
    )}</span><span class="wecom-file-size">${escapeHtml(
      size
    )}</span>${status}</span><span class="wecom-file-badge wecom-file-badge--${badgeType}">${escapeHtml(
      badgeText
    )}</span></${tag}>`;
  }

  function renderMissingWecomMedia(mediaType: string, detailText: string) {
    const typeText = wecomMessageTypeMap[mediaType] ?? mediaType;
    const detail = detailText ? ` ${detailText}` : '';
    return `<span class="wecom-media-missing">${typeText}暂未落盘，无法预览${escapeHtml(detail)}</span>`;
  }

  function formatWecomRedpacketAmount(totalAmount: string) {
    const amount = Number(totalAmount);
    if (!Number.isFinite(amount)) {
      return totalAmount;
    }
    const yuan = amount / 100;
    return `¥${yuan.toFixed(2).replace(/\.?0+$/, '')}`;
  }

  function renderWecomRedpacketLine(line: string) {
    const match = line.match(/^\s*\[redpacket\](.*)$/i);
    if (!match) {
      return null;
    }
    const summary = match[1] ?? '';
    const wish = getWecomSummaryValue(summary, 'wish') || '恭喜发财，大吉大利';
    const totalCnt = getWecomSummaryValue(summary, 'totalcnt');
    const totalAmount = getWecomSummaryValue(summary, 'totalamount');
    const meta = [
      totalCnt ? `${totalCnt} 个红包` : '',
      totalAmount ? `金额 ${formatWecomRedpacketAmount(totalAmount)}` : '',
    ].filter(Boolean);
    const metaHtml = meta.length ? `<div class="wecom-redpacket-meta">${escapeHtml(meta.join(' / '))}</div>` : '';
    return `<div class="wecom-redpacket-card"><div class="wecom-redpacket-icon">¥</div><div class="wecom-redpacket-main"><div class="wecom-redpacket-wish">${escapeHtml(
      wish
    )}</div><div class="wecom-redpacket-label">微信红包</div>${metaHtml}</div></div>`;
  }

  function formatVoipDurationSeconds(seconds: number) {
    if (!Number.isFinite(seconds)) {
      return '';
    }
    const safeSeconds = Math.max(0, Math.round(seconds));
    const hours = Math.floor(safeSeconds / 3600);
    const minutes = Math.floor((safeSeconds % 3600) / 60);
    const restSeconds = safeSeconds % 60;
    if (hours > 0) {
      return `${String(hours).padStart(2, '0')}:${String(minutes).padStart(2, '0')}:${String(restSeconds).padStart(
        2,
        '0'
      )}`;
    }
    return `${String(minutes).padStart(2, '0')}:${String(restSeconds).padStart(2, '0')}`;
  }

  function getWecomVoipDuration(summary: string) {
    const candidates = [
      getWecomSummaryAnyValue(summary, ['callduration', 'call_duration', 'callDuration']),
      getWecomSummaryValue(summary, 'duration'),
      getWecomSummaryValue(summary, 'play_length'),
      getWecomSummaryValue(summary, 'content'),
      summary,
    ].filter(Boolean);

    const matchedDuration = candidates.find((value) => {
      const clockMatch = value.match(/(\d{1,2}:\d{2}(?::\d{2})?)/);
      if (clockMatch) {
        return true;
      }
      const numberMatch = value.match(/(?:通话时长|时长|duration|callduration)?\s*[=:：]?\s*(\d{1,6})\s*(?:秒|s)?/i);
      return !!numberMatch;
    });
    if (matchedDuration) {
      const clockMatch = matchedDuration.match(/(\d{1,2}:\d{2}(?::\d{2})?)/);
      if (clockMatch) {
        return clockMatch[1];
      }
      const numberMatch = matchedDuration.match(
        /(?:通话时长|时长|duration|callduration)?\s*[=:：]?\s*(\d{1,6})\s*(?:秒|s)?/i
      );
      const raw = Number(numberMatch?.[1]);
      return formatVoipDurationSeconds(raw > 3600 ? raw / 1000 : raw);
    }
    return '--:--';
  }

  function getWecomVoipInviteType(summary: string) {
    return Number(getWecomSummaryAnyValue(summary, ['invitetype', 'invite_type', 'inviteType']));
  }

  function getWecomVoipTitle(summary: string) {
    const inviteType = getWecomVoipInviteType(summary);
    if ([1, 3].includes(inviteType)) {
      return '视频通话';
    }
    if ([2, 4].includes(inviteType)) {
      return '语音通话';
    }

    const content = `${summary} ${getWecomSummaryValue(summary, 'content')}`.toLowerCase();
    if (content.includes('视频') || content.includes('video')) {
      return '视频通话';
    }
    if (
      content.includes('语音') ||
      content.includes('音频') ||
      content.includes('voice') ||
      content.includes('audio')
    ) {
      return '语音通话';
    }
    return '音视频通话';
  }

  function renderWecomVoipTextLine(line: string) {
    const match = line.match(
      /^\s*\[(voiptext|meeting_voice_call|meeting_video_call|voice_call|video_call|voip_doc_share)\](.*)$/i
    );
    if (!match) {
      return null;
    }
    const msgType = match[1].toLowerCase();
    const summary = `${match[2] ?? ''} msg_type=${msgType}`;
    const title = getWecomVoipTitle(summary);
    const duration = getWecomVoipDuration(summary);
    const inviteType = getWecomVoipInviteType(summary);
    const videoTypes = ['meeting_video_call', 'video_call', 'voip_doc_share'];
    const iconClass =
      videoTypes.includes(msgType) || [1, 3].includes(inviteType) ? 'wecom-voip-icon--video' : 'wecom-voip-icon--voice';
    return `<div class="wecom-voip-card"><span class="wecom-voip-icon ${iconClass}"></span><span class="wecom-voip-duration">通话时长${escapeHtml(
      duration
    )}</span></div>`;
  }

  function renderWecomMediaLine(
    line: string,
    item: FollowDetailItem,
    cursorMap: Partial<Record<WecomMediaCursor, number>>
  ) {
    const match = line.match(/^\s*\[(image|video|emotion|voice|file)\](.*)$/i);
    if (!match) {
      return null;
    }
    const [, rawType, detailText] = match;
    const mediaType = rawType.toLowerCase();
    const media = takeNextWecomMedia(item, mediaType, cursorMap);
    const url = getSafeMediaPreviewUrl(media);
    if (mediaType === 'file') {
      return renderWecomFileCard(media, detailText, url);
    }
    if (!url) {
      return renderMissingWecomMedia(mediaType, detailText.trim());
    }

    const escapedUrl = escapeAttribute(url);
    const fileName = media?.fileName ? ` title="${escapeAttribute(media.fileName)}"` : '';
    const duration = formatMediaDuration(media?.durationMs);
    const size = formatMediaSize(media?.sizeBytes);
    const meta = [duration, size].filter(Boolean).join(' · ');
    const metaHtml = meta ? `<div class="wecom-media-meta">${escapeHtml(meta)}</div>` : '';

    if (mediaType === 'image' || mediaType === 'emotion') {
      const imageClass = mediaType === 'emotion' ? 'wecom-media-image wecom-emotion-image' : 'wecom-media-image';
      const alt = mediaType === 'emotion' ? '企业微信表情' : '企业微信图片';
      return `<a class="wecom-media-link" href="${escapedUrl}" target="_blank" rel="noopener noreferrer"><img class="${imageClass}" src="${escapedUrl}" alt="${alt}"${fileName} loading="lazy" /></a>`;
    }

    if (mediaType === 'voice') {
      const seconds = getVoiceDurationSeconds(media, detailText);
      const width = getVoiceBubbleWidth(seconds);
      const label = `${seconds}"`;
      return `<div class="wecom-media-voice-wrap"><button type="button" class="wecom-voice-bubble" style="width:${width}px" data-audio-src="${escapedUrl}" aria-label="播放语音 ${escapeAttribute(
        label
      )}"${fileName}><span class="wecom-voice-duration">${escapeHtml(
        label
      )}</span><span class="wecom-voice-wave"></span></button>${metaHtml}<a class="wecom-voice-download" href="${escapedUrl}" target="_blank" rel="noopener noreferrer">下载语音</a></div>`;
    }

    return `<div class="wecom-media-video-wrap"><video class="wecom-media-video" src="${escapedUrl}" controls preload="metadata"${fileName}></video>${metaHtml}</div>`;
  }

  function isAttachmentUrlLine(line: string) {
    return /^\s*附件[:：]\s*https?:\/\/\S+/i.test(line);
  }

  function renderFollowContent(item: FollowDetailItem) {
    const isWecom = isWecomMonitorContent(item.content);
    const cursorMap: Partial<Record<WecomMediaCursor, number>> = {};
    return (item.content ?? '')
      .split(/\r?\n/)
      .filter((line) => !isAttachmentUrlLine(line))
      .map((line) => {
        if (isWecom) {
          const mediaHtml = renderWecomMediaLine(line, item, cursorMap);
          if (mediaHtml) {
            return mediaHtml;
          }
          const redpacketHtml = renderWecomRedpacketLine(line);
          if (redpacketHtml) {
            return redpacketHtml;
          }
          const voipTextHtml = renderWecomVoipTextLine(line);
          if (voipTextHtml) {
            return voipTextHtml;
          }
          return renderWecomTextLine(line, item);
        }
        return linkifyEscapedText(escapeHtml(line));
      })
      .join('<br />')
      .trim();
  }

  function clearActiveVoice(reset = true) {
    if (activeVoiceAudio) {
      activeVoiceAudio.pause();
      if (reset) {
        activeVoiceAudio.currentTime = 0;
      }
      activeVoiceAudio.src = '';
    }
    activeVoiceButton?.classList.remove('is-playing');
    activeVoiceAudio = null;
    activeVoiceButton = null;
  }

  function playVoiceAudio(audio: HTMLAudioElement, button: HTMLElement) {
    button.classList.remove('is-error');
    button.classList.add('is-playing');
    audio.play().catch(() => {
      button.classList.remove('is-playing');
      button.classList.add('is-error');
    });
  }

  function handleWecomContentClick(event: MouseEvent) {
    const target = event.target as HTMLElement | null;
    const button = target?.closest('.wecom-voice-bubble') as HTMLElement | null;
    if (!button) {
      return;
    }
    const { audioSrc } = button.dataset;
    if (!audioSrc) {
      return;
    }
    event.preventDefault();
    event.stopPropagation();

    if (activeVoiceButton === button && activeVoiceAudio) {
      if (activeVoiceAudio.paused) {
        playVoiceAudio(activeVoiceAudio, button);
      } else {
        activeVoiceAudio.pause();
        button.classList.remove('is-playing');
      }
      return;
    }

    clearActiveVoice();
    const audio = new Audio(audioSrc);
    activeVoiceAudio = audio;
    activeVoiceButton = button;
    audio.addEventListener('ended', () => {
      if (activeVoiceAudio === audio) {
        clearActiveVoice(false);
      }
    });
    audio.addEventListener('error', () => {
      if (activeVoiceAudio === audio) {
        button.classList.remove('is-playing');
        button.classList.add('is-error');
      }
    });
    playVoiceAudio(audio, button);
  }

  onBeforeUnmount(() => {
    clearActiveVoice();
  });

  function normalizeWheelDelta(event: WheelEvent, element: HTMLElement) {
    if (event.deltaMode === WheelEvent.DOM_DELTA_LINE) {
      return event.deltaY * 16;
    }
    if (event.deltaMode === WheelEvent.DOM_DELTA_PAGE) {
      return event.deltaY * element.clientHeight;
    }
    return event.deltaY;
  }

  function handleWecomContentWheel(event: WheelEvent, item: FollowDetailItem) {
    if (!isWecomMonitorContent(item.content)) {
      return;
    }
    const element = event.currentTarget as HTMLElement | null;
    if (!element) {
      return;
    }
    const maxScrollTop = element.scrollHeight - element.clientHeight;
    if (maxScrollTop <= 1) {
      return;
    }

    const deltaY = normalizeWheelDelta(event, element);
    const canScrollUp = deltaY < 0 && element.scrollTop > 0;
    const canScrollDown = deltaY > 0 && element.scrollTop < maxScrollTop - 1;
    if (!canScrollUp && !canScrollDown) {
      return;
    }

    event.preventDefault();
    event.stopPropagation();
    element.scrollTop = Math.min(maxScrollTop, Math.max(0, element.scrollTop + deltaY));
  }

  function getAttachmentUrls(item: FollowDetailItem) {
    const urls = (item.attachmentUrls ?? []).map((url) => (url ?? '').trim()).filter((url) => !!url);
    return Array.from(new Set(urls));
  }

  function hasAttachment(item: FollowDetailItem) {
    return getAttachmentUrls(item).length > 0;
  }

  function getAttachmentGroupLabel(item: FollowDetailItem) {
    return isWecomMonitorContent(item.content) ? '企微媒体：' : '附件：';
  }

  function getAttachmentLabel(index: number, item: FollowDetailItem) {
    return `${isWecomMonitorContent(item.content) ? '媒体' : '附件'}${index + 1}`;
  }
</script>

<style scoped lang="less">
  .crm-follow-record-item {
    @apply flex gap-4;
    .crm-follow-time-line {
      padding-top: 12px;
      width: 8px;

      @apply flex flex-col items-center justify-center gap-2;
      .crm-follow-time-dot {
        width: 8px;
        height: 8px;
        border: 2px solid var(--text-n7);
        border-radius: 50%;
        flex-shrink: 0;
        &.crm-follow-dot-future {
          border-color: var(--primary-8);
        }
      }
      .crm-follow-time-line {
        width: 2px;
        background: var(--text-n8);
        @apply h-full;
      }
    }
    .crm-follow-record-title {
      @apply flex items-center justify-between gap-4;
      .crm-follow-record-method {
        color: var(--text-n1);
        @apply font-medium;
      }
    }
    .crm-follow-record-content {
      padding: 12px;
      border-radius: var(--border-radius-small);
      background: var(--text-n9);
      &.crm-follow-record-content--wecom {
        overflow-y: auto;
        padding-right: 16px;
        max-height: 325px;
        overscroll-behavior: contain;
        scrollbar-gutter: stable;
      }
      &.crm-follow-record-content--wecom::-webkit-scrollbar {
        width: 8px;
      }
      &.crm-follow-record-content--wecom::-webkit-scrollbar-thumb {
        border-radius: 8px;
        background-color: var(--text-n6);
      }
      &.crm-follow-record-content--wecom::-webkit-scrollbar-track {
        background-color: transparent;
      }
      :deep(.wecom-media-link) {
        display: inline-block;
        margin: 4px 0;
      }
      :deep(.wecom-media-image) {
        display: block;
        max-width: min(260px, 100%);
        max-height: 150px;
        border-radius: 6px;
        object-fit: contain;
        background: var(--text-n10);
      }
      :deep(.wecom-emotion-image) {
        max-width: 120px;
        max-height: 120px;
        background: transparent;
      }
      :deep(.wecom-media-video-wrap) {
        display: inline-block;
        margin: 4px 0;
        max-width: min(360px, 100%);
      }
      :deep(.wecom-media-video) {
        display: block;
        width: 100%;
        max-height: 220px;
        border-radius: 6px;
        background: #000000;
      }
      :deep(.wecom-media-meta) {
        margin-top: 4px;
        font-size: 12px;
        color: var(--text-n4);
      }
      :deep(.wecom-file-card) {
        display: inline-flex;
        justify-content: space-between;
        align-items: center;
        margin: 5px 0;
        padding: 12px 14px;
        width: min(320px, 100%);
        min-height: 72px;
        border: 1px solid #e5e7eb;
        border-radius: 6px;
        text-decoration: none;
        color: #111827;
        background: #ffffff;
        box-shadow: 0 1px 2px rgb(15 23 42 / 6%);
        gap: 12px;
        vertical-align: middle;
      }
      :deep(.wecom-file-card:not(.wecom-file-card--disabled):hover) {
        border-color: #cbd5e1;
        box-shadow: 0 3px 8px rgb(15 23 42 / 10%);
      }
      :deep(.wecom-file-card--disabled) {
        cursor: default;
        opacity: 0.72;
      }
      :deep(.wecom-file-main) {
        display: flex;
        min-width: 0;
        flex: 1;
        flex-direction: column;
        gap: 5px;
      }
      :deep(.wecom-file-name) {
        overflow: hidden;
        font-size: 14px;
        text-overflow: ellipsis;
        white-space: nowrap;
        color: #111827;
        line-height: 1.25;
      }
      :deep(.wecom-file-size),
      :deep(.wecom-file-status) {
        font-size: 13px;
        color: #8b949e;
        line-height: 1.2;
      }
      :deep(.wecom-file-status) {
        color: #d97706;
      }
      :deep(.wecom-file-badge) {
        display: inline-flex;
        justify-content: center;
        align-items: center;
        width: 48px;
        height: 48px;
        font-size: 26px;
        font-weight: 700;
        border-radius: 8px;
        color: #ffffff;
        flex-shrink: 0;
        line-height: 1;
      }
      :deep(.wecom-file-badge--pdf) {
        font-size: 15px;
        background: #ef4444;
      }
      :deep(.wecom-file-badge--word) {
        background: #2f67c6;
      }
      :deep(.wecom-file-badge--excel) {
        background: #16834a;
      }
      :deep(.wecom-file-badge--ppt) {
        background: #d97706;
      }
      :deep(.wecom-file-badge--file) {
        font-size: 22px;
        background: #64748b;
      }
      :deep(.wecom-media-voice-wrap) {
        display: inline-flex;
        flex-direction: column;
        align-items: flex-start;
        margin: 4px 0;
        vertical-align: middle;
      }
      :deep(.wecom-voice-bubble) {
        display: inline-flex;
        justify-content: space-between;
        align-items: center;
        padding: 0 12px 0 14px;
        min-width: 74px;
        max-width: 100%;
        height: 36px;
        border: 0;
        border-radius: 6px;
        color: #111827;
        background: #d9efff;
        line-height: 1;
        cursor: pointer;
      }
      :deep(.wecom-voice-bubble:hover) {
        background: #cae7ff;
      }
      :deep(.wecom-voice-bubble.is-playing) {
        background: #b9ddff;
      }
      :deep(.wecom-voice-bubble.is-error) {
        color: #d03050;
        background: #ffe8e8;
      }
      :deep(.wecom-voice-duration) {
        font-size: 14px;
        white-space: nowrap;
      }
      :deep(.wecom-voice-wave) {
        display: inline-block;
        margin-left: 12px;
        width: 18px;
        height: 18px;
        background-repeat: no-repeat;
        background-size: 18px 18px;
        background-image: url("data:image/svg+xml,%3Csvg%20xmlns='http://www.w3.org/2000/svg'%20width='18'%20height='18'%20viewBox='0%200%2018%2018'%20fill='none'%3E%3Cpath%20d='M8.1%206.2c.9.8%201.4%201.7%201.4%202.8s-.5%202-1.4%202.8'%20stroke='%23111827'%20stroke-width='1.6'%20stroke-linecap='round'/%3E%3Cpath%20d='M11%204.2c1.5%201.3%202.3%202.9%202.3%204.8s-.8%203.5-2.3%204.8'%20stroke='%23111827'%20stroke-width='1.6'%20stroke-linecap='round'/%3E%3Ccircle%20cx='5'%20cy='9'%20r='1.4'%20fill='%23111827'/%3E%3C/svg%3E");
      }
      :deep(.wecom-voice-bubble.is-playing .wecom-voice-wave) {
        animation: wecomVoicePulse 0.9s ease-in-out infinite;
      }
      :deep(.wecom-voice-download) {
        display: inline-block;
        margin-top: 3px;
        font-size: 12px;
        text-decoration: none;
        color: #2f80ed;
      }
      :deep(.wecom-voice-download:hover) {
        text-decoration: underline;
      }
      :deep(.wecom-media-missing) {
        display: inline-block;
        margin: 4px 0;
        color: var(--text-n4);
      }
      :deep(.wecom-redpacket-card) {
        display: inline-flex;
        align-items: center;
        margin: 4px 0;
        padding: 10px 12px;
        min-width: 220px;
        max-width: min(320px, 100%);
        border-radius: 6px;
        color: #ffffff;
        background: #f6a85f;
        gap: 10px;
      }
      :deep(.wecom-redpacket-icon) {
        display: flex;
        justify-content: center;
        align-items: center;
        width: 36px;
        height: 42px;
        font-size: 18px;
        font-weight: 600;
        border-radius: 4px;
        color: #f7c16f;
        background: #d84b3f;
        flex-shrink: 0;
      }
      :deep(.wecom-redpacket-main) {
        min-width: 0;
      }
      :deep(.wecom-redpacket-wish) {
        font-size: 14px;
        font-weight: 500;
        color: #ffffff;
        line-height: 1.4;
        word-break: break-word;
      }
      :deep(.wecom-redpacket-label),
      :deep(.wecom-redpacket-meta) {
        margin-top: 5px;
        font-size: 12px;
        color: rgb(255 255 255 / 82%);
        line-height: 1.3;
      }
      :deep(.wecom-voip-card) {
        position: relative;
        display: inline-flex;
        align-items: center;
        margin: 4px 0;
        padding: 7px 13px 7px 12px;
        width: fit-content;
        max-width: min(300px, 100%);
        border-radius: 8px;
        color: #1f2937;
        background: #ffffff;
        box-shadow: 0 1px 0 rgb(15 23 42 / 4%);
        gap: 8px;
        vertical-align: middle;
      }
      :deep(.wecom-voip-card::before) {
        position: absolute;
        top: 13px;
        left: -6px;
        width: 0;
        height: 0;
        content: '';
        border-top: 6px solid transparent;
        border-right: 7px solid #ffffff;
        border-bottom: 6px solid transparent;
      }
      :deep(.wecom-voip-icon) {
        display: inline-block;
        flex-shrink: 0;
        width: 24px;
        height: 24px;
        background-position: center;
        background-repeat: no-repeat;
        background-size: 22px 22px;
      }
      :deep(.wecom-voip-icon--video) {
        background-image: url("data:image/svg+xml,%3Csvg%20xmlns='http://www.w3.org/2000/svg'%20width='24'%20height='24'%20viewBox='0%200%2024%2024'%20fill='none'%20stroke='%232f80ed'%20stroke-width='2.4'%20stroke-linecap='round'%20stroke-linejoin='round'%3E%3Cpath%20d='m22%208-6%204%206%204V8Z'/%3E%3Crect%20x='2'%20y='6'%20width='14'%20height='12'%20rx='2'%20ry='2'/%3E%3C/svg%3E");
      }
      :deep(.wecom-voip-icon--voice) {
        background-image: url("data:image/svg+xml,%3Csvg%20xmlns='http://www.w3.org/2000/svg'%20width='24'%20height='24'%20viewBox='0%200%2024%2024'%20fill='none'%20stroke='%232f80ed'%20stroke-width='2.5'%20stroke-linecap='round'%20stroke-linejoin='round'%3E%3Cpath%20d='M22%2016.92v3a2%202%200%200%201-2.18%202A19.86%2019.86%200%200%201%203.1%205.18%202%202%200%200%201%205.11%203h3a2%202%200%200%201%202%201.72c.13.96.35%201.9.66%202.8a2%202%200%200%201-.45%202.11L9.05%2010.9a16%2016%200%200%200%204.05%204.05l1.27-1.27a2%202%200%200%201%202.11-.45c.9.31%201.84.53%202.8.66A2%202%200%200%201%2022%2016.92z'/%3E%3C/svg%3E");
      }
      :deep(.wecom-voip-duration) {
        font-size: 18px;
        font-weight: 400;
        white-space: nowrap;
        color: #1f2937;
        line-height: 1.2;
      }
    }
    .attachment-links {
      margin-left: 8px;
      white-space: normal;
      color: var(--text-n2);
      word-break: break-word;
      a {
        font-weight: 500;
        text-decoration: none;
        color: #d03050;
        &:hover {
          text-decoration: underline;
        }
      }
    }
  }

  @keyframes wecomVoicePulse {
    0%,
    100% {
      opacity: 0.45;
      transform: scale(0.96);
    }
    50% {
      opacity: 1;
      transform: scale(1);
    }
  }
</style>
