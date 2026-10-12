CREATE TABLE IF NOT EXISTS ai_agent_query_knowledge (
    id VARCHAR(64) NOT NULL,
    organization_id VARCHAR(64) NOT NULL,
    kind VARCHAR(16) NOT NULL,
    name VARCHAR(255) NOT NULL,
    content MEDIUMTEXT NOT NULL,
    synonyms_json TEXT NOT NULL,
    all_datasources TINYINT NOT NULL DEFAULT 0,
    datasource_ids_json TEXT NOT NULL,
    enabled TINYINT NOT NULL DEFAULT 1,
    revision BIGINT NOT NULL,
    embedding_json MEDIUMTEXT DEFAULT NULL,
    embedding_key VARCHAR(64) DEFAULT NULL,
    create_user VARCHAR(64) NOT NULL,
    create_time BIGINT NOT NULL,
    update_time BIGINT NOT NULL,
    PRIMARY KEY (id),
    KEY idx_agent_query_knowledge_org (organization_id,kind,update_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS ai_agent_query_knowledge_lock (
    organization_id VARCHAR(64) NOT NULL PRIMARY KEY
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
