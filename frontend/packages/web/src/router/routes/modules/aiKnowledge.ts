import { AiKnowledgeRouteEnum } from '@/enums/routeEnum';

import { DEFAULT_LAYOUT } from '../base';
import type { AppRouteRecordRaw } from '../types';

const aiKnowledge: AppRouteRecordRaw = {
  path: '/ai-knowledge',
  name: AiKnowledgeRouteEnum.AI_KNOWLEDGE,
  redirect: '/ai-knowledge/index',
  component: DEFAULT_LAYOUT,
  meta: {
    locale: 'menu.aiKnowledge',
    permissions: ['AGENT:UPDATE'],
    icon: 'iconicon_book_open',
    hideChildrenInMenu: true,
    collapsedLocale: 'menu.aiKnowledge',
  },
  children: [
    {
      path: 'index',
      name: AiKnowledgeRouteEnum.AI_KNOWLEDGE_INDEX,
      component: () => import('../../../views/agent/knowledge.vue'),
      meta: {
        locale: 'menu.queryTerminology',
        permissions: ['AGENT:UPDATE'],
        isTopMenu: true,
      },
    },
    {
      path: 'examples',
      name: AiKnowledgeRouteEnum.AI_KNOWLEDGE_EXAMPLES,
      component: () => import('../../../views/agent/knowledge.vue'),
      meta: {
        locale: 'menu.querySqlExamples',
        permissions: ['AGENT:UPDATE'],
        isTopMenu: true,
      },
    },
  ],
};

export default aiKnowledge;
