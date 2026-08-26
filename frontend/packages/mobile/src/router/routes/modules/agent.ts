import { AgentRouteEnum } from '@/enums/routeEnum';

import { DEFAULT_LAYOUT } from '../base';
import type { AppRouteRecordRaw } from '../types';

const agent: AppRouteRecordRaw = {
  path: '/agent',
  name: AgentRouteEnum.AGENT,
  redirect: '/agent/chat',
  component: DEFAULT_LAYOUT,
  meta: {
    permissions: ['AGENT:READ'],
  },
  children: [
    {
      path: 'chat',
      name: AgentRouteEnum.AGENT_CHAT,
      component: () => import('../../../views/agent/index.vue'),
      meta: {
        locale: 'menu.agent',
        permissions: ['AGENT:READ'],
        depth: 2,
      },
    },
  ],
};

export default agent;
