# 腾讯面试速背版（当前系统）

## 1. 项目一句话

我做的是一个完整的 AI 应用平台，不只是调用大模型，而是把 Chat、RAG、Agent、Skill、Tool、Memory、评测和部署都做成了闭环。

## 2. 你最该先背的 6 句

- skill 现在不是 prompt，而是结构化执行编排层
- 当前闭环是 `skill -> execute -> followup`
- tool 合同已经标准化到 `AgentTool / ExecutableAgentTool / AgentToolRegistry`
- RAG 不是纯向量检索，而是 `document-aware + hybrid retrieval + MMR + heuristic rerank`
- 我已经做过真实回归，smoke `20/20`，测试子集 `153/168`
- 当前还没有完整 markdown-native Skills，只有结构化 skill runtime + markdown importer

## 3. Skill 怎么讲

当前 skill 的核心字段有：

- `toolKeys`
- `executionMode`
- `inputSchema`
- `stepConfig`

模型输出 skill JSON 后，不会直接执行，而是先过 `SkillPlanner` 做 allowed skills 过滤和排序，再由 `SkillExecutor` 执行，最后 followup 生成用户可见答案。

## 4. Markdown Skill 怎么讲

现在已经支持把 `SKILL.md` 导入成托管 skill，管理端也有导入页面。

但要注意实话实说：

- 现在能导入，不代表 markdown 本身直接运行
- 当前 importer 会先解析、映射、校验，再转成结构化 skill
- 现在还没有做 section 级按需加载和渐进式披露

## 5. RAG 怎么讲

当前 RAG 重点不是“向量库跑通”，而是：

- 文档清洗
- OCR
- 结构化切块
- hybrid retrieval
- heuristic rerank
- document-aware 文件级召回
- 离线评测和真实回归

## 6. 高频止损答法

### Q：你是不是已经做成 OpenAI 那种 Skills 了？

没有硬说做到那种完整形态。当前已经有结构化 skill runtime 和 markdown importer，但运行时还没有 section 级按需加载。

### Q：你为什么没用 Cross-Encoder / Ragas？

因为当前阶段我优先想把 retrieval 层和 generation 层拆开验证，先把现有链路跑顺、评估清楚。

### Q：你前端不强怎么办？

我的强项在后端架构、RAG、Agent 和数据库，但我已经能独立完成 Vue3 管理端、接口联调和异步任务页面，比如 skill markdown 导入页就是我接进去的。
