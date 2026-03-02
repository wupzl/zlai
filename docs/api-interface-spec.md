# zlAI-v2 接口文档（详细版）

本文档根据当前后端代码（Spring Boot）整理，面向前后端联调、测试和接入方。

## 1. 通用约定

### 1.1 Base URL
- 本地默认：`http://localhost:8080`
- 统一前缀：`/api`

### 1.2 认证方式
- 头部：`Authorization: Bearer <accessToken>`
- 除白名单外默认都需要登录。
- 白名单（无需 token）：
  - `/api/auth/**`
  - `/api/admin/auth/**`
  - `/api/user/refresh`
  - `/api/user/logout`
  - `/api/gpts/public/**`

### 1.3 统一返回结构

```json
{
  "code": 200,
  "message": "success",
  "data": {},
  "timestamp": 1700000000000
}
```

### 1.4 分页结构

```json
{
  "content": [],
  "totalElements": 0,
  "totalPages": 0,
  "pageNumber": 1,
  "pageSize": 20
}
```

### 1.5 常见错误码
- `400` 参数错误/业务校验失败
- `401` 未登录或 token 无效
- `403` 权限不足
- `404` 资源不存在
- `429` 频率限制（如 OCR 速率限制）
- `500` 系统错误
- `501` 功能未实现（少数接口保留）

### 1.6 流式返回（SSE）
- 接口：`POST /api/chat/stream`
- `Content-Type: text/event-stream`
- 事件类型：
  - `session_created`（新会话自动创建时）
  - `message_chunk`（模型分片输出）
  - `done`（结束）
  - `error`（失败）

## 2. 认证与用户

### 2.1 注册
- `POST /api/auth/register`
- 鉴权：否
- Body：

```json
{
  "username": "string",
  "password": "string",
  "nickname": "string",
  "avatarUrl": "string"
}
```

- `data`：`UserInfoVO`

### 2.2 用户登录
- `POST /api/auth/login`
- 鉴权：否
- Body：

```json
{
  "username": "string",
  "password": "string",
  "deviceId": "optional"
}
```

- `data`：`LoginResponse`

### 2.3 管理员登录
- `POST /api/admin/auth/login`
- 鉴权：否
- Body 同普通登录，要求用户角色为 `ADMIN`
- `data`：`LoginResponse`

### 2.4 刷新 token
- `POST /api/user/refresh`
- 鉴权：否
- Body：

```json
{
  "refreshToken": "string"
}
```

- `data`：

```json
{
  "accessToken": "string"
}
```

### 2.5 退出登录
- `POST /api/user/logout`
- 鉴权：否
- Body：

```json
{
  "refreshToken": "string"
}
```

- `data`：`boolean`

### 2.6 更新用户信息
- `PUT /api/user/update`
- 鉴权：是
- Body：

```json
{
  "nickname": "optional",
  "avatarUrl": "optional"
}
```

- 约束：至少传一个字段，否则返回错误
- `data`：`boolean`

### 2.7 修改密码
- `PUT /api/user/change-password`
- 鉴权：是
- Body：

```json
{
  "currPassword": "string",
  "newPassword": "string",
  "confirmPassword": "string"
}
```

- 约束：新密码与确认密码必须一致
- `data`：`boolean`

### 2.8 用户核心响应模型

`LoginResponse`:
- `accessToken: string`
- `refreshToken: string`
- `expiresIn: number`
- `tokenType: string | null`
- `userInfo: UserInfoVO`
- `loginTime: datetime | null`

`UserInfoVO`:
- `username: string`
- `nickname: string`
- `avatarUrl: string`
- `balance: number`
- `role: USER | ADMIN`
- `last_password_change: yyyy-MM-dd HH:mm:ss`

## 3. Chat 模块

### 3.1 会话与历史

1. `GET /api/chat/{chatId}`
- 鉴权：是
- Path：`chatId`
- 返回结构（`data`）：
  - `chatId`
  - `currentMessageId`
  - `gptId`
  - `agentId`
  - `model`
  - `toolModel`
  - `ragEnabled`
  - `messages: Message[]`

2. `GET /api/chat/sessions?page=1&size=20`
- 鉴权：是
- Query：
  - `page` 默认 `1`
  - `size` 默认 `20`，服务端上限 `50`
- `data`：`PageResult<ChatSessionVO>`

