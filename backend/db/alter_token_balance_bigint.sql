USE `zl-ai2`;

ALTER TABLE `account`
    MODIFY COLUMN `token_balance` BIGINT DEFAULT 200000 COMMENT 'Token balance';
