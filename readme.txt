项目名称：Harmony AI 智能对话系统
版本：v2.3.1
更新时间：2026-01-15

一、项目概述
Harmony AI 是一个基于大语言模型（LLM）的智能对话系统，支持多轮对话、多分支会话、消息重发、上下文记忆以及知识库增强问答（RAG）。

系统采用模块化架构设计，主要包括以下模块：
1. chat 模块：负责对话生成与上下文管理
2. agent 模块：支持工具调用与任务规划
3. billing 模块：处理计费逻辑与额度扣减
4. knowledge 模块：实现向量化存储与语义检索
5. auth 模块：基于 JWT 实现身份认证

二、技术栈
后端语言：Java 21
框架：Spring Boot 3.2
数据库：PostgreSQL 15
缓存：Redis 7
向量数据库：pgvector
嵌入模型：text-embedding-3-large
大模型接口：OpenAI GPT-4o-mini

三、系统架构说明
系统采用分层设计：
- controller 层：接收 HTTP 请求
- service 层：业务逻辑处理
- repository 层：数据持久化
- handler / event / listener：事件驱动机制

在对话创建时，系统会生成唯一的 messageId。
如果客户端因为网络原因未收到响应，可以携带 MessageId 进行消息重发。

四、RAG 工作流程
1. 用户上传文档
2. 文档分块（chunk size = 500 tokens, overlap = 50）
3. 使用 embedding 模型生成向量
4. 向量写入 pgvector
5. 查询时执行相似度检索（TopK = 5）
6. 将检索结果拼接进 prompt
7. 调用 LLM 生成最终回答

五、关键概念说明

LLM（Large Language Model）：
一种基于 Transformer 架构的深度学习模型，能够进行文本生成与理解。

Embedding：
将文本转换为高维向量表示的过程，用于语义相似度计算。

向量相似度：
常用算法包括：
- Cosine Similarity
- Inner Product
- Euclidean Distance

六、示例问答

问题1：Harmony AI 使用什么数据库存储向量？
答案：系统使用 PostgreSQL + pgvector 进行向量存储。

问题2：如何实现消息重发？
答案：客户端在请求体中添加 MessageId 字段。

问题3：系统是否支持多轮对话？
答案：支持，并且可以进行上下文记忆管理。

七、性能指标

平均响应时间：850ms
向量检索耗时：45ms
Token 平均消耗：1200 tokens
并发支持：1000 QPS

八、安全策略

1. 使用 JWT 进行身份认证
2. 所有接口必须携带 Authorization Header
3. 对敏感操作进行权限校验
4. 数据库存储密码采用 bcrypt 哈希

九、未来规划

- 支持多模态输入（图片 + 文本）
- 引入 Agent 工具调用机制
- 增强知识库自动更新能力
- 引入增量索引优化策略
- 支持混合检索（BM25 + 向量检索）

十、测试语句集合（用于召回验证）

Harmony AI 使用 pgvector 存储向量数据。
系统基于 Spring Boot 3.2 开发。
嵌入模型是 text-embedding-3-large。
数据库版本为 PostgreSQL 15。
身份认证使用 JWT。
支持多轮对话和消息重发机制。
