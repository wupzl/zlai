SET @agent_skill_exists := (
    SELECT COUNT(*)
    FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'agent_skill'
);
SET @agent_skill_sql := IF(
    @agent_skill_exists = 0,
    'CREATE TABLE `agent_skill` (
        `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT ''Primary key'',
        `skill_key` VARCHAR(64) NOT NULL UNIQUE COMMENT ''Skill key'',
        `name` VARCHAR(128) NOT NULL COMMENT ''Skill name'',
        `description` TEXT COMMENT ''Skill description'',
        `tool_keys` TEXT COMMENT ''Bound tool keys JSON array'',
        `execution_mode` VARCHAR(32) NOT NULL DEFAULT ''single_tool'' COMMENT ''Execution mode: single_tool/pipeline'',
        `input_schema` LONGTEXT COMMENT ''Input schema JSON array'',
        `step_config` LONGTEXT COMMENT ''Step config JSON array'',
        `enabled` TINYINT(1) NOT NULL DEFAULT 1 COMMENT ''Enabled flag'',
        `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT ''Created at'',
        `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT ''Updated at'',
        `is_deleted` TINYINT(1) DEFAULT 0 COMMENT ''Soft delete'',
        PRIMARY KEY (`id`),
        INDEX `idx_skill_enabled` (`enabled`, `updated_at` DESC)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT=''Agent skill catalog''',
    'SELECT 1'
);
PREPARE agent_skill_stmt FROM @agent_skill_sql;
EXECUTE agent_skill_stmt;
DEALLOCATE PREPARE agent_skill_stmt;

SET @execution_mode_exists := (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'agent_skill'
      AND COLUMN_NAME = 'execution_mode'
);
SET @execution_mode_sql := IF(
    @execution_mode_exists = 0,
    'ALTER TABLE `agent_skill` ADD COLUMN `execution_mode` VARCHAR(32) NOT NULL DEFAULT ''single_tool'' COMMENT ''Execution mode: single_tool/pipeline'' AFTER `tool_keys`',
    'SELECT 1'
);
PREPARE execution_mode_stmt FROM @execution_mode_sql;
EXECUTE execution_mode_stmt;
DEALLOCATE PREPARE execution_mode_stmt;

SET @input_schema_exists := (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'agent_skill'
      AND COLUMN_NAME = 'input_schema'
);
SET @input_schema_sql := IF(
    @input_schema_exists = 0,
    'ALTER TABLE `agent_skill` ADD COLUMN `input_schema` LONGTEXT COMMENT ''Input schema JSON array'' AFTER `execution_mode`',
    'SELECT 1'
);
PREPARE input_schema_stmt FROM @input_schema_sql;
EXECUTE input_schema_stmt;
DEALLOCATE PREPARE input_schema_stmt;

SET @step_config_exists := (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'agent_skill'
      AND COLUMN_NAME = 'step_config'
);
SET @step_config_sql := IF(
    @step_config_exists = 0,
    'ALTER TABLE `agent_skill` ADD COLUMN `step_config` LONGTEXT COMMENT ''Step config JSON array'' AFTER `input_schema`',
    'SELECT 1'
);
PREPARE step_config_stmt FROM @step_config_sql;
EXECUTE step_config_stmt;
DEALLOCATE PREPARE step_config_stmt;

INSERT INTO `agent_skill` (`skill_key`, `name`, `description`, `tool_keys`, `execution_mode`, `input_schema`, `step_config`, `enabled`, `is_deleted`)
VALUES
('web_research', 'Web Research', 'Search the web and summarize relevant findings.', '["web_search"]', 'single_tool', '[{"key":"query","type":"string","required":true,"description":"Search query"}]', '[{"toolKey":"web_search","prompt":"Search the web for relevant sources and summarize the findings."}]', 1, 0),
('time_lookup', 'Time Lookup', 'Look up the current time for a timezone or region.', '["datetime"]', 'single_tool', '[{"key":"timezone","type":"string","required":false,"description":"Timezone or region name"}]', '[{"toolKey":"datetime","prompt":"Return the current time for the requested timezone or region."}]', 1, 0),
('calculation', 'Calculation', 'Solve arithmetic and numeric expressions.', '["calculator"]', 'single_tool', '[{"key":"expression","type":"string","required":true,"description":"Math expression to solve"}]', '[{"toolKey":"calculator","prompt":"Evaluate the numeric expression accurately."}]', 1, 0),
('translation', 'Translation', 'Translate text between languages.', '["translation"]', 'single_tool', '[{"key":"text","type":"string","required":true,"description":"Source text"},{"key":"targetLanguage","type":"string","required":true,"description":"Target language"}]', '[{"toolKey":"translation","prompt":"Translate the source text into the target language."}]', 1, 0),
('summarization', 'Summarization', 'Summarize long text into a concise answer.', '["summarize"]', 'single_tool', '[{"key":"text","type":"string","required":true,"description":"Text to summarize"}]', '[{"toolKey":"summarize","prompt":"Summarize the provided text concisely."}]', 1, 0)
ON DUPLICATE KEY UPDATE
`name` = VALUES(`name`),
`description` = VALUES(`description`),
`tool_keys` = VALUES(`tool_keys`),
`execution_mode` = VALUES(`execution_mode`),
`input_schema` = VALUES(`input_schema`),
`step_config` = VALUES(`step_config`),
`enabled` = VALUES(`enabled`),
`is_deleted` = VALUES(`is_deleted`);
