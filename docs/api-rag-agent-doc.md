# zlAI-v2 接口文档 + RAG/Agent 设计说明

> 基于当前代码仓库 `D:\Code\zlAI-v2`（后端 Spring Boot）整理。  
> 目标：覆盖可用接口、关键参数、RAG 与 Agent 的具体实现流程与设计收益。

## 1. 基础约定

### 1.1 基础地址
- 本地后端默认：`http://localhost:8080`
- 所有 API 前缀均为 `/api/*`

### 1.2 统一响应结构
所有 JSON 接口统一返回：

```json
{
  "code": 200,
  "message": "success",
  "data": {},
  "timestamp": 1700000000000
}
```

- `code=200` 表示成功
- 非 `200` 表示失败（常见 `400/401/403/429/500`）

### 1.3 鉴权方式
- Header：`Authorization: Bearer <accessToken>`
- 默认除白名单外，全部需要登录
- 管理员接口通常要求 `ROLE_ADMIN`（`@PreAuthorize("hasRole('ADMIN')")`）

### 1.4 分页结构（PageResult）

```json
{
  "content": [],
  "totalElements": 100,
  "totalPages": 5,
  "pageNumber": 1,
  "pageSize": 20
}
```

### 1.5 SSE 流式输出（聊天）
`POST /api/chat/stream` 返回 `text/event-stream`，事件类型包括：
- `session_created`
- `message_chunk`
- `done`
- `error`

---

## 2. 认证与用户模块

## 2.1 接口总览

| 方法 | 路径 | 鉴权 | 说明 |
|---|---|---|---|
| POST | `/api/auth/register` | 否 | 用户注册 |
| POST | `/api/auth/login` | 否 | 用户登录 |
| PUT | `/api/user/update` | 是 | 更新用户资料 |
| PUT | `/api/user/change-password` | 是 | 修改密码 |
| POST | `/api/user/refresh` | 否 | 刷新 accessToken |
| POST | `/api/user/logout` | 否 | refreshToken 失效（登出） |
| POST | `/api/admin/auth/login` | 否 | 管理员登录 |

## 2.2 关键请求模型

### LoginRequest
```json
{
  "username": "admin01",
  "password": "123456",
  "deviceId": "optional-device-id"
}
```

### RegisterRequest
```json
{
  "username": "test01",
  "password": "123456",
  "nickname": "Test User",
  "avatarUrl": "https://..."
}
```

### ChangePasswordRequest
```json
{
  "currPassword": "old",
  "newPassword": "new",
  "confirmPassword": "new"
}
```

### RefreshTokenRequest
```json
{
  "refreshToken": "..."
}
```

## 2.3 关键响应模型

### LoginResponse
```json
{
  "accessToken": "...",
  "refreshToken": "...",
  "expiresIn": 7200,
  "tokenType": null,
  "userInfo": {
    "username": "admin01",
    "nickname": "admin",
    "avatarUrl": null,
    "balance": 100000,
    "role": "ADMIN",
    "last_password_change": "2026-03-01 10:00:00"
  },
  "loginTime": null
}
```

### TokenResponse
```json
{
  "accessToken": "..."
}
```

---

## 3. Chat 模块

## 3.1 接口总览

| 方法 | 路径 | 鉴权 | 说明 |
|---|---|---|---|
| GET | `/api/chat/{chatId}` | 是 | 获取会话历史详情 |
| GET | `/api/chat/models/options` | 是 | 获取可用模型列表 |
| GET | `/api/chat/models/pricing` | 是 | 获取模型倍率配置 |
| GET | `/api/chat/sessions?page=&size=` | 是 | 分页获取当前用户会话 |
| POST | `/api/chat/session?title=&model=&toolModel=` | 是 | 新建会话 |
| PUT | `/api/chat/session/{chatId}/title?title=` | 是 | 重命名会话 |
| DELETE | `/api/chat/session/{chatId}` | 是 | 删除会话 |
| POST | `/api/chat/stream` | 是 | 流式对话（SSE） |
| POST | `/api/chat/message` | 是 | 同步对话 |

## 3.2 ChatRequest（同步/流式共用）

```json
{
  "chatId": "optional",
  "prompt": "用户问题",
  "messageId": "optional",
  "parentMessageId": "optional",
  "regenerateFromAssistantMessageId": "optional",
  "gptId": "optional",
  "agentId": "optional",
  "useRag": true,
  "ragQuery": "optional",
  "ragTopK": 5,
  "model": "deepseek-chat",
  "toolModel": "gpt-4o-mini"
}
```

