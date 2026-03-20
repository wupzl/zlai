# zlAI

zlAI 是一个面向多模型、多工作流场景的全栈 AI 应用平台，目标不是只做一个聊天页，而是把聊天、GPT、Agent、RAG、检索工具、计费和后台治理整合成一个可运行、可扩展、可演示的系统。

## 当前项目解决什么问题

它解决的是这样一类问题:

- 普通 LLM Chat 只能回答，但缺少会话管理、分支追问、计费和权限控制
- 仅有 Prompt 封装还不够，实际业务还需要 GPT 市场、Agent 技能编排、知识库检索增强
- 引入 RAG 和外部工具后，系统通常又会缺少治理能力，例如限流、日志、配置中心、审核、后台运营

这个项目把这些能力放在同一套前后端架构里，形成一个可以直接部署和继续开发的 AI 应用底座。

## 当前功能概览

### 用户侧

- 多会话聊天，支持流式和非流式消息
- 聊天分支切换，可在同一轮消息下查看不同回答分支
- 模型切换，支持为会话配置主模型和工具模型
- GPT 广场与个人 GPT 管理
- Agent 创建、技能选择、多 Agent 编排
- RAG 文档上传、文档查询、基于知识库创建聊天会话
- 用户资料维护、密码修改、登录态续期

### 管理侧

- 管理员登录与独立后台路由
- 用户管理，包含状态、余额、密码重置、导出
- GPT 管理与公开状态审核
- Agent 管理、导出、公开内容治理
- Skill 管理，可把底层工具封装成可复用技能
- RAG 文档管理
- 聊天会话与消息监控
- 登录日志、Token 日志、系统日志查询与导出
- 模型价格倍率管理与价格变更日志
- 搜索工具、全局限流、OCR、OpenAI 流式兼容等系统设置

### 平台能力

- 多模型适配层，当前代码已接入 DeepSeek、OpenAI 兼容接口、Claude、Mock
- RAG 文档入库，支持 PDF、DOC、DOCX、TXT、Markdown 上传
- Markdown + 引用图片的异步入库流程
- OCR 文本抽取与配额/限额控制
- pgvector 向量检索，支持 MMR、阈值过滤、TopK 配置
- 检索工具能力，支持 Bocha、Baidu、Wikipedia、Baike、Searx、SerpAPI 等配置项
- Token 余额与模型倍率计费
- 全局限流与聊天链路限流
- JWT + Cookie 认证配置
- Resilience4j 熔断、超时、重试保护

## 技术栈

- 前端: Vue 3、Vue Router、Vite、Vitest
- 后端: Spring Boot 3.5、Spring Security、Spring WebFlux、MyBatis-Plus
- 数据层: MySQL、Redis、PostgreSQL + pgvector
- AI 相关: Spring AI、OpenAI Compatible API、Tesseract OCR、Apache Tika、PDFBox
- 压测与评估: k6、RAG Eval 脚本

## 目录结构

```text
.
├─frontend/          # Vue 3 前端
├─backend/           # Spring Boot 后端
├─docs/              # 项目文档
├─test/              # k6 压测与 RAG 评估脚本
├─docker-compose.yml # 一键启动依赖与前后端
└─README.md
```

## 前端路由

### 用户端

- `/auth/user`
- `/app/chat`
- `/app/chat/:chatId`
- `/app/gpts`
- `/app/agents`
- `/app/rag`
- `/app/profile`
- `/app/gpt/:gptId/chat/:chatId?`
- `/app/agent/:agentId/chat/:chatId?`
- `/app/rag/chat/:chatId?`

### 管理端

- `/auth/admin`
- `/admin/dashboard`
- `/admin/users`
- `/admin/gpts`
- `/admin/agents`
- `/admin/skills`
- `/admin/rag`
- `/admin/chats`
- `/admin/logs`
- `/admin/pricing`
- `/admin/settings`

## 后端主要接口

### 认证与用户

