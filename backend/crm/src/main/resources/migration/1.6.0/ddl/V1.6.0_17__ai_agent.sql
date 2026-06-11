CREATE TABLE IF NOT EXISTS ai_agent_session (
  id VARCHAR(64) PRIMARY KEY,
  organization_id VARCHAR(64) NOT NULL,
  user_id VARCHAR(64) NOT NULL,
  title VARCHAR(255),
  create_user VARCHAR(64),
  update_user VARCHAR(64),
  create_time BIGINT NOT NULL,
  update_time BIGINT NOT NULL,
  INDEX idx_ai_agent_session_user (organization_id, user_id, update_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS ai_agent_message (
  id VARCHAR(64) PRIMARY KEY,
  session_id VARCHAR(64) NOT NULL,
  role VARCHAR(32) NOT NULL,
  content LONGTEXT NOT NULL,
  intent VARCHAR(64),
  evidence_json JSON,
  create_user VARCHAR(64),
  update_user VARCHAR(64),
  create_time BIGINT NOT NULL,
  update_time BIGINT NOT NULL,
  INDEX idx_ai_agent_message_session (session_id, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS ai_agent_tool_call_log (
  id VARCHAR(64) PRIMARY KEY,
  message_id VARCHAR(64) NOT NULL,
  tool_name VARCHAR(128) NOT NULL,
  input_json JSON,
  output_json JSON,
  status VARCHAR(32) NOT NULL,
  error_message TEXT,
  duration_ms BIGINT,
  create_user VARCHAR(64),
  update_user VARCHAR(64),
  create_time BIGINT NOT NULL,
  update_time BIGINT NOT NULL,
  INDEX idx_ai_agent_tool_message (message_id, create_time),
  INDEX idx_ai_agent_tool_name (tool_name, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS ai_agent_feedback (
  id VARCHAR(64) PRIMARY KEY,
  message_id VARCHAR(64) NOT NULL,
  user_id VARCHAR(64) NOT NULL,
  rating VARCHAR(32) NOT NULL,
  comment TEXT,
  correct_answer TEXT,
  create_user VARCHAR(64),
  update_user VARCHAR(64),
  create_time BIGINT NOT NULL,
  update_time BIGINT NOT NULL,
  INDEX idx_ai_agent_feedback_message (message_id, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
