import type { AiAgentChatResult } from '../api/modules/aiAgent';
import hljs from 'highlight.js/lib/core';
import sqlLanguage from 'highlight.js/lib/languages/sql';
import { format } from 'sql-formatter';

hljs.registerLanguage('sql', sqlLanguage);

export default function getAiAgentSqlQueries(response?: Partial<AiAgentChatResult>): string[] {
  if (!Array.isArray(response?.sqlQueries)) return [];
  return [...new Set(response.sqlQueries.filter((sql) => typeof sql === 'string' && sql.trim()))];
}

export function formatAiAgentSql(sql: string, datasourceType?: string): string {
  try {
    const dialects = {
      MYSQL: 'mysql',
      POSTGRESQL: 'postgresql',
      ORACLE: 'plsql',
      SQLSERVER: 'transactsql',
      FILE: 'sql',
    } as const;
    const language = dialects[datasourceType as keyof typeof dialects] || 'mysql';
    return format(sql, { language, keywordCase: 'upper', tabWidth: 2 });
  } catch {
    return sql;
  }
}

export function highlightAiAgentSql(sql: string): string {
  // highlight.js escapes source text before generating token spans.
  return hljs.highlight(sql, { language: 'sql', ignoreIllegals: true }).value;
}
