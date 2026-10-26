CREATE TABLE IF NOT EXISTS fm_survey_invitation (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 form_key VARCHAR(64) NOT NULL,
 batch_id BIGINT NOT NULL,
 recipient_id BIGINT NULL,
 mode VARCHAR(16) NOT NULL,
 token VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 active TINYINT(1) NOT NULL DEFAULT 1,
 visit_count BIGINT NOT NULL DEFAULT 0,
 first_visit_at DATETIME NULL,
 last_visit_at DATETIME NULL,
 create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 UNIQUE KEY uk_survey_invitation_token (token),
 KEY ix_survey_invitation_batch (batch_id),
 KEY ix_survey_invitation_recipient (form_key, recipient_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS fm_survey_answer_source (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 form_key VARCHAR(64) NOT NULL,
 answer_id BIGINT NOT NULL,
 batch_id BIGINT NOT NULL,
 invitation_id BIGINT NULL,
 recipient_id BIGINT NULL,
 mode VARCHAR(16) NOT NULL,
 create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 UNIQUE KEY uk_survey_answer_source (answer_id),
 KEY ix_survey_answer_batch (form_key, batch_id),
 KEY ix_survey_answer_recipient (form_key, recipient_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
