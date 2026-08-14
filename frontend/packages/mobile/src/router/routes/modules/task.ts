import { TaskRouteEnum } from '@/enums/routeEnum';

import { DEFAULT_LAYOUT } from '../base';
import type { AppRouteRecordRaw } from '../types';

const task: AppRouteRecordRaw = {
  path: '/task',
  name: TaskRouteEnum.TASK,
  redirect: '/task/index',
  component: DEFAULT_LAYOUT,
  meta: {
    permissions: ['TASK:READ'],
  },
  children: [
    {
      path: 'index',
      name: TaskRouteEnum.TASK_INDEX,
      component: () => import('@/views/task/index.vue'),
      meta: {
        locale: 'menu.task',
        permissions: ['TASK:READ'],
        depth: 2,
        isCache: true,
      },
    },
    {
      path: 'detail',
      name: TaskRouteEnum.TASK_DETAIL,
      component: () => import('@/views/task/detail.vue'),
      meta: {
        locale: 'task.detailTitle',
        permissions: ['TASK:READ'],
        depth: 3,
      },
    },
  ],
};

export default task;
