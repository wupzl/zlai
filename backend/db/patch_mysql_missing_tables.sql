USE `zl-ai2`;

CREATE TABLE IF NOT EXISTS `chat_request_idempotency` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
    `user_id` BIGINT NOT NULL COMMENT 'User ID',
    `request_id` VARCHAR(80) NOT NULL COMMENT 'Idempotency request id',
    `request_type` VARCHAR(32) NOT NULL COMMENT 'Request type',
    `chat_id` VARCHAR(64) DEFAULT NULL COMMENT 'Chat ID',
    `message_id` VARCHAR(64) DEFAULT NULL COMMENT 'User message ID',
    `request_hash` VARCHAR(64) NOT NULL COMMENT 'Request hash',
    `status` VARCHAR(16) NOT NULL COMMENT 'PENDING/DONE/FAILED/INTERRUPTED',
    `response_message_id` VARCHAR(64) DEFAULT NULL COMMENT 'Assistant message ID',
    `response_content` LONGTEXT COMMENT 'Cached response for replay',
    `error_message` VARCHAR(255) DEFAULT NULL COMMENT 'Error message',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Created at',
    `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Updated at',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_request` (`user_id`, `request_id`),
    INDEX `idx_chat_type` (`chat_id`, `request_type`),
    INDEX `idx_status_updated` (`status`, `updated_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Chat request idempotency';

CREATE TABLE IF NOT EXISTS `user_favorite` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
    `user_id` BIGINT NOT NULL COMMENT 'User ID',
    `gpt_id` VARCHAR(64) NOT NULL COMMENT 'GPT ID',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Created at',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_gpt` (`user_id`, `gpt_id`),
    INDEX `idx_gpt_id` (`gpt_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='User favorite GPT';