## 3.3 Chat 特殊规则
- `prompt` 不能为空，且长度限制 4000 字符
- 会话存在归属校验（用户只能访问自己的 chat）
- 先做 token 余额预估校验：`(promptTokens + maxCompletionTokens) * modelMultiplier`
- 会话上下文支持分支：`parentMessageId` 指定父节点
- 可能触发工具调用（Tool Call）二次追问流程
- 若会话开启 RAG 且未命中上下文，返回固定提示文案

---

## 4. GPT Store 模块

## 4.1 接口总览

| 方法 | 路径 | 鉴权 | 说明 |
|---|---|---|---|
| GET | `/api/gpts/public?page=&size=&keyword=&category=` | 否 | 公共 GPT 列表 |
| GET | `/api/gpts/public/{gptId}` | 否 | 公共 GPT 详情 |
| GET | `/api/gpts/mine?page=&size=` | 是 | 我的 GPT |
| GET | `/api/gpts/{gptId}` | 是 | GPT 详情（含权限判断） |
| POST | `/api/gpts` | 是 | 创建 GPT |
| PUT | `/api/gpts/{gptId}` | 是 | 更新 GPT |
| DELETE | `/api/gpts/{gptId}` | 是 | 删除 GPT |

## 4.2 GptUpsertRequest
```json
{
  "name": "Interview Copilot",
  "description": "Demo GPT for interview",
  "instructions": "You are ...",
  "model": "deepseek-chat",
  "avatarUrl": "https://...",
  "category": "interview",
  "requestPublic": true
}
```

---

## 5. Agent 模块

## 5.1 接口总览

| 方法 | 路径 | 鉴权 | 说明 |
|---|---|---|---|
| GET | `/api/agents/tools` | 是 | 查询可用工具定义 |
| POST | `/api/agents/tools/execute` | 是 | 手工执行工具 |
| GET | `/api/agents/public?page=&size=&keyword=` | 是 | 公共 Agent 列表 |
| GET | `/api/agents/mine?page=&size=` | 是 | 我的 Agent |
| GET | `/api/agents/{agentId}` | 是 | Agent 详情 |
| POST | `/api/agents` | 是 | 创建 Agent |
| PUT | `/api/agents/{agentId}` | 是 | 更新 Agent |
| DELETE | `/api/agents/{agentId}` | 是 | 删除 Agent |

## 5.2 AgentUpsertRequest
```json
{
  "name": "Research Manager",
  "description": "Multi-agent coordinator",
  "instructions": "You are a manager agent ...",
  "model": "deepseek-chat",
  "toolModel": "gpt-4o-mini",
  "tools": ["web_search", "datetime"],
  "requestPublic": false,
  "multiAgent": true,
  "teamAgentIds": ["agent-a", "agent-b"],
  "teamConfigs": [
    {
      "agentId": "agent-a",
      "role": "planner",
      "tools": ["web_search"]
    }
  ]
}
```

## 5.3 ToolExecutionRequest
```json
{
  "toolKey": "web_search",
  "input": "OpenAI latest announcements",
  "model": "gpt-4o-mini"
}
```

## 5.4 当前工具集合
- `calculator`：四则运算
- `datetime`：时区时间查询
- `translate`：翻译（支持 JSON 或纯文本）
- `summarize`：摘要
- `web_search`：搜索引擎聚合查询

---

## 6. RAG 模块

## 6.1 接口总览

| 方法 | 路径 | 鉴权 | 说明 |
|---|---|---|---|
| POST | `/api/rag/ingest` | 是 | 纯文本入库 |
| POST | `/api/rag/ingest/markdown` | 是 | Markdown 文本入库 |
| POST | `/api/rag/ingest/markdown-file` | 是 | 从服务器文件路径导入（需开关） |
| POST | `/api/rag/ingest/markdown-upload` | 是 | 上传 Markdown + 图片/Zip，支持 OCR |
| POST | `/api/rag/ingest/file-upload` | 是 | 上传 PDF/DOC/DOCX/TXT，含嵌入图 OCR |
| POST | `/api/rag/query` | 是 | RAG 查询 |
| GET | `/api/rag/documents?page=&size=` | 是 | 我的文档列表 |
| DELETE | `/api/rag/documents/{docId}` | 是 | 删除我的文档 |
| POST | `/api/rag/session?title=&model=&toolModel=` | 是 | 创建 RAG Chat 会话 |

## 6.2 RAG 请求模型

### RagIngestRequest
```json
{
  "title": "doc title",
  "content": "raw text"
}
```

