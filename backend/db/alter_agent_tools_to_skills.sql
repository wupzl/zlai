SET @skills_exists := (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'agent'
      AND COLUMN_NAME = 'skills'
);
SET @skills_sql := IF(
    @skills_exists = 0,
    'ALTER TABLE `agent` ADD COLUMN `skills` TEXT COMMENT ''Skills JSON array'' AFTER `user_id`',
    'SELECT 1'
);
PREPARE skills_stmt FROM @skills_sql;
EXECUTE skills_stmt;
DEALLOCATE PREPARE skills_stmt;

UPDATE `agent`
SET `skills` = REPLACE(
        REPLACE(
            REPLACE(
                REPLACE(
                    REPLACE(COALESCE(`tools`, '[]'),
                        '"calculator"', '"calculation"'),
                    '"datetime"', '"time_lookup"'),
                '"web_search"', '"web_research"'),
            '"translate"', '"translation"'),
        '"summarize"', '"summarization"')
WHERE `skills` IS NULL OR `skills` = '' OR `skills` = '[]';

UPDATE `agent`
SET `team_config` = REPLACE(
        REPLACE(
            REPLACE(
                REPLACE(
                    REPLACE(
                        REPLACE(COALESCE(`team_config`, '[]'),
                            '"tools":', '"skills":'),
                        '"calculator"', '"calculation"'),
                    '"datetime"', '"time_lookup"'),
                '"web_search"', '"web_research"'),
            '"translate"', '"translation"'),
        '"summarize"', '"summarization"')
WHERE `team_config` IS NOT NULL AND `team_config` <> '';
