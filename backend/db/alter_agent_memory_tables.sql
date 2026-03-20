CREATE TABLE IF NOT EXISTS `conversation_summary` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
    `chat_id` VARCHAR(64) NOT NULL COMMENT 'Chat ID',
    `user_id` BIGINT NOT NULL COMMENT 'User ID',
    `summary` LONGTEXT COMMENT 'Persistent conversation summary',
    `last_message_id` VARCHAR(64) DEFAULT NULL COMMENT 'Last summarized message id',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Created at',
    `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Updated at',
    `is_deleted` TINYINT(1) DEFAULT 0 COMMENT 'Soft delete',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_chat_id` (`chat_id`),
    INDEX `idx_user_updated` (`user_id`, `updated_at` DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Conversation summary memory';

CREATE TABLE IF NOT EXISTS `user_memory` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
    `user_id` BIGINT NOT NULL COMMENT 'User ID',
    `memory_key` VARCHAR(64) NOT NULL COMMENT 'Memory key',
    `memory_value` TEXT COMMENT 'Memory value',
    `confidence` DOUBLE DEFAULT 0.5 COMMENT 'Confidence',
    `source` VARCHAR(64) DEFAULT NULL COMMENT 'Source',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Created at',
    `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Updated at',
    `is_deleted` TINYINT(1) DEFAULT 0 COMMENT 'Soft delete',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_memory_key` (`user_id`, `memory_key`),
    INDEX `idx_user_memory_updated` (`user_id`, `updated_at` DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='User memory';

CREATE TABLE IF NOT EXISTS `task_state` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
    `chat_id` VARCHAR(64) NOT NULL COMMENT 'Chat ID',
    `user_id` BIGINT NOT NULL COMMENT 'User ID',
    `task_key` VARCHAR(64) NOT NULL DEFAULT 'default' COMMENT 'Task key',
    `goal` TEXT COMMENT 'Task goal',
    `current_skill` VARCHAR(64) DEFAULT NULL COMMENT 'Current skill',
    `current_step` VARCHAR(64) DEFAULT NULL COMMENT 'Current step',
    `artifacts_json` LONGTEXT COMMENT 'Artifacts JSON',
    `status` VARCHAR(32) NOT NULL DEFAULT 'ACTIVE' COMMENT 'Task status',
    `last_message_id` VARCHAR(64) DEFAULT NULL COMMENT 'Last related message id',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Created at',
    `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Updated at',
    `is_deleted` TINYINT(1) DEFAULT 0 COMMENT 'Soft delete',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_chat_task_key` (`chat_id`, `task_key`),
    INDEX `idx_user_task_updated` (`user_id`, `updated_at` DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Task state memory';