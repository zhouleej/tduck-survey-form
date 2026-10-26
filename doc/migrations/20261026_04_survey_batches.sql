CREATE TABLE IF NOT EXISTS fm_survey_batch (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, form_key VARCHAR(64) NOT NULL,
 name VARCHAR(100) NOT NULL, kind VARCHAR(16) NOT NULL, mode VARCHAR(16) NOT NULL,
 template_id BIGINT NOT NULL, template_version CHAR(64) NOT NULL, template_snapshot MEDIUMTEXT NOT NULL,
 account_key CHAR(64) NOT NULL, config_version CHAR(64) NOT NULL,
 status VARCHAR(24) NOT NULL, scheduled_at DATETIME NOT NULL, owner_id BIGINT NOT NULL,
 request_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL, request_hash CHAR(64) NOT NULL,
 recipient_count INT NOT NULL DEFAULT 0, excluded_count INT NOT NULL DEFAULT 0,
 source_batch_id BIGINT NULL, coverage_accepted TINYINT(1) NOT NULL DEFAULT 0, reason VARCHAR(500) NULL,
 create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 UNIQUE KEY uk_survey_batch_request (form_key, request_key),
 KEY ix_survey_batch_queue (account_key, status, scheduled_at), KEY ix_survey_batch_form (form_key, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS fm_survey_delivery (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, form_key VARCHAR(64) NOT NULL,
 batch_id BIGINT NOT NULL, recipient_id BIGINT NOT NULL, attempt_id BIGINT NULL, invitation_id BIGINT NULL,
 phone VARCHAR(11) NOT NULL, name VARCHAR(100) NULL, company VARCHAR(200) NULL, group_name VARCHAR(100) NULL,
 variables_json TEXT NOT NULL, rendered_content TEXT NOT NULL,
 submit_status VARCHAR(24) NOT NULL DEFAULT 'PENDING', send_status VARCHAR(24) NOT NULL DEFAULT 'PENDING', delivery_status VARCHAR(24) NOT NULL DEFAULT 'WAITING',
 reason VARCHAR(500) NULL, provider_record_id VARCHAR(128) NULL, provider_task_id VARCHAR(64) NULL, provider_msg_id VARCHAR(128) NULL,
 submitted_at DATETIME NULL, send_time DATETIME NULL, recv_time DATETIME NULL, synced_at DATETIME NULL,
 create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 UNIQUE KEY uk_survey_delivery_recipient (batch_id, recipient_id),
 KEY ix_survey_delivery_pending (batch_id, submit_status, id),
 KEY ix_survey_delivery_phone (form_key, phone, submitted_at),
 KEY ix_survey_delivery_task (provider_task_id, phone), KEY ix_survey_delivery_attempt (attempt_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS fm_survey_send_attempt (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, form_key VARCHAR(64) NOT NULL, batch_id BIGINT NOT NULL, account_key CHAR(64) NOT NULL,
 state VARCHAR(24) NOT NULL, provider_task_id VARCHAR(64) NULL, provider_msg_id VARCHAR(128) NULL,
 provider_code VARCHAR(64) NULL, reason VARCHAR(500) NULL, request_digest CHAR(64) NOT NULL,
 started_at DATETIME NOT NULL, finished_at DATETIME NULL,
 create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 KEY ix_survey_attempt_batch (batch_id), KEY ix_survey_attempt_recovery (account_key, state, started_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS fm_survey_dispatch_lease (
 account_key VARCHAR(100) NOT NULL PRIMARY KEY, lease_owner VARCHAR(64) NULL, lease_until DATETIME NULL,
 next_request_at DATETIME NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS fm_survey_suppression (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, account_key CHAR(64) NOT NULL, phone VARCHAR(11) NOT NULL,
 actor_id BIGINT NOT NULL, reason VARCHAR(500) NOT NULL, active TINYINT(1) NOT NULL DEFAULT 1,
 create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 UNIQUE KEY uk_survey_suppression_phone (account_key, phone)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
