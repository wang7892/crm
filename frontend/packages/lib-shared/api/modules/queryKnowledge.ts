export type QueryKnowledgeKind = 'TERM' | 'SQL_EXAMPLE';

export interface QueryKnowledgeItem {
  id?: string;
  kind: QueryKnowledgeKind;
  name: string;
  content: string;
  synonyms: string[];
  allDatasources: boolean;
  datasourceIds: string[];
  enabled: boolean;
  revision: number;
  updateTime?: number;
  embeddingStatus?: string;
}

export interface QueryKnowledgeMatches {
  terms: QueryKnowledgeItem[];
  examples: QueryKnowledgeItem[];
  truncated: boolean;
}
