CREATE TABLE IF NOT EXISTS fm_survey_upstream (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, account_key CHAR(64) NOT NULL, provider_record_id VARCHAR(128) NULL,
 fingerprint CHAR(64) NOT NULL, phone VARCHAR(32) NOT NULL, sms_code VARCHAR(64) NULL, content TEXT NOT NULL,
 recv_time DATETIME NULL, assigned_form_key VARCHAR(64) NULL, recipient_id BIGINT NULL,
 state VARCHAR(16) NOT NULL DEFAULT 'UNHANDLED', note VARCHAR(1000) NULL, assigned_by BIGINT NULL,
 create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 UNIQUE KEY uk_survey_upstream_event(account_key,fingerprint),
 KEY ix_survey_upstream_inbox(account_key,recv_time), KEY ix_survey_upstream_form(assigned_form_key,recv_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
