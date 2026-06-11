-- =============================================================================
-- CordysCRM 业务库（如 cordys-crm）中的企业微信日会话缓冲表
-- 在 Navicat 中选中 cordys-crm（或你的 CRM 库）后执行本脚本。
--
-- 新结构：
--   1. wecom_ingestion_session_day  一天一条主记录（客户 external_userid + 专员 userid + 日期）
--   2. wecom_ingestion_message      单条聊天消息明细
--   3. wecom_ingestion_media        图片/语音/视频/文件等媒体元数据，关联 message_id
--
-- 旧版 wecom_ingestion_event 可保留作为历史数据，不再由监测服务写入。
-- 若你的库里已经有旧版 wecom_ingestion_media，需手工执行一次：
--   ALTER TABLE wecom_ingestion_media ADD COLUMN message_id VARCHAR(50) DEFAULT NULL COMMENT '关联 wecom_ingestion_message.id' AFTER event_id;
--   ALTER TABLE wecom_ingestion_media ADD KEY idx_message_id (message_id);
-- =============================================================================

CREATE TABLE IF NOT EXISTS wecom_ingestion_session_day (
  id VARCHAR(50) NOT NULL PRIMARY KEY COMMENT '日会话主记录 ID',
  organization_id VARCHAR(50) NOT NULL COMMENT 'CordysCRM 组织 ID',
  corp_id VARCHAR(64) NOT NULL COMMENT '企业微信 corpId',
  chat_date VARCHAR(10) NOT NULL COMMENT '会话日期，yyyy-MM-dd',
  session_key VARCHAR(512) NOT NULL COMMENT '会话唯一键：single:{external_userid}:{specialist_userid} 或 room:{roomid}',
  chat_type VARCHAR(16) NOT NULL COMMENT 'single=单聊；room=群聊',
  external_userid VARCHAR(128) DEFAULT NULL COMMENT '客户 external_userid（单聊必填，群聊可为空）',
  specialist_userid VARCHAR(128) DEFAULT NULL COMMENT '联系专员企微 userid',
  roomid VARCHAR(128) DEFAULT NULL COMMENT '群聊 roomid；单聊为空',
  first_send_time BIGINT DEFAULT NULL COMMENT '当天第一条消息发送时间（毫秒时间戳）',
  last_send_time BIGINT DEFAULT NULL COMMENT '当天最后一条消息发送时间（毫秒时间戳）',
  message_count INT NOT NULL DEFAULT 0 COMMENT '当天消息数',
  media_count INT NOT NULL DEFAULT 0 COMMENT '当天媒体消息/媒体项数',
  merged_content MEDIUMTEXT COMMENT '当天聊天内容拼接预览，供生成跟进记录',
  status VARCHAR(32) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING=待 CRM 消费；SUCCESS=已处理；FAIL=失败可重试或人工处理',
  follow_record_id VARCHAR(50) DEFAULT NULL COMMENT 'CRM 消费后生成的跟进记录 ID',
  error_message VARCHAR(1024) DEFAULT NULL COMMENT '失败原因（匹配不到客户等）',
  create_user VARCHAR(50) DEFAULT NULL COMMENT '创建人（监测服务可写 wecom-monitor）',
  update_user VARCHAR(50) DEFAULT NULL COMMENT '最后修改人',
  create_time BIGINT NOT NULL COMMENT '创建时间（毫秒时间戳）',
  update_time BIGINT NOT NULL COMMENT '更新时间（毫秒时间戳）',
  UNIQUE KEY uk_org_corp_date_session (organization_id, corp_id, chat_date, session_key),
  KEY idx_status_time (status, update_time),
  KEY idx_org_last_time (organization_id, last_send_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='企业微信日会话主记录：一天一条，CRM 以此生成跟进';

CREATE TABLE IF NOT EXISTS wecom_ingestion_message (
  id VARCHAR(50) NOT NULL PRIMARY KEY COMMENT '消息明细 ID',
  session_day_id VARCHAR(50) NOT NULL COMMENT '关联 wecom_ingestion_session_day.id',
  organization_id VARCHAR(50) NOT NULL COMMENT 'CordysCRM 组织 ID',
  corp_id VARCHAR(64) NOT NULL COMMENT '企业微信 corpId',
  wecom_msg_id VARCHAR(128) NOT NULL COMMENT '企业微信消息幂等键，与监测库 wecom_msg_id 对应',
  message_direction VARCHAR(16) NOT NULL DEFAULT 'OUTBOUND' COMMENT 'OUTBOUND=专员→客户；INBOUND=客户→专员',
  sender_userid VARCHAR(128) DEFAULT NULL COMMENT '专员发送时填 userid；客户发送时为空',
  sender_external_userid VARCHAR(128) DEFAULT NULL COMMENT '客户发送时填 external_userid；专员发送时为空',
  peer_userid VARCHAR(128) DEFAULT NULL COMMENT '单聊 INBOUND：接收方专员 userid',
  chat_type VARCHAR(16) NOT NULL COMMENT 'single=单聊；room=群聊',
  external_userid VARCHAR(128) DEFAULT NULL COMMENT '单聊时：客户侧 external_userid；群聊时常为空',
  roomid VARCHAR(128) DEFAULT NULL COMMENT '群聊时：客户群 roomid；单聊时为空',
  matched_external_userid VARCHAR(128) DEFAULT NULL COMMENT '规则解析出的主客户 external_userid（用于 CRM 匹配客户）',
  msg_type VARCHAR(32) NOT NULL COMMENT '消息顶层类型：text/image/voice/video/file/link 等',
  content_text TEXT COMMENT '文本类消息的预览/摘要；纯媒体消息可为空',
  send_time BIGINT NOT NULL COMMENT '发送时间（毫秒时间戳）',
  extra_json TEXT COMMENT '扩展 JSON：群成员列表、原始引用、解密中的其它字段等',
  status VARCHAR(32) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING=待 CRM 消费；SUCCESS=已处理；FAIL=失败可重试或人工处理',
  follow_record_id VARCHAR(50) DEFAULT NULL COMMENT 'CRM 消费后生成的跟进记录 ID',
  error_message VARCHAR(1024) DEFAULT NULL COMMENT '失败原因',
  create_time BIGINT NOT NULL COMMENT '创建时间（毫秒时间戳）',
  update_user VARCHAR(50) DEFAULT NULL COMMENT '最后修改人',
  update_time BIGINT DEFAULT NULL COMMENT '最后修改时间（毫秒时间戳）',
  UNIQUE KEY uk_org_corp_msg (organization_id, corp_id, wecom_msg_id),
  KEY idx_session_time (session_day_id, send_time),
  KEY idx_org_time (organization_id, send_time),
  KEY idx_session_status (session_day_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='企业微信消息明细：一条聊天消息一条';

CREATE TABLE IF NOT EXISTS wecom_ingestion_media (
  id VARCHAR(50) NOT NULL PRIMARY KEY COMMENT '主键',
  event_id VARCHAR(50) NOT NULL COMMENT '兼容旧列：新版写入 message_id 的同值',
  message_id VARCHAR(50) DEFAULT NULL COMMENT '关联 wecom_ingestion_message.id',
  organization_id VARCHAR(50) NOT NULL COMMENT '组织 ID',
  media_index TINYINT NOT NULL DEFAULT 0 COMMENT '同一消息多块媒体时的序号，从 0 递增',
  msg_media_type VARCHAR(32) NOT NULL COMMENT '媒体类型：image/voice/video/file/emotion 等',
  sdk_file_id MEDIUMTEXT COMMENT '企业微信 sdkfileid（按官方文档拉取媒体内容）',
  file_name VARCHAR(512) DEFAULT NULL COMMENT '文件类消息时的文件名（若有）',
  mime_type VARCHAR(200) DEFAULT NULL COMMENT '转存或下载后的 MIME（可后填）',
  size_bytes BIGINT DEFAULT NULL COMMENT '字节大小（若解密或拉取后可填）',
  duration_ms INT DEFAULT NULL COMMENT '语音/视频时长毫秒（若有）',
  sha256_hex VARCHAR(64) DEFAULT NULL COMMENT '文件内容指纹（转存后可选，用于去重）',
  fetch_status VARCHAR(32) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING=待拉取素材；SUCCESS=已落盘；FAIL=拉取失败',
  crm_asset_ref VARCHAR(256) DEFAULT NULL COMMENT 'CRM 对象存储或内部附件引用（转存成功后填）',
  extra_json TEXT COMMENT '解密 JSON 中与该媒体相关的原始片段（sdkfileid 以外字段）',
  create_time BIGINT NOT NULL COMMENT '创建时间（毫秒时间戳）',
  update_time BIGINT NOT NULL COMMENT '更新时间（毫秒时间戳）',
  KEY idx_event_id (event_id),
  KEY idx_message_id (message_id),
  KEY idx_message_media (message_id, media_index)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='企业微信消息媒体元数据（图/音/视/文件），非邮件附件';