- `POST /api/auth/register`
- `POST /api/auth/login`
- `PUT /api/user/update`
- `PUT /api/user/change-password`
- `POST /api/user/refresh`
- `GET /api/user/check`
- `POST /api/user/logout`

### 聊天

- `GET /api/chat/models/options`
- `GET /api/chat/models/pricing`
- `GET /api/chat/sessions`
- `POST /api/chat/session`
- `PUT /api/chat/session/{chatId}/title`
- `DELETE /api/chat/session/{chatId}`
- `GET /api/chat/{chatId}`
- `POST /api/chat/stream`
- `POST /api/chat/message`

### GPT

- `GET /api/gpts/public`
- `GET /api/gpts/public/{gptId}`
- `GET /api/gpts/mine`
- `GET /api/gpts/{gptId}`
- `POST /api/gpts`
- `PUT /api/gpts/{gptId}`
- `DELETE /api/gpts/{gptId}`

### Agent

- `GET /api/agents/tools`
- `GET /api/agents/skills`
- `POST /api/agents/tools/execute`
- `GET /api/agents/public`
- `GET /api/agents/mine`
- `GET /api/agents/{agentId}`
- `POST /api/agents`
- `PUT /api/agents/{agentId}`
- `DELETE /api/agents/{agentId}`

### RAG

- `POST /api/rag/ingest`
- `POST /api/rag/ingest/async`
- `POST /api/rag/ingest/markdown`
- `POST /api/rag/ingest/markdown-file`
- `POST /api/rag/ingest/markdown-upload/async`
- `POST /api/rag/ingest/file-upload/async`
- `GET /api/rag/ingest/tasks/{taskId}`
- `POST /api/rag/query`
- `GET /api/rag/documents`
- `DELETE /api/rag/documents/{docId}`
- `POST /api/rag/session`

### 管理后台

- `/api/admin/users`
- `/api/admin/gpts`
- `/api/admin/agents`
- `/api/admin/skills`
- `/api/admin/rag`
- `/api/admin/chats`
- `/api/admin/logs`
- `/api/admin/model-pricing`
- `/api/admin/settings`

## 本地开发依赖

- JDK 21
- Node.js 18+
- MySQL 8.0+，项目兼容 5.7.26+
- Redis 6+
- PostgreSQL 14+，并启用 `vector` 扩展
- 可选: Tesseract OCR

## 本地启动

### 1. 初始化数据库

MySQL:

```sql
CREATE DATABASE `zl-ai2` DEFAULT CHARACTER SET utf8mb4;
```

```powershell
mysql -u root -p<your_password> < backend/db/zlai.sql
mysql -u root -p<your_password> < backend/db/seed.sql
```

PostgreSQL:

```sql
CREATE DATABASE zl_ai_rag;
```

```sql
\c zl_ai_rag
CREATE EXTENSION IF NOT EXISTS vector;
```

```powershell
psql -U postgres -d zl_ai_rag -f backend/db/zlai_pg.sql
```

### 2. 配置环境

本地直启后端时，默认读取:

- `backend/.env.properties`
- 或项目根目录 `.env.properties`

Docker Compose 启动时，读取项目根目录:

- `.env`

可参考:

- `.env.example`
- `.env.properties`

### 3. 启动后端

```powershell
cd backend
mvn -DskipTests=true spring-boot:run
```

默认地址:

- `http://localhost:8080`

### 4. 启动前端

```powershell
cd frontend
npm install
npm run dev
```

默认地址:

- `http://localhost:5173`

## Docker 启动

推荐先复制环境模板:

```powershell
Copy-Item .env.example .env
```

然后填写至少以下配置:

- `JWT_ACCESS_SECRET`
- `JWT_REFRESH_SECRET`
- 至少一组可用的模型 API Key
- 如需 RAG，配置 embedding 相关变量

启动:

```powershell
docker compose up -d --build
```

默认服务:

- Frontend: `http://localhost:5173`
- Backend: `http://localhost:8080`
- MySQL: `localhost:3306`
- Redis: `localhost:6380`
- PostgreSQL: `localhost:5455`

