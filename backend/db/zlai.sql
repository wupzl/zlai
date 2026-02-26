/* =========================================================
   Database: zl-ai2
   Charset : utf8mb4
   Collate : utf8mb4_unicode_ci
   ========================================================= */

DROP SCHEMA IF EXISTS `zl-ai2`;

CREATE SCHEMA IF NOT EXISTS `zl-ai2`
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

USE `zl-ai2`;

/* =========================================================
   1) Account table
   ========================================================= */
CREATE TABLE IF NOT EXISTS `account` (
    `id` BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'User ID',
    `username` VARCHAR(100) NOT NULL UNIQUE COMMENT 'Login username',
    `password` VARCHAR(255) NOT NULL COMMENT 'Password hash',
    `nickname` VARCHAR(100) DEFAULT NULL COMMENT 'Nickname',
    `avatar_url` VARCHAR(255) DEFAULT NULL COMMENT 'Avatar URL',
    `role` VARCHAR(50) NOT NULL DEFAULT 'USER' COMMENT 'Role',
    `status` VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' COMMENT 'Status',
    `last_password_change` DATETIME DEFAULT NULL COMMENT 'Last password change',
    `last_login_time` DATETIME DEFAULT NULL COMMENT 'Last login time',
    `last_logout_time` DATETIME DEFAULT NULL COMMENT 'Last logout time',
    `token_balance` INT DEFAULT 10000 COMMENT 'Token balance',
    `ocr_balance` INT DEFAULT 0 COMMENT 'OCR quota balance',
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT 'Created at',
    `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Updated at',
    `is_deleted` TINYINT(1) DEFAULT 0 COMMENT 'Soft delete',
    INDEX `idx_username_status` (`username`, `status`),
    INDEX `idx_status_created` (`status`, `created_at` DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Account';

/* =========================================================
   2) Login log
   ========================================================= */
CREATE TABLE IF NOT EXISTS `login_log` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT 'Primary key',
    `user_id` BIGINT NOT NULL COMMENT 'User ID',
    `username` VARCHAR(50) NOT NULL COMMENT 'Username',
    `ip_address` VARCHAR(45) COMMENT 'IP address',
    `user_agent` TEXT COMMENT 'User agent',
    `location` VARCHAR(100) COMMENT 'Location',
    `device_type` VARCHAR(50) COMMENT 'Device type',
    `success` TINYINT(1) NOT NULL COMMENT '0=failed, 1=success',
    `fail_reason` VARCHAR(255) COMMENT 'Fail reason',
    `login_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT 'Login time',
    INDEX `idx_user_id` (`user_id`),
    INDEX `idx_username` (`username`),
    INDEX `idx_success` (`success`),
    INDEX `idx_login_time` (`login_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Login log';

/* =========================================================
   3) GPT store
   ========================================================= */
CREATE TABLE IF NOT EXISTS `gpt` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
    `gpt_id` VARCHAR(64) NOT NULL UNIQUE COMMENT 'GPT ID (UUID)',
    `name` VARCHAR(255) NOT NULL COMMENT 'Name',
    `description` TEXT COMMENT 'Description',
    `instructions` TEXT COMMENT 'System instructions',
    `model` VARCHAR(64) DEFAULT NULL COMMENT 'Model',
    `user_id` BIGINT NOT NULL COMMENT 'Owner user ID',
    `avatar_url` VARCHAR(255) DEFAULT NULL COMMENT 'Avatar URL',
    `category` VARCHAR(64) DEFAULT 'general' COMMENT 'Category',
    `is_public` TINYINT(1) DEFAULT 0 COMMENT 'Public flag',
    `usage_count` INT DEFAULT 0 COMMENT 'Usage count',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Created at',
    `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Updated at',
    `is_deleted` TINYINT(1) DEFAULT 0 COMMENT 'Soft delete',
    PRIMARY KEY (`id`),
    INDEX `idx_gpt_id` (`gpt_id`),
    INDEX `idx_user_public` (`user_id`, `is_public`),
    INDEX `idx_public_category` (`is_public`, `category`, `created_at` DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='GPT';

/* =========================================================
   4) Chat session
   ========================================================= */
CREATE TABLE IF NOT EXISTS `chat_session` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
    `chat_id` VARCHAR(64) NOT NULL UNIQUE COMMENT 'Chat ID (UUID)',
    `user_id` BIGINT NOT NULL COMMENT 'User ID',
    `gpt_id` VARCHAR(64) DEFAULT NULL COMMENT 'GPT ID',
    `agent_id` VARCHAR(64) DEFAULT NULL COMMENT 'Agent ID',
    `title` VARCHAR(255) DEFAULT 'New Chat' COMMENT 'Title',
    `model` VARCHAR(64) DEFAULT 'deepseek-chat' COMMENT 'Model',
    `tool_model` VARCHAR(64) DEFAULT NULL COMMENT 'Tool model',
    `system_prompt` TEXT COMMENT 'System prompt',
    `rag_enabled` TINYINT(1) DEFAULT 0 COMMENT 'RAG enabled',
    `current_message_id` VARCHAR(64) DEFAULT NULL COMMENT 'Current message ID',
    `message_count` INT DEFAULT 0 COMMENT 'Message count',
    `total_tokens` INT DEFAULT 0 COMMENT 'Total tokens',
    `last_active_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT 'Last active time',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Created at',
    `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Updated at',
    `is_deleted` TINYINT(1) DEFAULT 0 COMMENT 'Soft delete',
    PRIMARY KEY (`id`),
    INDEX `idx_user_active` (`user_id`, `last_active_time` DESC),
    INDEX `idx_gpt_id` (`gpt_id`),
    INDEX `idx_user_created` (`user_id`, `created_at` DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Chat session';

/* =========================================================
   5) Message
   ========================================================= */
CREATE TABLE IF NOT EXISTS `message` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
    `message_id` VARCHAR(64) NOT NULL UNIQUE COMMENT 'Message ID (UUID)',
    `chat_id` VARCHAR(64) NOT NULL COMMENT 'Chat ID',
    `parent_message_id` VARCHAR(64) DEFAULT NULL COMMENT 'Parent message ID',
    `role` VARCHAR(32) NOT NULL COMMENT 'Role: user/assistant/system',
    `content` LONGTEXT NOT NULL COMMENT 'Content',
    `tokens` INT DEFAULT 0 COMMENT 'Token count',
    `model` VARCHAR(64) DEFAULT NULL COMMENT 'Model',
    `status` VARCHAR(32) NOT NULL DEFAULT 'SUCCESS' COMMENT 'Status',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Created at',
    `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Updated at',
    PRIMARY KEY (`id`),
    INDEX `idx_chat_id` (`chat_id`),
    INDEX `idx_parent_id` (`parent_message_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Message';

/* =========================================================
   6) Token consumption
   ========================================================= */
CREATE TABLE IF NOT EXISTS `token_consumption` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
    `user_id` BIGINT NOT NULL COMMENT 'User ID',
    `chat_id` VARCHAR(64) NOT NULL COMMENT 'Chat ID',
    `message_id` VARCHAR(64) DEFAULT NULL COMMENT 'Message ID',
    `model` VARCHAR(64) NOT NULL COMMENT 'Model',
    `prompt_tokens` INT DEFAULT 0 COMMENT 'Prompt tokens',
    `completion_tokens` INT DEFAULT 0 COMMENT 'Completion tokens',
    `total_tokens` INT DEFAULT 0 COMMENT 'Total tokens',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Created at',
    PRIMARY KEY (`id`),
    INDEX `idx_user_time` (`user_id`, `created_at` DESC),
    INDEX `idx_chat_id` (`chat_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Token consumption';

/* =========================================================
   7) System log
   ========================================================= */
CREATE TABLE IF NOT EXISTS `system_log` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
    `user_id` BIGINT DEFAULT NULL COMMENT 'User ID',
    `operation` VARCHAR(64) NOT NULL COMMENT 'Operation',
    `module` VARCHAR(64) NOT NULL COMMENT 'Module',
    `request_ip` VARCHAR(45) DEFAULT NULL COMMENT 'Request IP',
    `status` VARCHAR(20) NOT NULL COMMENT 'Status',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Created at',
    PRIMARY KEY (`id`),
    INDEX `idx_user_id` (`user_id`),
    INDEX `idx_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='System log';

/* =========================================================
   8) Model pricing
   ========================================================= */
CREATE TABLE IF NOT EXISTS `model_pricing` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
    `model` VARCHAR(64) NOT NULL UNIQUE COMMENT 'Model',
    `multiplier` DOUBLE NOT NULL DEFAULT 1.0 COMMENT 'Billing multiplier',
    `updated_by` BIGINT DEFAULT NULL COMMENT 'Updated by (admin user id)',
    `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Updated at',
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Model pricing';

/* =========================================================
   9) Model pricing audit log
   ========================================================= */
CREATE TABLE IF NOT EXISTS `model_pricing_log` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
    `model` VARCHAR(64) NOT NULL COMMENT 'Model',
    `old_multiplier` DOUBLE DEFAULT NULL COMMENT 'Old multiplier',
    `new_multiplier` DOUBLE NOT NULL COMMENT 'New multiplier',
    `updated_by` BIGINT DEFAULT NULL COMMENT 'Updated by (admin user id)',
    `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT 'Updated at',
    PRIMARY KEY (`id`),
    INDEX `idx_model_time` (`model`, `updated_at` DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Model pricing audit log';

/* =========================================================
   10) App config
   ========================================================= */
CREATE TABLE IF NOT EXISTS `app_config` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
    `config_key` VARCHAR(128) NOT NULL UNIQUE COMMENT 'Config key',
    `config_value` TEXT COMMENT 'Config value',
    `updated_by` BIGINT DEFAULT NULL COMMENT 'Updated by (admin user id)',
    `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Updated at',
    PRIMARY KEY (`id`),
    INDEX `idx_config_key` (`config_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='App config';

/* =========================================================
   8) Agent
   ========================================================= */
CREATE TABLE IF NOT EXISTS `agent` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
    `agent_id` VARCHAR(64) NOT NULL UNIQUE COMMENT 'Agent ID (UUID)',
    `name` VARCHAR(255) NOT NULL COMMENT 'Name',
    `description` TEXT COMMENT 'Description',
    `instructions` TEXT COMMENT 'System instructions',
    `model` VARCHAR(64) NOT NULL DEFAULT 'deepseek-chat' COMMENT 'Model',
    `tool_model` VARCHAR(64) DEFAULT NULL COMMENT 'Tool model',
    `user_id` BIGINT NOT NULL COMMENT 'Owner user ID',
    `tools` TEXT COMMENT 'Tools JSON array',
    `multi_agent` TINYINT(1) NOT NULL DEFAULT 0 COMMENT 'Enable multi-agent orchestration',
    `team_agent_ids` TEXT COMMENT 'Team agent ids JSON array',
    `team_config` TEXT COMMENT 'Team config JSON array',
    `is_public` TINYINT(1) NOT NULL DEFAULT 0 COMMENT 'Public flag',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Created at',
    `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Updated at',
    `is_deleted` TINYINT(1) DEFAULT 0 COMMENT 'Soft delete',
    PRIMARY KEY (`id`),
    INDEX `idx_agent_id` (`agent_id`),
    INDEX `idx_user_public` (`user_id`, `is_public`),
    INDEX `idx_public_created` (`is_public`, `created_at` DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Agent';
