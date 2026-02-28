USE `zl-ai2`;

/* Reset demo business rows (keep user accounts) */
DELETE FROM `message` WHERE `chat_id` = 'chat-demo-001';
DELETE FROM `chat_session` WHERE `chat_id` = 'chat-demo-001';
DELETE FROM `gpt` WHERE `gpt_id` = 'gpt-demo-001';
DELETE FROM `agent` WHERE `agent_id` = 'agent-demo-001';
DELETE FROM `system_log` WHERE `operation` = 'SEED_DATA';
DELETE FROM `app_config` WHERE `config_key` IN (
  'tools.search.searx.enabled',
  'tools.search.searx.url',
  'tools.search.serpapi.key',
  'tools.search.serpapi.engine',
  'tools.search.wikipedia.enabled',
  'tools.search.wikipedia.user_agent',
  'tools.search.wikipedia.proxy_enabled',
  'tools.search.wikipedia.proxy_url',
  'tools.search.baike.enabled',
  'tools.search.bocha.enabled',
  'tools.search.bocha.api_key',
  'tools.search.bocha.endpoint'
  , 'tools.search.baidu.enabled'
  , 'rate_limit.global.enabled'
  , 'rate_limit.global.admin_bypass'
  , 'rate_limit.global.window_seconds'
  , 'rate_limit.global.ip_limit'
  , 'rate_limit.global.user_limit'
  , 'rate_limit.global.whitelist_ips'
  , 'rate_limit.global.whitelist_paths'
  , 'rag.ocr.enabled'
  , 'rag.ocr.max_images_per_request'
  , 'rag.ocr.max_image_bytes'
  , 'rag.ocr.max_pdf_pages'
  , 'rag.ocr.rate_limit_per_day'
  , 'rag.ocr.rate_limit_window_seconds'
  , 'rag.ocr.default_user_quota'
  , 'openai.stream.enabled'
);

/* Upsert demo users */
INSERT INTO `account` (`username`, `password`, `nickname`, `role`, `status`, `token_balance`, `ocr_balance`, `is_deleted`)
VALUES
('wupzl', '$2a$10$d6RT20PoN9r4TMBFl.y/5ei2aRZkgvwVBW19NG/xcqTj5YETLelZe', 'wupzl', 'USER', 'ACTIVE', 100000, 500, 0),
('admin01', '$2a$10$d6RT20PoN9r4TMBFl.y/5ei2aRZkgvwVBW19NG/xcqTj5YETLelZe', 'admin', 'ADMIN', 'ACTIVE', 100000, 999999, 0),
('test01', '$2a$10$d6RT20PoN9r4TMBFl.y/5ei2aRZkgvwVBW19NG/xcqTj5YETLelZe', 'test01', 'USER', 'ACTIVE', 100000, 300, 0),
('test02', '$2a$10$d6RT20PoN9r4TMBFl.y/5ei2aRZkgvwVBW19NG/xcqTj5YETLelZe', 'test02', 'USER', 'ACTIVE', 100000, 300, 0),
('test03', '$2a$10$d6RT20PoN9r4TMBFl.y/5ei2aRZkgvwVBW19NG/xcqTj5YETLelZe', 'test03', 'USER', 'ACTIVE', 100000, 300, 0),
('test04', '$2a$10$d6RT20PoN9r4TMBFl.y/5ei2aRZkgvwVBW19NG/xcqTj5YETLelZe', 'test04', 'USER', 'ACTIVE', 100000, 300, 0),
('test05', '$2a$10$d6RT20PoN9r4TMBFl.y/5ei2aRZkgvwVBW19NG/xcqTj5YETLelZe', 'test05', 'USER', 'ACTIVE', 100000, 300, 0)
ON DUPLICATE KEY UPDATE
`password` = VALUES(`password`),
`nickname` = VALUES(`nickname`),
`role` = VALUES(`role`),
`status` = VALUES(`status`),
`token_balance` = VALUES(`token_balance`),
`ocr_balance` = VALUES(`ocr_balance`),
`is_deleted` = VALUES(`is_deleted`);

/* Demo GPT */
INSERT INTO `gpt`
(`gpt_id`, `name`, `description`, `instructions`, `model`, `user_id`, `avatar_url`, `category`, `is_public`, `request_public`, `usage_count`, `is_deleted`)
SELECT
    'gpt-demo-001',
    'Interview Copilot',
    'Demo GPT for interview prep',
    'You are a concise interview copilot.',
    'deepseek-chat',
    a.`id`,
    NULL,
    'interview',
    1,
    0,
    12,
    0
