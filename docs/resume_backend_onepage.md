# 后端开发实习一页终稿

# 彭子龙

手机：15634075538 | 邮箱：248282734@qq.com
GitHub：https://github.com/wupzl/zlai | 在线演示：https://www.zlai.me
求职意向：后端开发实习

---

## 教育背景

山东大学｜软件工程｜本科｜2023.09 - 2027.06
主修课程：数据结构、数据库系统、计算机网络、操作系统、软件工程、Java 程序设计

---

## 项目经历

### zlAI 大模型应用平台｜独立设计与开发
技术栈：Spring Boot / MyBatis-Plus / MySQL / Redis / PostgreSQL(pgvector) / Vue3 / Docker / Nginx

项目简介：
独立完成面向大模型问答、知识库检索与智能体协作的全栈平台开发，覆盖聊天、Agent、RAG、管理后台、计费与公网部署，形成完整后端开发与工程落地闭环。

核心工作：

1. 为解决多模型接入协议不统一的问题，设计 `LlmAdapter` 适配层，统一 OpenAI-compatible / DeepSeek / Claude / Mock 模型的请求、流式输出与异常处理，降低新模型接入和维护成本。
2. 独立实现多会话、多分支聊天链路，支持消息重发、上下文裁剪、模型切换、工具模型分离和 token 余额校验，并补充全局限流与分路由节流，提升高频访问场景下的稳定性。
3. 为提升复杂任务执行可控性，落地单智能体与多智能体工作流：单智能体采用 rule-first + LLM 消歧规划，多智能体采用 planner + workflow 执行模式，支持并行/串行协作、步骤保护与状态持久化。
4. 独立实现 `conversation_summary / user_memory / task_state` 三类持久化记忆，支持对话摘要、用户偏好抽取、任务进度记录与工作流状态回写，增强多轮任务连续性。
5. 为解决知识库问答效果不稳定问题，搭建 RAG 文档处理与检索链路，覆盖 Markdown/PDF/DOCX/TXT 入库、图片/PDF OCR、结构化切块、Embedding 入库、向量检索、rerank 与 document-aware 文件级召回，支持文件总结、章节问答和知识库问答。
6. 独立重构知识库上传链路为异步任务模式，使用 Redis 任务状态与内容指纹实现任务级幂等和重复上传去重；同时实现后台治理能力，支持用户管理、GPT/Agent 审核、OCR/搜索/限流配置、模型倍率管理与日志审计。
7. 完成 Docker Compose 编排 MySQL / Redis / PostgreSQL / Backend / Frontend 服务，并使用 Nginx 提供 HTTPS 访问；搭建离线 RAG 评测工具链，并使用 k6 对聊天、混合路由与 RAG 场景做压力测试。

项目结果：

- 形成包含多模型接入、聊天编排、Agent 工作流、RAG 检索、后台治理、计费与部署的完整后端系统。
- 使用 k6 完成压测：核心聊天场景 30 VUs / 60s 成功 889 次，吞吐约 14.8 req/s；混合路由 30 VUs / 80s 请求 970 次，吞吐约 12.1 req/s；RAG 场景 p95 响应约 511ms，失败率 0.92%。
- 完成公网部署与在线演示，具备从设计、开发、联调、排障到上线的完整工程实践经验。

---

## 技术能力

- Java：熟悉集合、多线程、线程池、锁、volatile、AQS、JVM 内存模型与常见 GC 机制；在项目中用于异步任务处理、并发控制与稳定性优化。
- Spring Boot：熟悉分层架构设计、模块拆分与常见后端工程化实践；完成 Chat / Agent / RAG / Admin / Billing 模块开发。
- MySQL / Redis / PostgreSQL：熟悉索引、缓存、事务、幂等控制与向量检索相关场景；在项目中用于业务存储、任务状态管理与 RAG 检索。
- 工程实践：具备接口联调、问题定位、日志排查、压测分析、Docker 部署与 Linux 环境排障经验。

---

## 其他

- LeetCode 500+
- 具备公网部署、域名解析、HTTPS 证书配置经验
- 能熟练使用 Codex、Gemini CLI 等工具辅助开发与调试
