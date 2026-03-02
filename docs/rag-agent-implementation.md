# zlAI-v2 RAG 与 Agent 实现说明

本文档基于当前代码实现，描述 RAG、Agent 的执行流程、关键参数与设计收益，并给出可改进项。

## 1. 总览

系统在 `ChatServiceImpl` 中把 RAG、Agent、Tool Calling 三条能力链路融合：
- RAG：文档入库、向量检索、关键词兜底、上下文拼接。
- Agent：单 Agent/多 Agent 编排，角色分工，工具调用与聚合。
- Tool Calling：模型 JSON 工具调用 + 意图触发 + 回填追问。

工具执行层当前结构：
- `DefaultToolExecutor`：只负责工具 key 校验与分发。
- `BasicToolHandler`：处理 `calculator/datetime`。
- `LlmTextToolHandler`：处理 `translate/summarize`。
- `WebSearchToolHandler`：处理 `web_search`（多搜索源聚合与回退）。

主调用链：
- 同步：`POST /api/chat/message -> ChatServiceImpl.sendMessage`
- 流式：`POST /api/chat/stream -> ChatServiceImpl.chat`（SSE）

## 2. RAG 实现流程

### 2.1 入库流程（纯文本/Markdown）

入口：
- `POST /api/rag/ingest`
- `POST /api/rag/ingest/markdown`
- `RagServiceImpl.ingest*`

步骤：
1. 校验用户与输入内容（空内容直接 400）。
2. 创建文档主记录 `rag_document`（生成 `docId`）。
3. 以 `chunkSize/chunkOverlap` 分片（最小安全值：`chunkSize>=200`，步长最小 `50`）。
4. 对每个 chunk 调用 `EmbeddingService.embed`。
5. chunk + embedding 写入 `rag_chunk`（pgvector）。

### 2.2 Markdown + 图片 OCR 入库流程

入口：
- `POST /api/rag/ingest/markdown-upload`

步骤：
1. 读取 markdown 文件内容。
2. 汇总图片来源：
   - 表单 `images[]`
   - `imagesZip`
   - markdown 中远程图片 URL（`http/https`）
3. 安全过滤：
   - 拒绝内网/回环地址（防 SSRF）
   - 限制远程下载数量、字节、超时
4. OCR 提取：
   - 对图片逐个 OCR
   - OCR 文本尽量插入图片引用后
   - 未引用图片追加到附录区
5. 进入通用 ingest 分片 + embedding + 落库流程。
6. 非管理员按 OCR 张数扣减用户 OCR 配额。

### 2.3 文档上传（PDF/DOC/DOCX/TXT）流程

入口：
- `POST /api/rag/ingest/file-upload`

步骤：
1. 通过 Apache Tika 提取正文文本。
2. 抽取嵌入图片，逐张 OCR。
3. 若是 PDF，按页渲染图片后 OCR（页数受配置限制）。
4. 合并提取文本 + OCR 文本，进入通用 ingest。

### 2.4 查询流程

入口：
- `POST /api/rag/query`
- `RagServiceImpl.search/buildContext`

步骤：
1. query embedding。
2. 向量检索：
   - 默认策略 `mmr`
   - 候选池大小：`topK * mmrCandidateMultiplier`
3. 过滤低分结果（`minScore`）。
4. 若向量检索结果弱或关键词未命中，走关键词 fallback：
   - chunk/content 的 ILIKE 检索
   - 仍不足时走内存兜底（近期文档/分片扫描）
5. 构建 `context` 返回给调用方。

### 2.5 Chat 融合流程

入口：
- `ChatServiceImpl.resolveRagContext`

逻辑：
1. 判定本次是否启用 RAG：
   - 优先请求参数 `useRag`
   - 否则使用会话 `ragEnabled`
2. 检索词：
   - 优先 `ragQuery`
   - 否则使用用户 `prompt`
3. 得到 `ragContext` 后与系统提示词合并（`mergeSystemPrompt`）。
4. 若会话要求 RAG 且无上下文命中，直接返回固定回复：
   - `No relevant context found in the knowledge base. Please refine your question or add documents.`

## 3. RAG 关键参数

### 3.1 API 参数
- `useRag: boolean`
- `ragQuery: string`
- `ragTopK: int`
- `query/topK`（RAG query 接口）
- 上传类接口中的 `file/images/imagesZip`

