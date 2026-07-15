import { SpecialColumnEnum } from '@lib/shared/enums/tableEnum';

import type { CrmDataTableColumn } from '@/components/pure/crm-table/type';
import type { FormCreateField } from '@/components/business/crm-form-create/types';

export const CUSTOMER_TABLE_COLUMN_ORDER = [
  'name',
  'fullName',
  'owner',
  'phone',
  'email',
  'region',
  'address',
  'remark',
  'follower',
  'followTime',
  'customerSource',
  'creditLimit',
  'customsCode',
  'customerAvailable',
  'customerType',
  'wecomExternalId',
  'roomid',
  'departmentId',
  'collectionTime',
  'recyclePoolName',
  'reasonId',
  'createUser',
  'createTime',
  'updateUser',
  'updateTime',
];

const CUSTOMER_FIELD_ORDER_KEY_MAP: Record<string, string> = {
  customerName: 'name',
  customerOwner: 'owner',
  customerWecomExternalId: 'wecomExternalId',
  customerRoomid: 'roomid',
  customerEmail: 'email',
  customerFullName: 'fullName',
  customerCreditLimit: 'creditLimit',
  customerCustomsCode: 'customsCode',
  customerRegion: 'region',
  customerPhone: 'phone',
  customerAddress: 'address',
  customerRemark: 'remark',
  customerAvailable: 'customerAvailable',
  customerSource: 'customerSource',
  customerType: 'customerType',
};

type ColumnWithOrderKey = CrmDataTableColumn & {
  orderKey?: string;
};

export function getCustomerFieldOrderKey(field: Pick<FormCreateField, 'businessKey' | 'id' | 'internalKey'>) {
  const internalOrderKey = field.internalKey ? CUSTOMER_FIELD_ORDER_KEY_MAP[field.internalKey] : undefined;
  const idOrderKey = field.id ? CUSTOMER_FIELD_ORDER_KEY_MAP[field.id] : undefined;
  return field.businessKey || internalOrderKey || idOrderKey || field.id;
}

function getCustomerColumnOrderKey(column: CrmDataTableColumn) {
  const col = column as ColumnWithOrderKey;
  if (col.orderKey) return col.orderKey;
  if (typeof column.key !== 'string') return column.key;
  return CUSTOMER_FIELD_ORDER_KEY_MAP[column.key] || column.key;
}

function isRemovedCustomerColumn(column: CrmDataTableColumn) {
  return getCustomerColumnOrderKey(column) === 'reservedDays' || column.key === 'reservedDays';
}

export function sortCustomerTableBodyColumns(columns: CrmDataTableColumn[]) {
  const orderMap = new Map(CUSTOMER_TABLE_COLUMN_ORDER.map((key, index) => [key, index]));

  return columns
    .filter(
      (column) =>
        !isRemovedCustomerColumn(column) &&
        column.key !== SpecialColumnEnum.DRAG &&
        column.key !== SpecialColumnEnum.ORDER &&
        column.key !== SpecialColumnEnum.OPERATION &&
        column.type !== SpecialColumnEnum.SELECTION
    )
    .map((column, index) => {
      const orderKey = getCustomerColumnOrderKey(column);
      const orderIndex = typeof orderKey === 'string' ? orderMap.get(orderKey) : undefined;
      return {
        column,
        index,
        orderIndex: orderIndex ?? Number.MAX_SAFE_INTEGER,
      };
    })
    .sort((a, b) => a.orderIndex - b.orderIndex || a.index - b.index)
    .map((item) => item.column);
}

export function sortCustomerTableColumns(columns: CrmDataTableColumn[]) {
  return [
    ...columns.filter((column) => column.key === SpecialColumnEnum.DRAG),
    ...columns.filter((column) => column.type === SpecialColumnEnum.SELECTION),
    ...columns.filter((column) => column.key === SpecialColumnEnum.ORDER),
    ...sortCustomerTableBodyColumns(columns),
    ...columns.filter((column) => column.key === SpecialColumnEnum.OPERATION),
  ];
}
