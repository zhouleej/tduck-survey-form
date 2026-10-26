CREATE TABLE IF NOT EXISTS fm_survey_sms_template (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 owner_id BIGINT NOT NULL,
 shared TINYINT(1) NOT NULL DEFAULT 0,
 account_key CHAR(64) NOT NULL,
 name VARCHAR(100) NOT NULL,
 provider_template_id VARCHAR(64) NULL,
 content TEXT NOT NULL,
 variable TINYINT(1) NOT NULL DEFAULT 1,
 mapping_json TEXT NOT NULL,
 version CHAR(64) NOT NULL,
 application_state VARCHAR(32) NOT NULL DEFAULT 'BOUND',
 audit_json TEXT NULL,
 audited_at DATETIME NULL,
 create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 KEY ix_survey_template_owner (owner_id, shared, account_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