### 3.2 配置参数（`app.rag.*`）
- `vector-size`（默认 `1536`）
- `default-top-k`（默认 `5`）
- `chunk-size`（默认 `800`）
- `chunk-overlap`（默认 `100`）
- `search.strategy`（默认 `mmr`）
- `search.min-score`（默认 `0.2`）
- `search.mmr-lambda`（默认 `0.7`）
- `search.mmr-candidate-multiplier`（默认 `4`）
- `datasource.url/username/password`（RAG Postgres）
- `embedding.base-url/api-key/model`

### 3.3 OCR 参数（`app.rag.ocr.*` 与后台动态设置）
- `enabled`（默认 `true`）
- `tessdata-path`
- `language`（默认 `eng+chi_sim`）
- `default-user-quota`（默认 `200`）
- `max-images-per-request`（默认 `50`）
- `max-image-bytes`（默认 `5242880`）
- `max-pdf-pages`（默认 `8`）
- `rate-limit-per-day`（默认 `200`）
- `rate-limit-window-seconds`（默认 `86400`）

### 3.4 远程图片安全参数
- `app.rag.remote-images.max-count`（默认 `20`）
- `app.rag.remote-images.max-bytes`（默认 `5242880`）
- `app.rag.remote-images.connect-timeout-ms`（默认 `5000`）
- `app.rag.remote-images.read-timeout-ms`（默认 `8000`）

## 4. Agent 实现流程

### 4.1 Agent 配置与创建

入口：
- `POST /api/agents`
- `PUT /api/agents/{agentId}`
- `AgentServiceImpl`

关键逻辑：
1. 校验必填：
   - 创建时 `name/instructions` 必填
2. 校验工具合法性：
   - 必须存在于 `AgentToolRegistry`
3. 校验团队成员可见性：
   - 私有成员需本人或管理员可访问
4. 存储字段：
   - `tools/teamAgentIds/teamConfig` 以 JSON 存储
5. 发布审核：
   - 管理员可直接 `isPublic=true`
   - 普通用户走 `requestPublic=true`

### 4.2 单 Agent 运行流程

入口：
- `ChatServiceImpl.sendMessage/chat/regenerateAssistant`

步骤：
1. 解析会话绑定的 `agentId`，注入 `agent.instructions` 到系统提示词。
2. 若 agent 配了工具，附加“工具声明 + JSON 调用规范”。
3. 模型返回后进入 `handleToolCallIfNeeded`：
   - 优先解析显式 JSON tool call（`{"tool":"...","input":"..."}`）
   - 否则执行意图触发（时间/搜索类）
4. 执行工具后，用 `Tool result` 二次追问模型，生成最终自然语言答案。
5. 记录 token 消耗与工具消耗（可单独走 `toolModel` 计费）。

### 4.3 多 Agent 运行流程

入口：
- `multiAgent=true`
- `MultiAgentOrchestrator.runTeam/streamTeam`

步骤：
1. 解析团队成员：
   - 优先 `teamConfig`
   - 否则 `teamAgentIds`
2. 并发执行成员任务（线程池 `agentExecutor`）：
   - 每个成员可有独立 role 和 tools
   - 支持强制工具调用（搜索/时间类）
3. 收集各成员输出并拼装给 Manager Agent。
4. Manager 统一聚合成最终答案。
5. 流式时同样先并发收敛，再由 Manager 流式输出。

说明：
- 团队为空时，回退到内置 Planner/Researcher/Critic 三角色并发模式。

### 4.4 Tool Calling 执行细节

执行入口与职责：
- `DefaultToolExecutor.execute` 做统一分发，不再承载具体工具实现。
- 具体逻辑下沉到 `ToolHandler` 实现类，按工具类别隔离。

1. JSON 解析：
   - 支持纯 JSON 与 fenced code block JSON
2. 权限：
   - 只允许当前 agent 配置中的工具
3. 搜索失败兜底：
   - 若输出为空/无结果，按策略尝试 fallback
   - 对时效性问题返回明确不可回答提示，防止编造
4. 二次追问约束：
   - 要求“基于工具结果回答，不要再调工具”

## 5. Agent 关键参数

### 5.1 API 参数

`AgentUpsertRequest`:
- `name`
- `description`
- `instructions`
- `model`
- `toolModel`
- `tools`
- `requestPublic`
- `multiAgent`
- `teamAgentIds`
- `teamConfigs[].agentId/role/tools`

`ToolExecutionRequest`:
- `toolKey`
- `input`
- `model`（可覆盖默认工具模型）

### 5.2 运行配置（`app.agents.*`）
- `executor.core-pool-size`（默认 `10`）
- `executor.max-pool-size`（默认 `50`）
- `executor.queue-capacity`（默认 `100`）
- `executor.await-termination-seconds`（默认 `30`）
- `multiagent-timeout-seconds`（默认 `20`）

