-- =============================================================================
-- 企业微信「监测服务」数据库（如 wecom_monitoring_db）
-- 在 Navicat 中选中该库后执行本脚本即可（CREATE IF NOT EXISTS，可重复执行）。
-- 共 4 张表：游标、原始报文、归一化消息、监测专员名单。
-- 升级说明：若你曾使用旧版（无 message_direction、唯一键为 uk_corp_sender_msg），请备份后
--           删除旧表再执行本脚本，或自行 ALTER 对齐本文件结构。
-- =============================================================================

CREATE TABLE IF NOT EXISTS wecom_sync_checkpoint (
  id VARCHAR(64) NOT NULL PRIMARY KEY COMMENT '主键',
  corp_id VARCHAR(64) NOT NULL COMMENT '企业微信企业 ID（corpId）',
  checkpoint_type VARCHAR(32) NOT NULL COMMENT '游标类型，如 ARCHIVE_SEQ（会话存档拉取位点）',
  last_seq BIGINT DEFAULT NULL COMMENT '会话存档接口返回的上一成功消费 seq，下次拉取从此继续',
  last_cursor VARCHAR(255) DEFAULT NULL COMMENT '备用游标（若接入版本使用 cursor 而非 seq）',
  create_time BIGINT NOT NULL COMMENT '创建时间（毫秒时间戳，与 CRM BaseModel 风格一致）',
  update_time BIGINT NOT NULL COMMENT '更新时间（毫秒时间戳）',
  UNIQUE KEY uk_corp_checkpoint (corp_id, checkpoint_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会话存档同步游标：断点续拉、防重复';

CREATE TABLE IF NOT EXISTS wecom_raw_message (
  id VARCHAR(64) NOT NULL PRIMARY KEY COMMENT '主键',
  corp_id VARCHAR(64) NOT NULL COMMENT '企业微信 corpId',
  wecom_msg_id VARCHAR(128) DEFAULT NULL COMMENT '消息唯一标识（解密得到后填入；未解密时可为占位）',
  seq BIGINT DEFAULT NULL COMMENT '企业微信会话存档返回的 seq，用于对账与排序',
  decrypted_payload MEDIUMTEXT COMMENT '解密后的单条消息 JSON，或暂存企业微信返回的加密包原文（排障）',
  create_time BIGINT NOT NULL COMMENT '入库时间（毫秒时间戳）',
  KEY idx_corp_seq (corp_id, seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='原始/排障：存档报文留痕，便于对接官方 SDK 解密';

CREATE TABLE IF NOT EXISTS wecom_message_event (
  id VARCHAR(64) NOT NULL PRIMARY KEY COMMENT '本表主键',
  organization_id VARCHAR(64) NOT NULL COMMENT 'CordysCRM 组织 ID（多租户）',
  corp_id VARCHAR(64) NOT NULL COMMENT '企业微信 corpId',
  wecom_msg_id VARCHAR(128) NOT NULL COMMENT '企业微信侧消息幂等键（与存档/解密结果一致）',
  message_direction VARCHAR(16) NOT NULL DEFAULT 'OUTBOUND' COMMENT 'OUTBOUND=联系专员→客户；INBOUND=客户→联系专员',
  sender_userid VARCHAR(128) DEFAULT NULL COMMENT '发送方为企业成员时：userid；客户发送时为空',
  sender_external_userid VARCHAR(128) DEFAULT NULL COMMENT '发送方为外部联系人时：external_userid；专员发送时为空',
  peer_userid VARCHAR(128) DEFAULT NULL COMMENT '单聊 INBOUND：接收方专员 userid（须在监测名单）；单聊 OUTBOUND 可空',
  chat_type VARCHAR(16) NOT NULL COMMENT '会话形态：single=与外部联系人单聊；room=群聊（含客户群）',
  external_userid VARCHAR(128) DEFAULT NULL COMMENT '单聊中客户侧 external_userid（会话维度标识）',
  roomid VARCHAR(128) DEFAULT NULL COMMENT '群聊(chat_type=room)时：群/客户群 roomid；单聊时为空',
  room_external_snapshot TEXT COMMENT '群场景下成员 JSON 快照；INBOUND 群聊时建议含监测 userid 以便规则命中',
  msg_type VARCHAR(32) NOT NULL COMMENT '消息类型：text/image/file 等（与企业微信解密字段一致）',
  content_text TEXT COMMENT '文本摘要或正文（可按合规策略截断）',
  send_time BIGINT NOT NULL COMMENT '消息发送时间（毫秒时间戳）',
  crm_ingestion_id VARCHAR(64) DEFAULT NULL COMMENT '已成功写入 CRM 库 wecom_ingestion_event.id 时回填',
  dedup_hash VARCHAR(64) DEFAULT NULL COMMENT '辅助幂等指纹（可选）',
  create_time BIGINT NOT NULL COMMENT '本行写入时间（毫秒时间戳）',
  UNIQUE KEY uk_corp_msg (corp_id, wecom_msg_id),
  KEY idx_org_time (organization_id, send_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='监测侧归一化消息（专员↔客户），供报表与写 CRM 缓冲表';

CREATE TABLE IF NOT EXISTS wecom_monitored_user (
  id VARCHAR(64) NOT NULL PRIMARY KEY COMMENT '主键',
  corp_id VARCHAR(64) NOT NULL COMMENT '企业微信 corpId',
  userid VARCHAR(128) NOT NULL COMMENT '需监测的企业成员 userid（联系专员账号，与 sender_userid 同源概念）',
  organization_id VARCHAR(64) DEFAULT NULL COMMENT '对应 CRM 组织 ID，便于映射缓冲表 organization_id',
  enabled TINYINT NOT NULL DEFAULT 1 COMMENT '1=参与监测规则；0=暂停',
  create_time BIGINT NOT NULL COMMENT '创建时间（毫秒时间戳）',
  update_time BIGINT NOT NULL COMMENT '更新时间（毫秒时间戳）',
  UNIQUE KEY uk_corp_user (corp_id, userid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='监测范围：哪些企业成员与客户会话需要落库';