### RagMarkdownIngestRequest
```json
{
  "title": "md title",
  "markdownContent": "# content",
  "sourcePath": "C:/docs/a.md"
}
```

### RagMarkdownFileIngestRequest
```json
{
  "title": "md title",
  "filePath": "D:/data/a.md"
}
```

### RagQueryRequest
```json
{
  "query": "问题",
  "topK": 5
}
```

## 6.3 上传接口规则（重点）

### `/api/rag/ingest/markdown-upload`
- 必传：`file`（`.md` / `.markdown`）
- 可选：`images`（多文件）、`imagesZip`（zip）
- 支持远程图片 URL 抓取（从 markdown 中解析），并做安全校验：
  - 仅 `http/https`
  - 拒绝本地环回/内网 IP（防 SSRF）
  - 有最大抓取数量、最大字节数、超时控制
- OCR 受 `OcrSettings` 和用户配额控制（管理员可豁免）

### `/api/rag/ingest/file-upload`
- 支持：`pdf/doc/docx/txt`
- 文本提取：Apache Tika
- 图片 OCR：对嵌入图片做 OCR
- PDF 场景：按页渲染后 OCR（页数受配置限制）

## 6.4 RAG 响应模型

### RagIngestResponse
```json
{
  "docId": "uuid"
}
```

### RagQueryResponse
```json
{
  "context": "...",
  "matches": [
    {
      "docId": "uuid",
      "content": "...",
      "score": 0.87
    }
  ]
}
```

---

## 7. Admin 模块

> 以下接口均需管理员权限（`ROLE_ADMIN`），除 `/api/admin/auth/login`。

## 7.1 用户管理 `/api/admin/users`

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/admin/users?page=&size=&keyword=` | 用户分页列表 |
| GET | `/api/admin/users/{id}` | 用户详情 |
| GET | `/api/admin/users/export?keyword=` | 导出 CSV |
| PUT | `/api/admin/users/{id}` | 更新用户基础字段 |
| PUT | `/api/admin/users/{id}/status?status=` | 修改状态 |
| PUT | `/api/admin/users/{id}/balance?delta=` | 调整余额 |
| PUT | `/api/admin/users/{id}/reset-password?newPassword=` | 重置密码 |
| DELETE | `/api/admin/users/{id}` | 删除用户 |

## 7.2 GPT 管理 `/api/admin/gpts`

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/admin/gpts?page=&size=&keyword=&requestPublic=` | GPT 分页 |
| GET | `/api/admin/gpts/{id}` | GPT 详情 |
| GET | `/api/admin/gpts/export?keyword=` | 导出 CSV |
| PUT | `/api/admin/gpts/{id}` | 更新 GPT |
| PUT | `/api/admin/gpts/{id}/public?isPublic=` | 审核/公开状态 |
| DELETE | `/api/admin/gpts/{id}` | 删除 GPT |

## 7.3 Agent 管理 `/api/admin/agents`

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/admin/agents?page=&size=&keyword=&requestPublic=` | Agent 分页 |
| GET | `/api/admin/agents/{agentId}` | Agent 详情 |
| GET | `/api/admin/agents/export?keyword=` | 导出 CSV |
| PUT | `/api/admin/agents/{agentId}` | 更新 Agent |
| DELETE | `/api/admin/agents/{agentId}` | 删除 Agent |

## 7.4 Chat 管理 `/api/admin/chats`

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/admin/chats/sessions?userId=&keyword=&page=&size=` | 会话分页 |
| GET | `/api/admin/chats/sessions/{chatId}` | 会话详情 |
| GET | `/api/admin/chats/sessions/export?userId=&keyword=` | 导出会话 CSV |
| GET | `/api/admin/chats/messages?chatId=&page=&size=` | 消息分页 |
| GET | `/api/admin/chats/messages/{messageId}` | 消息详情 |

## 7.5 日志管理 `/api/admin/logs`

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/admin/logs/login` | 登录日志 |
| GET | `/api/admin/logs/tokens` | token 消费日志 |
| GET | `/api/admin/logs/system` | 系统日志 |
| GET | `/api/admin/logs/system/count` | 系统日志计数 |
| GET | `/api/admin/logs/login/count` | 登录日志计数 |
| GET | `/api/admin/logs/tokens/count` | token 日志计数 |
| GET | `/api/admin/logs/count` | 总日志计数 |
| GET | `/api/admin/logs/export?type=all` | 导出日志 CSV |

## 7.6 RAG 管理 `/api/admin/rag`

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/admin/rag/documents?userId=&page=&size=` | 管理端文档列表 |
| DELETE | `/api/admin/rag/documents/{docId}` | 管理端删除文档 |