### 5.3 Chat 相关参数（影响 Agent）
- `app.chat.context.window-messages`（默认 `20`）
- `app.chat.context.warn-messages`（默认 `50`）
- `app.chat.billing.*`（模型倍率、消息窗口、completion 限额）

## 6. 这么设计的好处

### 6.1 RAG 设计收益
- 检索稳健：向量检索 + 关键词 fallback，降低“有资料但答不出”概率。
- 上下文可控：`minScore/MMR` 防止把噪声片段带入回答。
- 安全性：远程图片下载做 SSRF 防护与资源配额限制。
- 成本可控：OCR 有额度和速率双限流，避免滥用。
- 用户体验稳定：RAG 未命中时返回明确结果，不让模型硬编。

### 6.2 Agent 设计收益
- 能力解耦：对话模型与工具模型分离，质量与成本可独立调优。
- 扩展方便：新增工具仅需实现 `AgentTool` + `ToolExecutor` 分支。
- 并发提效：多 Agent 并行分工，复杂任务响应更快。
- 质量增强：Manager 聚合 + Critic 思路减少遗漏。
- 风险控制：工具权限按 Agent 白名单限制，避免越权调用。

## 7. 架构决策与替代方案对比

本节回答两个问题：
- 为什么当前采用这套设计。
- 为什么暂不采用其他常见替代方案。

### 7.1 RAG 侧关键决策

1. 检索策略采用“向量检索 + 关键词兜底”
- 选择原因：
  - 纯向量在短 query、术语精确匹配场景下容易漏召回。
  - 纯关键词在语义改写、同义表达时召回差。
  - 两者组合可提升稳健性，且实现成本可控。
- 替代方案：
  - 仅向量检索。
  - 仅 BM25/倒排检索。
  - 向量检索 + Cross-Encoder 重排。
- 暂不采用原因：
  - 仅向量或仅关键词单点能力不足。
  - Cross-Encoder 重排会引入额外模型成本和时延，当前系统先保证吞吐与稳定性。

2. 向量检索默认使用 MMR（非纯 topK 相似度）
- 选择原因：
  - MMR 能抑制“高相似但内容重复”的候选，提升上下文多样性。
  - 对长文档切片后重复片段多的场景更友好。
- 替代方案：
  - 纯 topK 相似度排序。
  - 先聚类再抽样。
- 暂不采用原因：
  - 纯 topK 重复率高，实际回答信息增量小。
  - 聚类方案复杂度更高，收益相对 MMR 不明显。

3. 切片采用固定窗口 + overlap（非语义切片）
- 选择原因：
  - 语言无关，规则简单，性能稳定。
  - 与 OCR/Markdown 混合文本兼容性更好。
- 替代方案：
  - 句法/段落语义切片。
  - 基于标题层级的结构化切片。
- 暂不采用原因：
  - 语义切片对中英文混排、OCR 噪声文本稳定性较差。
  - 当前优先稳定可控，后续可在高质量语料上引入语义切片。

4. OCR 放在入库阶段（非查询时 OCR）
- 选择原因：
  - 查询时延更稳定，避免“首次查询慢、后续快”的波动。
  - OCR 额度可在上传阶段清晰计费与限流。
- 替代方案：
  - 查询时按需 OCR。
  - 混合策略（热文档预处理，其余按需）。
- 暂不采用原因：
  - 查询时 OCR 会显著增加交互延迟并放大峰值资源压力。
  - 混合策略需要额外缓存与任务调度体系，当前阶段复杂度偏高。

5. RAG 未命中时返回固定提示（非直接让模型自由回答）
- 选择原因：
  - 强约束“知识库问答”边界，降低幻觉风险。
  - 对业务方可解释，便于线上问题定位。
- 替代方案：
  - 未命中时回退到模型通识回答。
  - 未命中时自动触发联网搜索。
- 暂不采用原因：
  - 通识回答容易让用户误以为来自知识库。
  - 自动联网搜索会改变“私域问答”边界，且引入额外合规与成本问题。

### 7.2 Agent 侧关键决策

1. 单 Agent 与多 Agent 并存（按配置启用）
- 选择原因：
  - 简单任务走单 Agent，链路短、成本低。
  - 复杂任务可启用多 Agent 分工，提升完整性。
- 替代方案：
  - 全部任务都用多 Agent。
  - 全部任务都用单 Agent + 更长提示词。