## 关键配置项

### 模型与推理

- `APP_AI_DEEPSEEK_KEY`
- `APP_AI_DEEPSEEK_BASE_URL`
- `APP_AI_OPENAI_KEY`
- `APP_AI_OPENAI_BASE_URL`
- `APP_AI_CLAUDE_KEY`
- `APP_AI_CLAUDE_BASE_URL`
- `APP_AI_OPENAI_STREAM_ENABLED`
- `APP_LLM_MOCK_ENABLED` 或本地配置 `app.llm.mock-enabled=true`

### 数据库

- `SPRING_DATASOURCE_URL`
- `SPRING_DATASOURCE_USERNAME`
- `SPRING_DATASOURCE_PASSWORD`
- `SPRING_DATA_REDIS_HOST`
- `SPRING_DATA_REDIS_PORT`
- `SPRING_DATA_REDIS_PASSWORD`
- `APP_RAG_DATASOURCE_URL`
- `APP_RAG_DATASOURCE_USERNAME`
- `APP_RAG_DATASOURCE_PASSWORD`

### RAG 与 OCR

- `APP_RAG_EMBEDDING_BASE_URL`
- `APP_RAG_EMBEDDING_API_KEY`
- `APP_RAG_EMBEDDING_MODEL`
- `APP_RAG_OCR_ENABLED`
- `TESSDATA_PATH`
- `APP_RAG_OCR_TESSDATA_PATH`

### 搜索工具

- `APP_TOOLS_SEARCH_BOCHA_ENABLED`
- `APP_TOOLS_SEARCH_BOCHA_API_KEY`
- `APP_TOOLS_SEARCH_BAIDU_ENABLED`
- `APP_TOOLS_SEARCH_WIKIPEDIA_ENABLED`
- `APP_TOOLS_SEARCH_BAIKE_ENABLED`
- `APP_TOOLS_SEARCH_SEARX_ENABLED`
- `APP_TOOLS_SEARCH_SEARX_URL`
- `APP_TOOLS_SEARCH_SERPAPI_KEY`

### 认证与安全

- `APP_JWT_ACCESS_SECRET`
- `APP_JWT_REFRESH_SECRET`
- `APP_AUTH_COOKIES_SECURE`
- `APP_AUTH_COOKIES_SAME_SITE`
- `APP_AUTH_COOKIES_DOMAIN`
- `APP_CORS_ALLOWED_ORIGINS`
- `APP_CORS_ALLOWED_ORIGIN_PATTERNS`

## 默认账号

开发种子数据来自 `backend/db/seed.sql`:

- 管理员: `admin01 / 123456`
- 普通用户: `test01 / 123456`
- 普通用户: `test02 / 123456`
- 普通用户: `test03 / 123456`
- 普通用户: `wupzl / 123456`

新注册用户默认余额由以下配置决定:

- `app.user.default-token-balance`
- `APP_USER_DEFAULT_TOKEN_BALANCE`

## 测试与验证

### 后端测试

```powershell
cd backend
mvn -q -DskipTests=false test
```

### 前端测试

```powershell
cd frontend
npm test
```

### k6 压测

```powershell
k6 run test/k6-load.js -e MODEL=mock-chat
k6 run test/k6-mix.js -e MODEL=mock-chat
k6 run test/k6-rag.js
```

### RAG Eval

相关脚本位于:

- `test/rag-eval/normalize-study-resource.ps1`
- `test/rag-eval/generate-rag-eval-set.ps1`
- `test/rag-eval/run-rag-eval.ps1`

## 补充说明

- `prod` 配置下，Cookie 安全策略更严格，建议配合 HTTPS 使用
- 如果只做 Docker 同源部署，`VITE_API_BASE` 通常保持为空
- 管理后台可以在线配置搜索工具、OCR 限额、限流和模型价格倍率
- 项目内已有 Mock LLM、压测脚本和 RAG 评估脚本，便于做稳定性演示和面试展示
