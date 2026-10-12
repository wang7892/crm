CREATE TABLE IF NOT EXISTS ai_agent_knowledge_settings (
    organization_id VARCHAR(64) NOT NULL PRIMARY KEY,
    config_json LONGTEXT NOT NULL,
    revision BIGINT NOT NULL DEFAULT 1
);