3. `POST /api/chat/session?title=&model=&toolModel=`
- 鉴权：是
- Query（可选）：
  - `title`
  - `model`
  - `toolModel`
- `data`：`ChatSessionVO`

4. `PUT /api/chat/session/{chatId}/title?title=`
- 鉴权：是
- `data`：`"success"` 或错误信息

5. `DELETE /api/chat/session/{chatId}`
- 鉴权：是
- `data`：`"success"` 或错误信息

### 3.2 模型信息

1. `GET /api/chat/models/options`
- 鉴权：是
- `data`：`string[]`

2. `GET /api/chat/models/pricing`
- 鉴权：是
- `data`：`ModelPricingVO[]`

### 3.3 发消息（同步）

- `POST /api/chat/message`
- 鉴权：是
- Body：`ChatRequest`

```json
{
  "chatId": "required-in-sync-mode",
  "prompt": "用户问题",
  "messageId": "optional",
  "parentMessageId": "optional",
  "gptId": "optional",
  "agentId": "optional",
  "useRag": true,
  "ragQuery": "optional",
  "ragTopK": 5,
  "model": "optional",
  "toolModel": "optional"
}
```

- 约束：
  - `prompt` 不能为空，长度 <= `4000`
  - `chatId` 在同步接口中必须已存在（否则会话不存在）
  - 先做余额预估：`(promptTokens + maxCompletionTokens) * modelMultiplier`
- 返回：
  - 正常：字符串答案（或 warning + 答案）
  - 开启 RAG 且未命中上下文：固定提示文本

### 3.4 发消息（流式）

- `POST /api/chat/stream`
- 鉴权：是
- Body：`ChatRequest`

```json
{
  "chatId": "optional",
  "prompt": "用户问题",
  "regenerateFromAssistantMessageId": "optional",
  "parentMessageId": "optional",
  "gptId": "optional",
  "agentId": "optional",
  "useRag": true,
  "ragQuery": "optional",
  "ragTopK": 5,
  "model": "optional",
  "toolModel": "optional"
}
```

- 特性：
  - `chatId` 为空时自动创建新会话，先发 `session_created`
  - 支持通过 `regenerateFromAssistantMessageId` 重新生成
  - 事件：`message_chunk`/`done`/`error`
  - 超时配置：`app.chat.stream-timeout-seconds`（默认 `90`）

### 3.5 ChatRequest 字段说明
- `chatId`：会话 ID
- `prompt`：用户输入
- `messageId`：用户消息 ID（可自定义）
- `parentMessageId`：分支上下文锚点
- `regenerateFromAssistantMessageId`：重生目标消息 ID（仅流式）
- `gptId`：绑定 GPT
- `agentId`：绑定 Agent
- `useRag`：是否使用 RAG
- `ragQuery`：显式检索词，不传时使用 `prompt`
- `ragTopK`：检索数量
- `model`：对话模型
- `toolModel`：工具调用模型

## 4. GPT Store

1. `GET /api/gpts/public?page=&size=&keyword=&category=`
- 鉴权：否
- `data`：`PageResult<Gpt>`

2. `GET /api/gpts/public/{gptId}`
- 鉴权：否
- `data`：`Gpt`

3. `GET /api/gpts/mine?page=&size=`
- 鉴权：是
- `data`：`PageResult<Gpt>`

4. `GET /api/gpts/{gptId}`
- 鉴权：是
- `data`：`Gpt`（含权限校验）

5. `POST /api/gpts`
- 鉴权：是
- Body：`GptUpsertRequest`
- `data`：`Gpt`

6. `PUT /api/gpts/{gptId}`
- 鉴权：是
- Body：`GptUpsertRequest`
- `data`：`Gpt`

7. `DELETE /api/gpts/{gptId}`
- 鉴权：是
- `data`：`boolean`

`GptUpsertRequest`:
- `name: string`
- `description?: string`
- `instructions?: string`
- `model?: string`
- `avatarUrl?: string`
- `category?: string`
- `requestPublic?: boolean`

## 5. Agent

### 5.1 工具相关

1. `GET /api/agents/tools`
- 鉴权：是
- `data`：`AgentToolDefinition[]`

2. `POST /api/agents/tools/execute`
- 鉴权：是
- Body：

```json
{
  "toolKey": "web_search",
  "input": "OpenAI latest announcements",
  "model": "optional"
}
```

