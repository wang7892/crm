import { CustomerRouteEnum } from '@/enums/routeEnum';

import { DEFAULT_LAYOUT } from '../base';
import type { AppRouteRecordRaw } from '../types';

const customerFollow: AppRouteRecordRaw = {
  path: '/follow-record',
  name: CustomerRouteEnum.CUSTOMER_FOLLOW_RECORD,
  redirect: '/follow-record/index',
  component: DEFAULT_LAYOUT,
  meta: {
    locale: 'module.customer.followRecord',
    permissions: ['CUSTOMER_FOLLOW_RECORD:READ'],
    icon: 'iconicon_data_record',
    hideChildrenInMenu: true,
    collapsedLocale: 'module.customer.followRecord',
  },
  children: [
    {
      path: 'index',
      name: CustomerRouteEnum.CUSTOMER_FOLLOW_RECORD_INDEX,
      component: () => import('@/views/customer/followRecord.vue'),
      meta: {
        locale: 'module.customer.followRecord',
        permissions: ['CUSTOMER_FOLLOW_RECORD:READ'],
      },
    },
    {
      path: 'customer',
      name: CustomerRouteEnum.CUSTOMER_FOLLOW_RECORD_CUSTOMER,
      component: () => import('@/views/customer/followRecord.vue'),
      meta: {
        locale: 'customerFollow.followedCustomer',
        permissions: ['CUSTOMER_FOLLOW_RECORD:READ'],
        hideInMenu: true,
      },
    },
  ],
};

export default customerFollow;