FROM `account` a
WHERE a.`username` = 'test01'
LIMIT 1;

/* Demo multi-agent */
INSERT INTO `agent`
(`agent_id`, `name`, `description`, `instructions`, `model`, `user_id`, `tools`, `multi_agent`, `is_public`, `is_deleted`)
SELECT
    'agent-demo-001',
    'Multi Agent Tutor',
    'Planner + Specialist + Critic demo agent',
    'Teach with clear steps and concise explanations.',
    'deepseek-chat',
    a.`id`,
    '["calculator","datetime","translate"]',
    1,
    1,
    0
FROM `account` a
WHERE a.`username` = 'test01'
LIMIT 1;

/* Demo chat session */
INSERT INTO `chat_session`
(`chat_id`, `user_id`, `gpt_id`, `agent_id`, `title`, `model`, `system_prompt`, `rag_enabled`, `current_message_id`, `message_count`, `total_tokens`, `is_deleted`)
SELECT
    'chat-demo-001',
    a.`id`,
    'gpt-demo-001',
    'agent-demo-001',
    'Demo Interview Session',
    'deepseek-chat',
    'You are an interview tutor.',
    0,
    'msg-demo-a1',
    2,
    0,
    0
FROM `account` a
WHERE a.`username` = 'test01'
LIMIT 1;

/* Demo messages */
INSERT INTO `message`
(`message_id`, `chat_id`, `parent_message_id`, `role`, `content`, `tokens`, `model`, `status`)
VALUES
('msg-demo-u1', 'chat-demo-001', NULL, 'user', 'Explain CAP theorem in distributed systems.', 21, 'deepseek-chat', 'SUCCESS'),
('msg-demo-a1', 'chat-demo-001', 'msg-demo-u1', 'assistant', 'CAP theorem says a distributed system can only guarantee two of Consistency, Availability, and Partition tolerance at the same time.', 39, 'deepseek-chat', 'SUCCESS');

/* Seed marker log */
INSERT INTO `system_log` (`user_id`, `operation`, `module`, `request_ip`, `status`)
SELECT a.`id`, 'SEED_DATA', 'database', '127.0.0.1', 'SUCCESS'
FROM `account` a
WHERE a.`username` = 'admin01'
LIMIT 1;

/* Default tool search config */
INSERT INTO `app_config` (`config_key`, `config_value`, `updated_by`)
SELECT 'tools.search.searx.enabled', 'false', a.`id`
FROM `account` a WHERE a.`username` = 'admin01' LIMIT 1;
INSERT INTO `app_config` (`config_key`, `config_value`, `updated_by`)
SELECT 'tools.search.searx.url', '', a.`id`
FROM `account` a WHERE a.`username` = 'admin01' LIMIT 1;
INSERT INTO `app_config` (`config_key`, `config_value`, `updated_by`)
SELECT 'tools.search.serpapi.key', '', a.`id`
FROM `account` a WHERE a.`username` = 'admin01' LIMIT 1;
INSERT INTO `app_config` (`config_key`, `config_value`, `updated_by`)
SELECT 'tools.search.serpapi.engine', 'baidu', a.`id`
FROM `account` a WHERE a.`username` = 'admin01' LIMIT 1;
INSERT INTO `app_config` (`config_key`, `config_value`, `updated_by`)
SELECT 'tools.search.wikipedia.enabled', 'false', a.`id`
FROM `account` a WHERE a.`username` = 'admin01' LIMIT 1;
INSERT INTO `app_config` (`config_key`, `config_value`, `updated_by`)
SELECT 'tools.search.baike.enabled', 'false', a.`id`
FROM `account` a WHERE a.`username` = 'admin01' LIMIT 1;
INSERT INTO `app_config` (`config_key`, `config_value`, `updated_by`)
SELECT 'tools.search.bocha.enabled', 'true', a.`id`
FROM `account` a WHERE a.`username` = 'admin01' LIMIT 1;
INSERT INTO `app_config` (`config_key`, `config_value`, `updated_by`)
SELECT 'tools.search.bocha.api_key', '', a.`id`
FROM `account` a WHERE a.`username` = 'admin01' LIMIT 1;
INSERT INTO `app_config` (`config_key`, `config_value`, `updated_by`)
SELECT 'tools.search.bocha.endpoint', 'https://api.bocha.cn/v1/web-search', a.`id`
FROM `account` a WHERE a.`username` = 'admin01' LIMIT 1;
INSERT INTO `app_config` (`config_key`, `config_value`, `updated_by`)
SELECT 'tools.search.baidu.enabled', 'true', a.`id`
FROM `account` a WHERE a.`username` = 'admin01' LIMIT 1;
INSERT INTO `app_config` (`config_key`, `config_value`, `updated_by`)
SELECT 'tools.search.wikipedia.user_agent', 'zlAI/1.0 (contact: tomchares0@gmail.com)', a.`id`
FROM `account` a WHERE a.`username` = 'admin01' LIMIT 1;
INSERT INTO `app_config` (`config_key`, `config_value`, `updated_by`)
SELECT 'tools.search.wikipedia.proxy_enabled', 'false', a.`id`
FROM `account` a WHERE a.`username` = 'admin01' LIMIT 1;
INSERT INTO `app_config` (`config_key`, `config_value`, `updated_by`)
SELECT 'tools.search.wikipedia.proxy_url', '', a.`id`
FROM `account` a WHERE a.`username` = 'admin01' LIMIT 1;

