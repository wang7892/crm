import { CustomerRouteEnum } from '@/enums/routeEnum';

import { DEFAULT_LAYOUT } from '../base';
import type { AppRouteRecordRaw } from '../types';

const customer: AppRouteRecordRaw = {
  path: '/account',
  name: CustomerRouteEnum.CUSTOMER,
  redirect: '/account/index',
  component: DEFAULT_LAYOUT,
  meta: {
    locale: 'module.customerManagement',
    permissions: ['CUSTOMER_MANAGEMENT:READ', 'CUSTOMER_MANAGEMENT_POOL:READ'],
    icon: 'iconicon_customer',
    hideChildrenInMenu: true,
    collapsedLocale: 'menu.customer',
  },
  children: [
    {
      path: 'index',
      name: CustomerRouteEnum.CUSTOMER_INDEX,
      component: () => import('@/views/customer/customer.vue'),
      meta: {
        locale: 'menu.customer',
        isTopMenu: true,
        permissions: ['CUSTOMER_MANAGEMENT:READ'],
      },
    },
    {
      path: 'openSea',
      name: CustomerRouteEnum.CUSTOMER_OPEN_SEA,
      component: () => import('@/views/customer/openSea.vue'),
      meta: {
        locale: 'module.openSea',
        isTopMenu: true,
        permissions: ['CUSTOMER_MANAGEMENT_POOL:READ'],
      },
    },
    {
      path: 'wecom-monitor',
      name: CustomerRouteEnum.CUSTOMER_WECOM,
      component: () => import('../../../views/customer/wecomIngestionMonitor.vue'),
      meta: {
        locale: 'menu.customerWecom',
        isTopMenu: true,
        permissions: ['CUSTOMER_MANAGEMENT:READ', 'CUSTOMER_MANAGEMENT_POOL:READ'],
      },
    },
  ],
};

export default customer;