- 暂不采用原因：
  - 全量多 Agent 成本与时延过高。
  - 全量单 Agent 在复杂任务上遗漏率更高。

2. 团队为空时回退 Planner/Researcher/Critic 内置三角色
- 选择原因：
  - 避免配置不完整导致功能不可用。
  - 保障多 Agent 能力“开箱即用”。
- 替代方案：
  - 团队为空直接报错。
  - 团队为空退化为单 Agent。
- 暂不采用原因：
  - 直接报错影响可用性。
  - 直接退化单 Agent 会损失多角色收益。

3. 工具权限按 Agent 白名单控制
- 选择原因：
  - 权限边界清晰，可审计。
  - 降低模型误调高风险工具概率。
- 替代方案：
  - 所有 agent 共享全量工具。
  - 在 prompt 中软约束，不做后端强校验。
- 暂不采用原因：
  - 共享全量工具越权风险高。
  - 仅靠 prompt 约束不可控，安全性不足。

4. `model` 与 `toolModel` 解耦
- 选择原因：
  - 工具步骤可用更便宜模型，主回答用高质量模型。
  - 成本/质量可独立调优。
- 替代方案：
  - 统一单模型。
- 暂不采用原因：
  - 单模型要么贵、要么效果不稳，缺少精细化成本控制。

### 7.3 Tool 执行层关键决策

1. 采用“路由器 + 处理器”分层（`DefaultToolExecutor + ToolHandler`）
- 选择原因：
  - 单一职责清晰，便于单元测试和后续扩展。
  - 新工具只需新增处理器并实现 `supports/execute`。
- 替代方案：
  - 一个大类承载全部工具逻辑（旧方案）。
  - 反射/脚本动态加载工具。
- 暂不采用原因：
  - 大类维护成本高、回归风险大。
  - 动态加载灵活但可观测性和安全治理成本更高。

2. 工具调用协议采用 JSON 文本（并辅以意图触发）
- 选择原因：
  - 跨模型兼容，不绑定某一家模型厂商的 function-calling 协议。
  - 对现有多适配器实现改造小。
- 替代方案：
  - 全面切换到厂商原生 function-calling。
- 暂不采用原因：
  - 当前模型池异构（多家模型），统一协议成本更低。
  - 原生 function-calling 在不同厂商间字段与行为不一致，迁移成本高。

3. `web_search` 采用多数据源并发 + 回退
- 选择原因：
  - 单一搜索源受限时，系统仍有较高可用性。
  - 并发等待“最先可用结果”可降低长尾延迟。
- 替代方案：
  - 只保留一个搜索源。
  - 串行依次尝试多个源。
- 暂不采用原因：
  - 单源易受封禁/限流影响。
  - 串行尝试在失败场景下时延过高。

### 7.4 当前设计边界（何时考虑替代方案）

出现以下情况时，可考虑切换：
- 召回质量瓶颈明显：引入 Cross-Encoder 重排或语义切片。
- 工具数量快速增长：继续把 `WebSearchToolHandler` 拆到 provider 级。
- 模型供应商收敛：可评估统一迁移到原生 function-calling。
- 查询时延预算放宽：可评估查询时动态 OCR 或更重的重排链路。

## 8. 基于当前代码的改进建议

1. 同步聊天接口与流式接口新建会话语义不一致：
   - `POST /api/chat/message` 目前依赖已存在 `chatId`
   - `POST /api/chat/stream` 支持自动创建
   - 建议统一行为，减少前端分支逻辑。

2. `ChatServiceImpl` 日志存在重复打印段：
   - 多 Agent 打点有重复调用，建议清理避免噪声日志。

3. `ChatServiceImpl` 与 `MultiAgentOrchestrator` 都有工具意图判断逻辑：
   - 建议提取统一策略组件，避免规则漂移。

4. `WebSearchToolHandler` 仍偏大：
   - 当前聚合了多搜索源策略、HTML 清洗、HTTP 请求与解析逻辑
   - 建议继续拆成 provider 级组件（Wikipedia/Baidu/Bocha/Searx/SerpApi）。

5. DTO 参数校验可进一步标准化：
   - 当前大量手写校验，建议增加 `jakarta.validation` 注解统一约束。

6. API 契约可补 OpenAPI：
   - 自动生成文档与示例，降低接入沟通成本。

7. 错误文案语言可统一：
   - 当前中英文混用（如 RAG no-context），建议规范化。

8. RAG 可补可观测性指标：
   - 建议记录命中率、fallback 比例、平均分数区间，便于调参。
