ALTER TABLE ai_agent_session
  MODIFY COLUMN `id` VARCHAR(64) NOT NULL COMMENT '主键',
  MODIFY COLUMN `organization_id` VARCHAR(64) NOT NULL COMMENT '组织 ID',
  MODIFY COLUMN `user_id` VARCHAR(64) NOT NULL COMMENT '发起会话的用户 ID',
  MODIFY COLUMN `title` VARCHAR(255) NULL COMMENT '会话标题，默认取首条问题摘要',
  MODIFY COLUMN `create_user` VARCHAR(64) NULL COMMENT '创建人 ID',
  MODIFY COLUMN `update_user` VARCHAR(64) NULL COMMENT '最后修改人 ID',
  MODIFY COLUMN `create_time` BIGINT NOT NULL COMMENT '创建时间（毫秒时间戳）',
  MODIFY COLUMN `update_time` BIGINT NOT NULL COMMENT '更新时间（毫秒时间戳）',
  COMMENT = 'AI Agent 会话记录';

ALTER TABLE ai_agent_message
  MODIFY COLUMN `id` VARCHAR(64) NOT NULL COMMENT '主键',
  MODIFY COLUMN `session_id` VARCHAR(64) NOT NULL COMMENT '关联 ai_agent_session.id',
  MODIFY COLUMN `role` VARCHAR(32) NOT NULL COMMENT '消息角色：user=用户；assistant=AI 助手',
  MODIFY COLUMN `content` LONGTEXT NOT NULL COMMENT '消息正文内容',
  MODIFY COLUMN `intent` VARCHAR(64) NULL COMMENT 'AI 识别或命中的业务意图',
  MODIFY COLUMN `evidence_json` JSON NULL COMMENT '回答依据、引用和结构化结果快照 JSON',
  MODIFY COLUMN `create_user` VARCHAR(64) NULL COMMENT '创建人 ID',
  MODIFY COLUMN `update_user` VARCHAR(64) NULL COMMENT '最后修改人 ID',
  MODIFY COLUMN `create_time` BIGINT NOT NULL COMMENT '创建时间（毫秒时间戳）',
  MODIFY COLUMN `update_time` BIGINT NOT NULL COMMENT '更新时间（毫秒时间戳）',
  COMMENT = 'AI Agent 聊天消息记录';

ALTER TABLE ai_agent_tool_call_log
  MODIFY COLUMN `id` VARCHAR(64) NOT NULL COMMENT '主键',
  MODIFY COLUMN `message_id` VARCHAR(64) NOT NULL COMMENT '关联 ai_agent_message.id，通常对应 AI 助手消息',
  MODIFY COLUMN `tool_name` VARCHAR(128) NOT NULL COMMENT '调用的工具名称',
  MODIFY COLUMN `input_json` JSON NULL COMMENT '工具调用入参 JSON',
  MODIFY COLUMN `output_json` JSON NULL COMMENT '工具调用结果、摘要或返回快照 JSON',
  MODIFY COLUMN `status` VARCHAR(32) NOT NULL COMMENT '调用状态：SUCCESS=成功；SKIPPED=跳过；FAIL=失败',
  MODIFY COLUMN `error_message` TEXT NULL COMMENT '调用失败时的错误信息',
  MODIFY COLUMN `duration_ms` BIGINT NULL COMMENT '工具调用耗时（毫秒）',
  MODIFY COLUMN `create_user` VARCHAR(64) NULL COMMENT '创建人 ID',
  MODIFY COLUMN `update_user` VARCHAR(64) NULL COMMENT '最后修改人 ID',
  MODIFY COLUMN `create_time` BIGINT NOT NULL COMMENT '创建时间（毫秒时间戳）',
  MODIFY COLUMN `update_time` BIGINT NOT NULL COMMENT '更新时间（毫秒时间戳）',
  COMMENT = 'AI Agent 工具调用日志';

ALTER TABLE ai_agent_feedback
  MODIFY COLUMN `id` VARCHAR(64) NOT NULL COMMENT '主键',
  MODIFY COLUMN `message_id` VARCHAR(64) NOT NULL COMMENT '被评价的 AI 消息 ID，关联 ai_agent_message.id',
  MODIFY COLUMN `user_id` VARCHAR(64) NOT NULL COMMENT '提交反馈的用户 ID',
  MODIFY COLUMN `rating` VARCHAR(32) NOT NULL COMMENT '反馈评分或态度，如 like/dislike',
  MODIFY COLUMN `comment` TEXT NULL COMMENT '用户补充评价说明',
  MODIFY COLUMN `correct_answer` TEXT NULL COMMENT '用户提供的期望答案或纠正内容',
  MODIFY COLUMN `create_user` VARCHAR(64) NULL COMMENT '创建人 ID',
  MODIFY COLUMN `update_user` VARCHAR(64) NULL COMMENT '最后修改人 ID',
  MODIFY COLUMN `create_time` BIGINT NOT NULL COMMENT '创建时间（毫秒时间戳）',
  MODIFY COLUMN `update_time` BIGINT NOT NULL COMMENT '更新时间（毫秒时间戳）',
  COMMENT = 'AI Agent 用户反馈记录';
