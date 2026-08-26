import { AiKnowledgeRouteEnum } from '@/enums/routeEnum';

import { DEFAULT_LAYOUT } from '../base';
import type { AppRouteRecordRaw } from '../types';

const aiKnowledge: AppRouteRecordRaw = {
  path: '/ai-knowledge',
  name: AiKnowledgeRouteEnum.AI_KNOWLEDGE,
  redirect: '/ai-knowledge/index',
  component: DEFAULT_LAYOUT,
  meta: {
    permissions: ['AGENT:UPDATE'],
  },
  children: [
    {
      path: 'index',
      name: AiKnowledgeRouteEnum.AI_KNOWLEDGE_INDEX,
      component: () => import('../../../views/aiKnowledge/index.vue'),
      meta: {
        locale: 'menu.aiKnowledge',
        permissions: ['AGENT:UPDATE'],
        depth: 2,
      },
    },
  ],
};

export default aiKnowledge;