## 7.7 设置管理 `/api/admin/settings`

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/admin/settings/tools-search` | 获取搜索工具配置 |
| PUT | `/api/admin/settings/tools-search` | 更新搜索工具配置 |
| GET | `/api/admin/settings/rate-limit` | 获取全局限流配置 |
| PUT | `/api/admin/settings/rate-limit` | 更新全局限流配置 |
| GET | `/api/admin/settings/ocr` | 获取 OCR 配置 |
| PUT | `/api/admin/settings/ocr` | 更新 OCR 配置 |
| GET | `/api/admin/settings/openai-stream` | 获取 OpenAI 流式开关 |
| PUT | `/api/admin/settings/openai-stream` | 更新 OpenAI 流式开关 |

## 7.8 模型倍率管理 `/api/admin/model-pricing`

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/admin/model-pricing` | 模型倍率列表 |
| GET | `/api/admin/model-pricing/export` | 导出倍率 CSV |
| PUT | `/api/admin/model-pricing?model=&multiplier=` | 更新倍率 |
| GET | `/api/admin/model-pricing/logs?page=&size=&startTime=&endTime=` | 倍率变更日志 |
| GET | `/api/admin/model-pricing/logs/export?startTime=&endTime=` | 导出倍率日志 CSV |

---

## 8. RAG 具体实现流程、参数与设计收益

## 8.1 RAG 实现流程（按执行链路）

### A. 入库流程（文本/Markdown）
1. Controller 接收请求，校验用户与参数。
2. 调用 `RagService.ingest*`。
3. 在 `rag_document` 写入主文档（`docId`）。
4. 文本按 `chunkSize/chunkOverlap` 切片。
5. 每个 chunk 做 embedding。
6. chunk + embedding 写入 `rag_chunk`（pgvector）。

### B. Markdown 上传 + 图片 OCR 流程
1. 读取 markdown 正文。
2. 合并图片来源：`images` + `imagesZip` + markdown 远程图片 URL。
3. 对图片做 OCR，OCR 文本注入 markdown 对应位置（含未引用图片附录）。
4. 走普通 ingest 流程落库。
5. 非 admin 用户扣减 OCR 配额。

### C. 文档上传（PDF/DOC/DOCX/TXT）
1. Tika 提取正文。
2. 嵌入图片提取 + OCR。
3. PDF 额外按页渲染并 OCR。
4. 组装为增强文本，再落库。

### D. 查询流程
1. 计算 query embedding。
2. 优先向量检索（支持 MMR 策略）。
3. 按 `minScore` 过滤。
4. 如向量命中不足，走关键词 fallback（文档内容/块内容）。
5. 返回 `matches` 并构建 `context`。

### E. RAG + Chat 融合流程
1. `ChatServiceImpl.resolveRagContext` 判断是否启用 RAG（会话开关 + 请求开关）。
2. 将 RAG `context` 合并到 system prompt（`mergeSystemPrompt`）。
3. 若会话要求 RAG 但 context 为空，直接返回固定“无知识库命中”答复。

## 8.2 RAG 关键参数

### 业务接口参数
- `useRag`：本次对话是否启用 RAG
- `ragQuery`：显式检索问题（不传时使用 prompt）
- `ragTopK`：检索条数
- `title/content/markdownContent/sourcePath`：入库字段

### 应用配置参数（`app.rag.*`）
- `vector-size`：向量维度（Hash Embedding 回退时有效）
- `default-top-k`
- `chunk-size`
- `chunk-overlap`
- `search.strategy`：`mmr`/其他
- `search.min-score`
- `search.mmr-lambda`
- `search.mmr-candidate-multiplier`
- `datasource.url/username/password`：RAG PG 数据源

### OCR 参数（`app.rag.ocr.*` + 管理后台动态配置）
- `enabled`
- `max-images-per-request`
- `max-image-bytes`
- `max-pdf-pages`
- `default-user-quota`
- `rate-limit-per-day`
- `rate-limit-window-seconds`

### 远程图片安全参数
- `app.rag.remote-images.max-count`
- `app.rag.remote-images.max-bytes`
- `app.rag.remote-images.connect-timeout-ms`
- `app.rag.remote-images.read-timeout-ms`