- `data`：`ToolExecutionResult`
- 服务端执行架构（当前实现）：
  - `DefaultToolExecutor`：校验并分发
  - `BasicToolHandler`：`calculator/datetime`
  - `LlmTextToolHandler`：`translate/summarize`
  - `WebSearchToolHandler`：`web_search`

### 5.2 Agent 资源管理

1. `GET /api/agents/public?page=&size=&keyword=`
- 鉴权：是
- `data`：`PageResult<AgentVO>`

2. `GET /api/agents/mine?page=&size=`
- 鉴权：是
- `data`：`PageResult<AgentVO>`

3. `GET /api/agents/{agentId}`
- 鉴权：是
- `data`：`AgentVO`

4. `POST /api/agents`
- 鉴权：是
- Body：`AgentUpsertRequest`
- `data`：`AgentVO`

5. `PUT /api/agents/{agentId}`
- 鉴权：是
- Body：`AgentUpsertRequest`（局部更新）
- `data`：`AgentVO`

6. `DELETE /api/agents/{agentId}`
- 鉴权：是
- `data`：`boolean`

`AgentUpsertRequest`:
- `name: string`（创建必填）
- `description?: string`
- `instructions: string`（创建必填）
- `model?: string`
- `toolModel?: string`
- `tools?: string[]`（必须在注册表中存在）
- `requestPublic?: boolean`
- `multiAgent?: boolean`
- `teamAgentIds?: string[]`
- `teamConfigs?: TeamAgentConfig[]`

`TeamAgentConfig`:
- `agentId: string`
- `role?: string`
- `tools?: string[]`

## 6. RAG

### 6.1 入库

1. `POST /api/rag/ingest`
- 鉴权：是
- Body：

```json
{
  "title": "optional",
  "content": "required"
}
```

- `data`：`{ "docId": "uuid" }`

2. `POST /api/rag/ingest/markdown`
- 鉴权：是
- Body：

```json
{
  "title": "optional",
  "markdownContent": "required",
  "sourcePath": "optional"
}
```

- `data`：`{ "docId": "uuid" }`

3. `POST /api/rag/ingest/markdown-file`
- 鉴权：是
- Body：

```json
{
  "title": "optional",
  "filePath": "required"
}
```

- 前置：`app.rag.ingest-filepath-enabled=true`
- `data`：`{ "docId": "uuid" }`

4. `POST /api/rag/ingest/markdown-upload`
- 鉴权：是
- `multipart/form-data`：
  - `file`（必填，`.md/.markdown`）
  - `images`（可选，多文件）
  - `imagesZip`（可选，zip）
- 约束：
  - zip 最大 300MB
  - OCR 开启时受 `maxImagesPerRequest/maxImageBytes` 等限制
  - 非管理员受 OCR 配额与频率限制
- `data`：`{ "docId": "uuid" }`

5. `POST /api/rag/ingest/file-upload`
- 鉴权：是
- `multipart/form-data`：
  - `file`（必填，支持 `pdf/doc/docx/txt`）
- 行为：Tika 提取正文 + 嵌入图 OCR + PDF 页级 OCR
- `data`：`{ "docId": "uuid" }`

### 6.2 查询与文档管理

1. `POST /api/rag/query`
- 鉴权：是
- Body：

```json
{
  "query": "required",
  "topK": 5
}
```

- `data`：

```json
{
  "context": "string",
  "matches": [
    {
      "docId": "string",
      "content": "string",
      "score": 0.83
    }
  ]
}
```

2. `GET /api/rag/documents?page=&size=`
- 鉴权：是
- `data`：`PageResult<RagDocumentSummary>`

3. `DELETE /api/rag/documents/{docId}`
- 鉴权：是
- `data`：`boolean`

4. `POST /api/rag/session?title=&model=&toolModel=`
- 鉴权：是
- 作用：创建 `ragEnabled=true` 的聊天会话
- `data`：`ChatSessionVO`

## 7. 管理端接口（ROLE_ADMIN）

说明：除 `/api/admin/auth/login` 外，均要求 `ROLE_ADMIN`。

### 7.1 用户管理 `/api/admin/users`
- `GET /api/admin/users?page=&size=&keyword=`
- `GET /api/admin/users/{id}`
- `GET /api/admin/users/export?keyword=`（CSV）
- `PUT /api/admin/users/{id}`（Body: `AdminUserUpdateRequest`）
- `PUT /api/admin/users/{id}/status?status=`
- `PUT /api/admin/users/{id}/balance?delta=`
- `PUT /api/admin/users/{id}/reset-password?newPassword=`
- `DELETE /api/admin/users/{id}`

