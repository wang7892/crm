import type { CordysAxios } from '@lib/shared/api/http/Axios';
import {
  WecomIngestionDeleteSessionUrl,
  WecomIngestionMessagePageUrl,
  WecomIngestionSessionPageUrl,
  WecomIngestionSyncFollowUrl,
} from '@lib/shared/api/requrls/wecomIngestion';
import type { CommonList } from '@lib/shared/models/common';

export interface WecomSessionPageParams {
  current: number;
  pageSize: number;
}

export interface WecomSessionItem {
  sessionKey: string;
  chatType?: string;
  roomid?: string;
  chatDate?: string;
  lastSendTime: number;
  messageCount?: number;
  status?: string;
  followRecordId?: string;
  lastPreview?: string;
  wecomCustomerExternalUserid?: string;
  wecomStaffUserid?: string;
  matchedCustomerName?: string;
  matchedStaffName?: string;
  matchRuleSummary?: string;
}

export interface WecomMessagePageParams {
  current: number;
  pageSize: number;
  sessionKey: string;
}

export interface WecomMessageItem {
  id: string;
  messageDirection?: string;
  msgType?: string;
  chatType?: string;
  roomid?: string;
  contentText?: string;
  sendTime?: number;
  wecomCustomerExternalUserid?: string;
  wecomStaffUserid?: string;
  matchedCustomerName?: string;
  matchedStaffName?: string;
  matchRuleSummary?: string;
  synced?: boolean;
  followRecordId?: string;
}

export interface WecomSyncFollowParams {
  eventIds: string[];
  customerId?: string;
  ownerUserId?: string;
}

export interface WecomDeleteSessionParams {
  sessionKey: string;
}

export default function useWecomIngestionApi(CDR: CordysAxios) {
  function getWecomSessionPage(data: WecomSessionPageParams) {
    return CDR.post<CommonList<WecomSessionItem>>({ url: WecomIngestionSessionPageUrl, data });
  }

  function getWecomMessagePage(data: WecomMessagePageParams) {
    return CDR.post<CommonList<WecomMessageItem>>({ url: WecomIngestionMessagePageUrl, data });
  }

  function syncWecomToFollow(data: WecomSyncFollowParams) {
    return CDR.post<{ id: string }>({ url: WecomIngestionSyncFollowUrl, data });
  }

  function deleteWecomSession(data: WecomDeleteSessionParams) {
    return CDR.post<void>({ url: WecomIngestionDeleteSessionUrl, data });
  }

  return {
    getWecomSessionPage,
    getWecomMessagePage,
    syncWecomToFollow,
    deleteWecomSession,
  };
}
