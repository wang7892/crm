-- Task management tables.
-- Task files are stored under a configurable backend directory;
-- this schema stores only relative paths and file metadata.

CREATE TABLE IF NOT EXISTS `crm_task`
(
    `id`                VARCHAR(32)  NOT NULL COMMENT '任务 ID',
    `organization_id`   VARCHAR(32)  NOT NULL COMMENT '组织 ID',
    `name`              VARCHAR(255) NOT NULL COMMENT '任务名称',
    `source`            VARCHAR(32)  NOT NULL COMMENT '任务来源：AI=智能生成；MANAGER=领导下发',
    `assignee_id`       VARCHAR(32)  NOT NULL COMMENT '联系专员 ID，关联 sys_user.id',
    `customer_id`       VARCHAR(32)  NULL COMMENT '客户 ID，关联 customer.id',
    `description`       TEXT         NULL COMMENT '任务说明',
    `deadline`          BIGINT       NOT NULL COMMENT '最晚完成时间（毫秒时间戳）',
    `status`            VARCHAR(32)  NOT NULL DEFAULT 'PENDING' COMMENT '持久化状态：PENDING=待完成；IN_PROGRESS=执行中；COMPLETED=已完成',
    `report_content`    LONGTEXT     NULL COMMENT '联系专员汇报内容',
    `ai_reply`          LONGTEXT     NULL COMMENT 'AI 预先生成的客户回复',
    `started_at`        BIGINT       NULL COMMENT '开始执行时间（毫秒时间戳）',
    `completed_at`      BIGINT       NULL COMMENT '完成时间（毫秒时间戳）',
    `report_submitted_at` BIGINT     NULL COMMENT '提交汇报时间（毫秒时间戳）',
    `create_time`       BIGINT       NOT NULL COMMENT '创建时间（毫秒时间戳）',
    `update_time`       BIGINT       NOT NULL COMMENT '更新时间（毫秒时间戳）',
    `create_user`       VARCHAR(32)  NOT NULL COMMENT '创建人 ID',
    `update_user`       VARCHAR(32)  NOT NULL COMMENT '最后修改人 ID',
    PRIMARY KEY (`id`),
    KEY `idx_crm_task_org_status_deadline` (`organization_id`, `status`, `deadline`),
    KEY `idx_crm_task_org_assignee_status` (`organization_id`, `assignee_id`, `status`, `deadline`),
    KEY `idx_crm_task_org_customer` (`organization_id`, `customer_id`),
    KEY `idx_crm_task_org_create_time` (`organization_id`, `create_time`)
) COMMENT = 'CRM 任务主表'
ENGINE = InnoDB
DEFAULT CHARSET = utf8mb4
COLLATE = utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS `crm_task_attachment`
(
    `id`                VARCHAR(32)  NOT NULL COMMENT '附件 ID',
    `task_id`           VARCHAR(32)  NOT NULL COMMENT '任务 ID，关联 crm_task.id',
    `organization_id`   VARCHAR(32)  NOT NULL COMMENT '组织 ID',
    `scene`             VARCHAR(32)  NOT NULL COMMENT '附件场景：TASK=任务附件；REPORT=汇报附件',
    `storage_path`      VARCHAR(1024) NOT NULL COMMENT '服务器附件根目录下的相对路径',
    `original_name`     VARCHAR(512)  NOT NULL COMMENT '原始文件名',
    `content_type`      VARCHAR(255) NULL COMMENT '文件 MIME 类型',
    `size_bytes`        BIGINT       NOT NULL DEFAULT 0 COMMENT '文件大小（字节）',
    `sha256_hex`        VARCHAR(64)  NULL COMMENT '文件 SHA-256 指纹',
    `create_time`       BIGINT       NOT NULL COMMENT '创建时间（毫秒时间戳）',
    `update_time`       BIGINT       NOT NULL COMMENT '更新时间（毫秒时间戳）',
    `create_user`       VARCHAR(32)  NOT NULL COMMENT '上传人 ID',
    `update_user`       VARCHAR(32)  NOT NULL COMMENT '最后修改人 ID',
    PRIMARY KEY (`id`),
    KEY `idx_crm_task_attachment_task_scene` (`task_id`, `scene`, `create_time`),
    KEY `idx_crm_task_attachment_org_task` (`organization_id`, `task_id`),
    KEY `idx_crm_task_attachment_hash` (`organization_id`, `sha256_hex`)
) COMMENT = 'CRM 任务附件元数据，文件内容存储于后端服务器本地目录'
ENGINE = InnoDB
DEFAULT CHARSET = utf8mb4
COLLATE = utf8mb4_general_ci;
