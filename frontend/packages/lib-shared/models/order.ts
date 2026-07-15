import type { ModuleField } from './common';
import type { FormDesignConfigDetailParams } from '@lib/shared/models/system/module';

export interface SaveOrderParams {
  name: string;
  customerId: string;
  contractId: string;
  amount?: number;
  owner: string;
  moduleFields?: ModuleField[];
  moduleFormConfigDTO?: FormDesignConfigDetailParams;
  number?: string;
  orderNo?: string;
  processOrderNo?: string;
  processor?: string;
  merchandiser?: string;
  status?: string;
  color?: string;
  colorCode?: string;
  composition?: string;
  materialName?: string;
  materialType?: string;
  processTechnology?: string;
  orderTime?: number;
  quantity?: number;
  unit?: string;
  unitPrice?: number;
  currency?: string;
}

export interface UpdateOrderParams extends SaveOrderParams {
  id: string;
}

export interface OrderItem {
  id: string;
  name: string;
  contractName: string;
  contractId: string;
  moduleFields: ModuleField[];
  createUser: string;
  updateUser: string;
  customerId: string;
  owner: string;
  ownerName?: string;
  number: string;
  orderNo?: string;
  processOrderNo?: string;
  processor?: string;
  merchandiser?: string;
  status?: string;
  color?: string;
  colorCode?: string;
  composition?: string;
  materialName?: string;
  materialType?: string;
  processTechnology?: string;
  orderTime?: number;
  quantity?: number;
  unit?: string;
  unitPrice?: number;
  currency?: string;
  stage: string;
  stageName: string;
  organizationId: string;
  customerName: string;
  createUserName: string;
  updateUserName: string;
  departmentId: string;
  departmentName: string;
  createTime: number;
  updateTime: number;
  amount: number;
  inCustomerPool: boolean;
  poolId: string;
  optionMap?: Record<string, any>;
  attachmentMap?: Record<string, any>;
}

export interface OrderSummaryItem {
  orderNo: string;
  processor?: string;
  owner?: string;
  ownerName?: string;
  merchandiser?: string;
  orderTime?: number;
  quantity?: number | string;
  unit?: string;
  amount?: number | string;
  currency?: string;
}

export interface ExternalOrderSyncResult {
  total: number;
  created: number;
  updated: number;
  skipped: number;
  nextMinId?: number;
  hasMore?: boolean;
  configured: boolean;
  warnings: string[];
}
