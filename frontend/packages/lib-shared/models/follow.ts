import type { TableQueryParams } from './common';

export interface FollowSpecialistPageParams extends TableQueryParams {
  startTime?: number;
  endTime?: number;
  monitorSource?: 'WECOM' | 'MAIL';
}

export interface FollowSpecialistCustomerPageParams extends FollowSpecialistPageParams {
  owner: string;
  customerSource?: string;
}

export interface FollowSpecialistItem {
  owner: string;
  ownerName: string;
  phone?: string;
  email?: string;
  departmentId?: string;
  departmentName?: string;
  customerCount: number;
  recordCount: number;
  latestFollowTime?: number;
}

export interface FollowSpecialistCustomerItem {
  customerId: string;
  customerName: string;
  recordCount: number;
  latestFollowTime?: number;
  latestContent?: string;
  contactId?: string;
  contactName?: string;
  contactPhone?: string;
}
