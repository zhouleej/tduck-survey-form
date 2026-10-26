CREATE TABLE IF NOT EXISTS fm_survey_receipt (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, account_key CHAR(64) NOT NULL,
 provider_record_id VARCHAR(128) NULL, record_identity CHAR(64) NOT NULL,
 provider_task_id VARCHAR(64) NULL, phone VARCHAR(32) NULL, provider_msg_id VARCHAR(128) NULL, cust_id VARCHAR(128) NULL,
 fingerprint CHAR(64) NOT NULL, raw_json TEXT NOT NULL,
 matched_delivery_id BIGINT NULL, match_state VARCHAR(32) NOT NULL, match_reason VARCHAR(500) NULL,
 send_time DATETIME NULL, recv_time DATETIME NULL,
 create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 UNIQUE KEY uk_survey_receipt_event (account_key,fingerprint),
 KEY ix_survey_receipt_identity (account_key,record_identity),
 KEY ix_survey_receipt_delivery (matched_delivery_id), KEY ix_survey_receipt_unmatched (account_key,match_state)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS fm_survey_sync_cursor (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 account_key CHAR(64) NOT NULL, kind VARCHAR(16) NOT NULL, source_key VARCHAR(64) NOT NULL,
 window_start DATETIME NULL, window_end DATETIME NULL, page_num INT NOT NULL DEFAULT 1,
 poll_step INT NOT NULL DEFAULT 0, failures INT NOT NULL DEFAULT 0, next_poll_at DATETIME NOT NULL,
 manual_requested TINYINT(1) NOT NULL DEFAULT 0, requested_from DATETIME NULL,
 previous_page_hash CHAR(64) NULL,
 last_error VARCHAR(500) NULL,
 create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 UNIQUE KEY uk_survey_sync_source (account_key,kind,source_key),
 KEY ix_survey_sync_due (account_key,kind,next_poll_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 拆分条数只使用正式接口实际返回的值；允许升级中断后重试。
SET @survey_split_ddl = IF(
 (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='fm_survey_delivery' AND COLUMN_NAME='split_size')=0,
 'ALTER TABLE fm_survey_delivery ADD COLUMN split_size INT NULL', 'SELECT 1');
PREPARE survey_split_statement FROM @survey_split_ddl;
EXECUTE survey_split_statement;
DEALLOCATE PREPARE survey_split_statement;
