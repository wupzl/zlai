# Skill Architecture（当前实现与后续演进）

本文档说明当前 `zlAI` 项目里 skill 层的真实边界，以及后续通过 Specify Kit 规划 skill 相关 feature 时应该如何落地。

## 1. 当前真实系统里 skill 的位置

当前 skill 层已经是正式运行能力，不是纯设计文档。

它处在以下几个模块之间：

- `AgentTool` / `ExecutableAgentTool`
- `AgentToolRegistry`
- `AgentSkillDefinition`
- `SkillPlanner`
- `SkillExecutor`
- `ToolFollowupService`
- `MultiAgentOrchestrator`

当前 skill 的职责是：

- 对工具进行业务级封装
- 给 planner 提供候选能力
- 给 executor 提供结构化执行配置
- 把复杂工具能力包装成单个 agent 可调用单元

## 2. 当前 skill 层的四个核心组件

### 2.1 Skill Metadata

当前 skill 运行时模型核心字段：

- `key`
- `name`
- `description`
- `toolKeys`
- `executionMode`
- `inputSchema`
- `stepConfig`

### 2.2 Skill Routing

当前 skill selection 由 `SkillPlanner` 负责。

当前 planner 主要基于：

- requested skill
- user prompt keyword 命中
- description / tool / schema 匹配
- allowed skills 约束

### 2.3 Skill Execution

当前 skill execution 由 `DefaultSkillExecutor` 负责。

当前执行逻辑：

- 校验 `inputSchema`
- 解析 `stepConfig` 或回退到 `toolKeys`
- `single_tool` 或 `pipeline` 执行
- 优先使用 `ExecutableAgentTool`
- fallback 到 `ToolExecutor`

### 2.4 Fallback / Traceability

当前 skill 层已经有基础可观测性和回退路径：

- disallowed skill fallback
- execution failure fallback
- billing hooks
- memory write-back
- workflow progress recording

## 3. 当前 skill 与 markdown skill 的关系

当前项目里有两层概念：

### 3.1 当前真正运行的 skill

这是结构化 runtime skill，能被 planner 选中并执行。

### 3.2 当前导入用的 markdown skill

这是输入来源，不是最终运行时对象。

现在支持通过 importer：

- 粘贴 `SKILL.md`
- 上传 markdown 文件
- 转成托管 skill

但当前并没有实现 markdown-native Skills runtime。

也就是说：

**现在 markdown 只负责导入，不负责运行时按需加载。**

## 4. 什么时候算 skill-layer feature

在当前项目里，下列变更都应该算 skill-layer feature：

- skill metadata 扩展
- skill planner 策略调整
- skill executor 行为调整
- markdown skill importer 变更
- skill fallback / observability 变更
- multi-agent 中的 skill 路由变更

而下列变更不应误算成 skill-layer feature：

- 普通 CRUD 页面调整
- 无 skill 语义的普通工具配置修改
- 与 skill 无关的纯 UI 文案调整

## 5. 当前最重要的模块边界

在当前系统里，这些边界要保持清晰：

### 5.1 Tool 是原子能力

Tool 负责：

- 原子执行
- 输入输出合同
- 参数 schema

不负责高层业务编排。

### 5.2 Skill 是编排层

Skill 负责：

- 包装一个或多个 tool
- 定义执行模式
- 提供输入 schema
- 在 planner 中作为可选能力被识别

### 5.3 Followup Service / Orchestrator 是主链路入口

不要把 skill 逻辑散落到 controller 或普通 service 中。

当前主接入点仍然应该是：

- `ToolFollowupService`
- `MultiAgentOrchestrator`

## 6. 当前项目做 skill 相关 spec 时应该写什么

如果后续继续用 Specify Kit 管 skill feature，最少应写清楚：

- `Skill Classification`
- `Trigger Model`
- `Execution Contract`
- `Fallback Strategy`
- `Observability`
- `Mainline Preservation`

建议每个 skill feature 至少包含：

- `spec.md`
- `plan.md`
- `tasks.md`
- `data-model.md`
- `contracts/skill-contract.md`
- `quickstart.md`

## 7. 后续真实 Skills 演进方向

如果后续要把当前 skill 层升级成真正的 markdown-native Skills，建议增加两层：

- `SkillManifest`
- `SkillDocument / SkillSection`

届时 skill 架构会变成：

- Runtime Skill：负责执行
- Markdown Skill Document：负责 SOP 与按需加载

但这一步属于下一阶段演进，不应和当前结构化 skill runtime 混为一谈。

## 8. 一句话总结

当前 `zlAI` 的 skill 层已经是结构化执行编排层，markdown importer 只是导入入口；后续如需做真实 Skills，应在现有 runtime 之上增加 document-layer 和按需加载能力，而不是推翻当前 skill runtime。 
