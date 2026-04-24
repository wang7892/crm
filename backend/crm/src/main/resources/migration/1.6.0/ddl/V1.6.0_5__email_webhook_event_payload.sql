-- Add payload fields to email_webhook_event for keeping original webhook content.
-- MySQL doesn't support ADD COLUMN IF NOT EXISTS universally, so we use information_schema + dynamic SQL.

SET @col_subject_exists := (
    SELECT COUNT(1)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'email_webhook_event'
      AND column_name = 'subject'
);
SET @ddl_add_subject := IF(
    @col_subject_exists = 0,
    'ALTER TABLE email_webhook_event ADD COLUMN subject VARCHAR(512) NULL COMMENT ''邮件主题''',
    'SELECT 1'
);
PREPARE stmt_add_subject FROM @ddl_add_subject;
EXECUTE stmt_add_subject;
DEALLOCATE PREPARE stmt_add_subject;

SET @col_content_text_exists := (
    SELECT COUNT(1)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'email_webhook_event'
      AND column_name = 'content_text'
);
SET @ddl_add_content_text := IF(
    @col_content_text_exists = 0,
    'ALTER TABLE email_webhook_event ADD COLUMN content_text TEXT NULL COMMENT ''邮件正文纯文本''',
    'SELECT 1'
);
PREPARE stmt_add_content_text FROM @ddl_add_content_text;
EXECUTE stmt_add_content_text;
DEALLOCATE PREPARE stmt_add_content_text;

SET @col_to_addresses_exists := (
    SELECT COUNT(1)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'email_webhook_event'
      AND column_name = 'to_addresses'
);
SET @ddl_add_to_addresses := IF(
    @col_to_addresses_exists = 0,
    'ALTER TABLE email_webhook_event ADD COLUMN to_addresses TEXT NULL COMMENT ''收件人列表(JSON)''',
    'SELECT 1'
);
PREPARE stmt_add_to_addresses FROM @ddl_add_to_addresses;
EXECUTE stmt_add_to_addresses;
DEALLOCATE PREPARE stmt_add_to_addresses;

SET @col_cc_addresses_exists := (
    SELECT COUNT(1)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'email_webhook_event'
      AND column_name = 'cc_addresses'
);
SET @ddl_add_cc_addresses := IF(
    @col_cc_addresses_exists = 0,
    'ALTER TABLE email_webhook_event ADD COLUMN cc_addresses TEXT NULL COMMENT ''抄送人列表(JSON)''',
    'SELECT 1'
);
PREPARE stmt_add_cc_addresses FROM @ddl_add_cc_addresses;
EXECUTE stmt_add_cc_addresses;
DEALLOCATE PREPARE stmt_add_cc_addresses;

