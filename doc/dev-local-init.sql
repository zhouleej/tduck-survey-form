-- 在 doc/tduck-v6.sql 初始化后执行；补全当前源码使用的表单链接扩展表。
CREATE TABLE IF NOT EXISTS `fm_user_form_link_ext` (
    `id` bigint NOT NULL AUTO_INCREMENT,
    `form_key` varchar(50) NOT NULL COMMENT '表单唯一标识',
    `ext_value` varchar(1000) NOT NULL COMMENT '扩展值',
    `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
    `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_form_key` (`form_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='表单链接扩展';

-- 本地前后端分离访问地址；云信等外部服务由管理员自行配置。
UPDATE `sys_env_config`
SET `env_value` = JSON_SET(`env_value`, '$.webBaseUrl', 'http://localhost:8888', '$.openWxMpLogin', CAST('false' AS JSON))
WHERE `env_key` = 'systemInfoConfig';

UPDATE `sys_env_config`
SET `env_value` = JSON_SET(`env_value`, '$.ossType', 'LOCAL', '$.domain', 'http://localhost:8888/tduck-api/u', '$.uploadFolder', './run/upload', '$.accessPathPattern', '/u/**')
WHERE `env_key` = 'fileEnvConfig';