## 8.3 这么设计的好处
- 检索鲁棒：向量检索 + 关键词兜底，减少“明明有文档却答不出”。
- 安全可控：远程图片下载带 SSRF 防护与大小/超时限制。
- 成本可控：OCR 有用户配额和频率限制，避免被滥用。
- 可运维：OCR/搜索/限流参数可通过管理接口动态调优，不必重启发版。
- 用户体验一致：RAG 命中不足时有确定性返回，不会胡编。

---

## 9. Agent 具体实现流程、参数与设计收益

## 9.1 Agent 配置与存储模型

Agent 核心字段：
- `agentId/name/description/instructions/model/toolModel`
- `tools`：工具 key 列表（JSON 字符串存储）
- `multiAgent`：是否启用多 Agent 编排
- `teamAgentIds`：团队成员 id 列表
- `teamConfig`：团队成员细粒度配置（角色、工具）
- `isPublic/requestPublic`

## 9.2 Agent 运行流程（单 Agent）

1. Chat 会话绑定 agent（创建会话时或会话已有 `agentId`）。
2. 系统提示词由 `agent.instructions` 构造，并追加工具声明（可调用工具及 JSON 约束）。
3. 模型输出后进入工具解析流程：
   - 若输出为 JSON tool call，解析并执行工具。
   - 若未显式 tool call，但用户意图匹配（时间/搜索），可触发意图工具。
4. 工具结果回注二次追问，让模型生成最终自然语言答案。
5. 记录消息、token 消耗与工具调用消耗（按 `toolModel` 或默认模型计费）。

## 9.3 Agent 运行流程（多 Agent）

当 `multiAgent=true`：
1. 解析团队成员（`teamConfig` 或 `teamAgentIds`）。
2. 每个成员按自身角色/工具配置并行运行。
3. 编排器可进行“强制工具执行”（例如搜索/时间类问题）。
4. 收集各成员输出后，由 Manager Agent 聚合为最终答案。
5. 流式场景下同样支持团队模式并最后汇聚输出。

> 额外：若团队为空，会回落到 Planner/Researcher/Critic 的三角色并行模式再聚合。

## 9.4 Agent 关键参数

### 接口参数（创建/更新）
- `tools: List<String>`：必须在工具注册表中存在
- `toolModel`：工具调用计费模型（可独立于对话模型）
- `multiAgent`
- `teamAgentIds`
- `teamConfigs[].agentId/role/tools`

### 运行时参数（配置）
- `app.agents.executor.core-pool-size`
- `app.agents.executor.max-pool-size`
- `app.agents.executor.queue-capacity`
- `app.agents.executor.await-termination-seconds`
- `app.agents.multiagent-timeout-seconds`
- `app.tools.search.*`（工具搜索配置，支持动态更新）

## 9.5 这么设计的好处
- 能力解耦：对话模型与工具模型可拆分，成本与质量可分别优化。
- 扩展简单：新增工具只需实现 `AgentTool` + 执行逻辑并注册。
- 可靠性更高：工具失败有 fallback（搜索兜底、无结果策略）。
- 并行提效：多 Agent 并发执行，复杂任务可分工协作后聚合。
- 权限清晰：私有/公开 Agent 边界明确，团队成员访问也做权限校验。

---

## 10. 常见对接示例

## 10.1 同步聊天

```bash
curl -X POST "http://localhost:8080/api/chat/message" \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{
    "chatId":"<existing-chat-id>",
    "prompt":"帮我总结这段内容",
    "useRag":true,
    "ragTopK":5,
    "model":"deepseek-chat"
  }'
```

## 10.2 RAG 文本入库

```bash
curl -X POST "http://localhost:8080/api/rag/ingest" \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{
    "title":"产品手册",
    "content":"这里是文档正文..."
  }'
```

## 10.3 创建多 Agent

```bash
curl -X POST "http://localhost:8080/api/agents" \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{
    "name":"Research Manager",
    "instructions":"你是经理代理，负责汇总团队结论。",
    "model":"deepseek-chat",
    "toolModel":"gpt-4o-mini",
    "multiAgent":true,
    "teamConfigs":[
      {"agentId":"agent-a","role":"planner","tools":["web_search"]},
      {"agentId":"agent-b","role":"critic","tools":["datetime"]}
    ]
  }'
```

---

## 11. 备注

- 本文档按当前源码行为整理，若后续接口签名/字段变更，请同步更新。
- 管理端多个 `export` 接口返回 `text/csv`（文件下载），非统一 JSON 包装。
- 生产环境建议在网关层统一补充接口级审计、幂等约束、DTO 参数校验与 OpenAPI 自动生成。