### 7.2 GPT 管理 `/api/admin/gpts`
- `GET /api/admin/gpts?page=&size=&keyword=&requestPublic=`
- `GET /api/admin/gpts/{id}`
- `GET /api/admin/gpts/export?keyword=`（CSV）
- `PUT /api/admin/gpts/{id}`（Body: `GptUpsertRequest`）
- `PUT /api/admin/gpts/{id}/public?isPublic=`
- `DELETE /api/admin/gpts/{id}`

### 7.3 Agent 管理 `/api/admin/agents`
- `GET /api/admin/agents?page=&size=&keyword=&requestPublic=`
- `GET /api/admin/agents/{agentId}`
- `GET /api/admin/agents/export?keyword=`（CSV）
- `PUT /api/admin/agents/{agentId}`（Body: `AgentUpsertRequest`）
- `DELETE /api/admin/agents/{agentId}`

### 7.4 Chat 管理 `/api/admin/chats`
- `GET /api/admin/chats/sessions?userId=&keyword=&page=&size=`
- `GET /api/admin/chats/sessions/{chatId}`
- `GET /api/admin/chats/sessions/export?userId=&keyword=`（CSV）
- `GET /api/admin/chats/messages?chatId=&page=&size=`
- `GET /api/admin/chats/messages/{messageId}`

### 7.5 日志管理 `/api/admin/logs`
- `GET /api/admin/logs/login?userId=&startTime=&endTime=&page=&size=`
- `GET /api/admin/logs/tokens?userId=&startTime=&endTime=&page=&size=`
- `GET /api/admin/logs/system?userId=&startTime=&endTime=&page=&size=`
- `GET /api/admin/logs/system/count?userId=&startTime=&endTime=`
- `GET /api/admin/logs/login/count?userId=&startTime=&endTime=`
- `GET /api/admin/logs/tokens/count?userId=&startTime=&endTime=`
- `GET /api/admin/logs/count?userId=&startTime=&endTime=`
- `GET /api/admin/logs/export?type=all&userId=&startTime=&endTime=`（CSV）

### 7.6 RAG 管理 `/api/admin/rag`
- `GET /api/admin/rag/documents?userId=&page=&size=`
- `DELETE /api/admin/rag/documents/{docId}`

### 7.7 系统设置 `/api/admin/settings`
- `GET /api/admin/settings/tools-search`
- `PUT /api/admin/settings/tools-search`（Body: `ToolSearchSettingsRequest`）
- `GET /api/admin/settings/rate-limit`
- `PUT /api/admin/settings/rate-limit`（Body: `GlobalRateLimitSettingsRequest`）
- `GET /api/admin/settings/ocr`
- `PUT /api/admin/settings/ocr`（Body: `OcrSettingsRequest`）
- `GET /api/admin/settings/openai-stream`
- `PUT /api/admin/settings/openai-stream`（Body: `OpenAiStreamSettingsRequest`）

### 7.8 模型倍率 `/api/admin/model-pricing`
- `GET /api/admin/model-pricing`
- `GET /api/admin/model-pricing/export`（CSV）
- `PUT /api/admin/model-pricing?model=&multiplier=`
- `GET /api/admin/model-pricing/logs?page=&size=&startTime=&endTime=`
- `GET /api/admin/model-pricing/logs/export?startTime=&endTime=`（CSV）

## 8. 关键数据模型

`ChatSessionVO`:
- `chatId`
- `title`
- `model`
- `toolModel`
- `messageCount`
- `lastActiveTime`
- `ragEnabled`
- `gptId`
- `agentId`

`AgentVO`:
- `agentId`
- `name`
- `description`
- `instructions`
- `model`
- `toolModel`
- `userId`
- `tools`
- `multiAgent`
- `teamAgentIds`
- `teamConfigs`
- `isPublic`
- `requestPublic`
- `createdAt`
- `updatedAt`

`RagDocumentSummary`:
- `docId`
- `title`
- `createdAt`
- `updatedAt`

`ToolExecutionResult`:
- `success`
- `output`
- `error`
- `model`
- `promptTokens`
- `completionTokens`