/* Default global rate limit config */
INSERT INTO `app_config` (`config_key`, `config_value`, `updated_by`)
SELECT 'rate_limit.global.enabled', 'true', a.`id`
FROM `account` a WHERE a.`username` = 'admin01' LIMIT 1;
INSERT INTO `app_config` (`config_key`, `config_value`, `updated_by`)
SELECT 'rate_limit.global.admin_bypass', 'true', a.`id`
FROM `account` a WHERE a.`username` = 'admin01' LIMIT 1;
INSERT INTO `app_config` (`config_key`, `config_value`, `updated_by`)
SELECT 'rate_limit.global.window_seconds', '60', a.`id`
FROM `account` a WHERE a.`username` = 'admin01' LIMIT 1;
INSERT INTO `app_config` (`config_key`, `config_value`, `updated_by`)
SELECT 'rate_limit.global.ip_limit', '300', a.`id`
FROM `account` a WHERE a.`username` = 'admin01' LIMIT 1;
INSERT INTO `app_config` (`config_key`, `config_value`, `updated_by`)
SELECT 'rate_limit.global.user_limit', '200', a.`id`
FROM `account` a WHERE a.`username` = 'admin01' LIMIT 1;
INSERT INTO `app_config` (`config_key`, `config_value`, `updated_by`)
SELECT 'rate_limit.global.whitelist_ips', '', a.`id`
FROM `account` a WHERE a.`username` = 'admin01' LIMIT 1;
INSERT INTO `app_config` (`config_key`, `config_value`, `updated_by`)
SELECT 'rate_limit.global.whitelist_paths', '/actuator,/swagger-ui,/v3/api-docs,/doc.html,/webjars,/h2-console,/error', a.`id`
FROM `account` a WHERE a.`username` = 'admin01' LIMIT 1;

/* Default OCR config */
INSERT INTO `app_config` (`config_key`, `config_value`, `updated_by`)
SELECT 'rag.ocr.enabled', 'true', a.`id`
FROM `account` a WHERE a.`username` = 'admin01' LIMIT 1;
INSERT INTO `app_config` (`config_key`, `config_value`, `updated_by`)
SELECT 'rag.ocr.max_images_per_request', '50', a.`id`
FROM `account` a WHERE a.`username` = 'admin01' LIMIT 1;
INSERT INTO `app_config` (`config_key`, `config_value`, `updated_by`)
SELECT 'rag.ocr.max_image_bytes', '5242880', a.`id`
FROM `account` a WHERE a.`username` = 'admin01' LIMIT 1;
INSERT INTO `app_config` (`config_key`, `config_value`, `updated_by`)
SELECT 'rag.ocr.max_pdf_pages', '8', a.`id`
FROM `account` a WHERE a.`username` = 'admin01' LIMIT 1;
INSERT INTO `app_config` (`config_key`, `config_value`, `updated_by`)
SELECT 'rag.ocr.rate_limit_per_day', '200', a.`id`
FROM `account` a WHERE a.`username` = 'admin01' LIMIT 1;
INSERT INTO `app_config` (`config_key`, `config_value`, `updated_by`)
SELECT 'rag.ocr.rate_limit_window_seconds', '86400', a.`id`
FROM `account` a WHERE a.`username` = 'admin01' LIMIT 1;
INSERT INTO `app_config` (`config_key`, `config_value`, `updated_by`)
SELECT 'rag.ocr.default_user_quota', '200', a.`id`
FROM `account` a WHERE a.`username` = 'admin01' LIMIT 1;
INSERT INTO `app_config` (`config_key`, `config_value`, `updated_by`)
SELECT 'openai.stream.enabled', 'false', a.`id`
FROM `account` a WHERE a.`username` = 'admin01' LIMIT 1;
