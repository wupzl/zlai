# RAG 面试总稿（当前实现）

适用目标：面试前统一复习当前项目里真正已经做完的 RAG / Skill / Agent 相关能力。

## 1. 项目总述

我这个项目不是单独做了一个向量库问答 demo，而是做了一个完整的 AI 应用平台，里面把：

- Chat
- Agent
- Skill
- Tool
- Memory
- RAG
- 多模型接入
- 部署
- 评测

都串起来了。

其中 RAG 不是纯向量检索，skill 也不是简单 prompt，而是已经做成了结构化执行层。

## 2. RAG 现在到底是什么

### 2.1 当前检索链路

当前默认检索可以概括成：

**document-aware -> hybrid retrieval -> vector MMR + keyword branch -> heuristic rerank**

也就是说：

- 文件级问题先看 document-aware 路由
- 常规问题走 hybrid retrieval
- 向量支路里默认是 MMR
- 关键词支路是轻量规则检索
- 最后不是 external reranker，而是启发式 rerank

### 2.2 当前为什么不是纯 topK

因为只靠纯向量 topK 会有几个问题：

- 重复 chunk 太多
- 标题和章节词命中不稳
- 文件总结和章节总结这种问题不适合只靠局部 chunk

所以我做了：

- whole-document summary 路由
- section-aware 路由
- hybrid retrieval
- heuristic rerank

### 2.3 当前关键词检索到底是什么

当前不是 BM25，也不是 ES。

真实实现是：

- 规则提词
- 中文扩词
- PostgreSQL `ILIKE` 模糊匹配
- SQL metadata 加权
- Java lexical score 再排一轮

所以关键词检索是一个轻量词法支路，不是完整搜索引擎。

### 2.4 当前为什么没用 Cross-Encoder

不是说不会用，而是当前项目阶段更看重：

- 复杂度低
- 延迟可控
- 成本可控
- 可解释性强

现在已经有：

- document-aware
- hybrid retrieval
- MMR
- heuristic rerank
- 评测工具链

所以先把现有链路跑顺、验证清楚，比直接上 Cross-Encoder 更合理。

### 2.5 当前为什么没先上 Ragas

因为我当前优先想把：

- retrieval 问题
- generation 问题

拆开验证。

当前自写工具链更偏：

- 检索层回归
- 上下文组织层回归

Ragas 更适合后面做：

- faithfulness
- answer relevancy
- 端到端回答质量评估

## 3. RAG 评测现状

### 3.1 评测集怎么来的

当前评测集不是人工逐条精标 benchmark，而是：

**基于真实资料库副本，自己设计规则、自己写脚本，启发式自动生成的第一版工程回归集。**

### 3.2 当前评测工具是什么

不是第三方平台，而是项目里自己写的工具链：

- `normalize-study-resource.ps1`
- `generate-rag-eval-set.ps1`
- `run-rag-eval.ps1`

### 3.3 当前真实结果怎么讲

当前可以直接讲两组真实结果：

- smoke：`20 / 20`
- 测试集子集：`153 / 168 = 91.07%`

而且失败样本里主要问题已经收敛在：

- `section_summary`

这说明当前系统不是“整体不行”，而是“章节级精度还在继续优化”。

## 4. Skill 现在到底是什么

### 4.1 当前 skill 不是简单 prompt

当前项目里的 skill 已经是结构化运行时对象，核心字段包括：

- `key`
- `name`
- `description`
- `toolKeys`
- `executionMode`
- `inputSchema`
- `stepConfig`

也就是说，skill 现在是一个真正的执行编排单元。

### 4.2 skill 执行链路是什么

当前单 Agent 里，核心闭环是：

**skill -> execute -> followup**

具体是：

1. 模型先输出内部 `skill` JSON
2. 后端用 `SkillPlanner` 在 allowed skills 中做选择
3. `SkillExecutor` 按 schema / toolKeys / stepConfig 执行
4. 成功后 billing 和记忆更新
5. 最后 followup 生成用户可见答案

### 4.3 当前 tool 层有没有升级

有。

当前已经把 tool 合同标准化到：

- `AgentTool`
- `ExecutableAgentTool`
- `AgentToolRegistry`

这样 skill 在执行时可以优先拿 executable tool，但 runtime 里还保留了原有 `ToolExecutor` 兼容层。

### 4.4 当前有没有 markdown skill importer

有。

现在管理端已经支持：

- 粘贴 `SKILL.md`
- 上传 markdown 文件
- 导入后自动转成托管 skill

对应后端接口是：

- `POST /api/admin/skills/import-markdown`
- `POST /api/admin/skills/import-markdown-file`

### 4.5 现在是不是已经做成真实 Skills 了

还没有。

当前已经做到的是：

- 结构化 skill runtime
- markdown importer

但还没做到：

- markdown 原文在运行时按需加载
- section 级渐进式披露
- token budget 驱动的 skill 文档分段注入

所以更准确的表述是：

**当前已经有可执行 skill 层，但还不是完整的 markdown-native Skills system。**

## 5. Memory 怎么讲

当前 memory 是三层：

- `conversation_summary`
- `user_memory`
- `task_state`

而且我已经把它从“每轮都刷”改成了“按价值刷新”：

- summary 有增量门槛和时间门槛
- user memory 只在稳定偏好信号时更新
- task state 只在任务型回合或结构化产物时更新

这个点面试里是能讲出工程感的。

## 6. 高频问题直接答法

### Q1：你的 RAG 还是纯 MMR 吗？

不是纯 MMR。MMR 现在只在向量支路里负责候选多样性，最终结果是 hybrid merge + heuristic rerank。

### Q2：你为什么做 document-aware？

因为很多问题不是局部 chunk 问答，而是文件总结、按文件名总结、章节总结，这类问题要单独组织更大粒度上下文。

### Q3：你现在 skill 是不是就是 prompt？

不是。当前 skill 已经是结构化执行编排层，包含 input schema、toolKeys、execution mode 和 step config。

### Q4：导入一个开源 `SKILL.md` 能直接用吗？

不一定。当前 importer 会先解析、再映射、再校验，最终转成项目自己的托管 skill。只要 markdown 里 tools 和 workflow 足够清晰，通常可以直接导入；否则还需要补 `defaultToolKeys` 或 `toolAliases`。

### Q5：你现在是不是已经实现真实 Skills 了？

没有硬说做成那种完整形态。当前已经做成结构化 skill runtime，并支持 markdown importer；真正的 markdown-native Skills 还差按需加载和渐进式披露。

## 7. 最后速背 8 句

- 我的 RAG 不是纯向量检索，而是 document-aware + hybrid retrieval + MMR + heuristic rerank
- document-aware 主要解决文件总结和章节总结这类大粒度问题
- 当前关键词检索不是 BM25，而是规则提词 + PostgreSQL 模糊匹配 + metadata 加权 + Java 重排
- 当前没有优先上 Cross-Encoder 和 Ragas，是明确的阶段性工程取舍
- 我已经搭了离线评测工具链，并跑过真实回归，smoke 是 `20/20`，测试集子集是 `153/168`
- 当前 skill 已经不是 prompt，而是结构化执行编排层
- 当前管理端已经支持 `SKILL.md` 导入，但导入后会先转成托管 skill，不是直接把 markdown 当 runtime
- 当前还没有完整的 markdown-native Skills，下一步演进方向是按需加载和渐进式披露
