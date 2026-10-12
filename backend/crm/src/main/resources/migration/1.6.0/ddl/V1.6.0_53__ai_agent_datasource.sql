CREATE TABLE IF NOT EXISTS ai_agent_datasource (
    id VARCHAR(64) NOT NULL,
    organization_id VARCHAR(64) NOT NULL,
    owner_id VARCHAR(64) NOT NULL,
    name VARCHAR(100) NOT NULL,
    type VARCHAR(16) NOT NULL,
    config_json MEDIUMTEXT NOT NULL,
    tables_json MEDIUMTEXT NOT NULL,
    selected_json TEXT NOT NULL,
    revision BIGINT NOT NULL,
    update_time BIGINT NOT NULL,
    PRIMARY KEY (id),
    KEY idx_agent_datasource_owner (organization_id, owner_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
