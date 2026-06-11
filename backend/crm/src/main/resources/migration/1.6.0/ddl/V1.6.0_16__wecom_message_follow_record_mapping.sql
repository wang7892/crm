CREATE TABLE IF NOT EXISTS wecom_ingestion_message_follow_record (
  id VARCHAR(50) NOT NULL PRIMARY KEY COMMENT '主键',
  organization_id VARCHAR(50) NOT NULL COMMENT '组织 ID',
  message_id VARCHAR(50) NOT NULL COMMENT '企微消息 ID',
  follow_record_id VARCHAR(50) NOT NULL COMMENT '跟进记录 ID',
  create_user VARCHAR(50) DEFAULT NULL COMMENT '创建人',
  create_time BIGINT NOT NULL COMMENT '创建时间（毫秒时间戳）',
  UNIQUE KEY uk_org_msg_follow (organization_id, message_id, follow_record_id),
  KEY idx_org_follow (organization_id, follow_record_id),
  KEY idx_org_message (organization_id, message_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='企微消息与跟进记录关联表';

INSERT IGNORE INTO wecom_ingestion_message_follow_record
  (id, organization_id, message_id, follow_record_id, create_user, create_time)
SELECT REPLACE(UUID(), '-', ''),
       m.organization_id,
       m.id,
       m.follow_record_id,
       m.update_user,
       COALESCE(m.update_time, m.create_time, UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000)
FROM wecom_ingestion_message m
JOIN follow_up_record f
  ON f.id COLLATE utf8mb4_general_ci = m.follow_record_id COLLATE utf8mb4_general_ci
 AND f.organization_id COLLATE utf8mb4_general_ci = m.organization_id COLLATE utf8mb4_general_ci
WHERE m.follow_record_id IS NOT NULL
  AND TRIM(m.follow_record_id) != '';
