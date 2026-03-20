# zlAI 项目里的 MCP 面试回答

## 先说结论

这个项目目前**没有真正落地标准 MCP**。

更准确的说法是:

- 我已经实现了 **Agent + Tool + Skill + Workflow** 的一套自定义调用体系
- 它在能力形态上和 MCP 有相似点，比如“让模型调用外部能力”
- 但它**不是基于 MCP 协议的标准化实现**，当前代码里没有看到 MCP Server、MCP Client、标准协议握手、标准资源发现或标准工具暴露

所以如果面试官问“你这个项目做没做 MCP”，最稳妥的回答是:

> 这个项目现在还不是标准 MCP 实现，但已经做了 MCP 之前那一层核心能力，也就是把模型调用工具、技能编排、多 Agent 协作和工具结果回填这一套链路打通了。如果后续要升级到 MCP，现有架构可以比较平滑地改造。

---

## 为什么我说它还不是 MCP

### 1. 代码里没有 MCP 协议实现痕迹

我检查了项目代码，没有发现这些典型内容:

- `MCP Server`
- `MCP Client`
- `Model Context Protocol`
- 标准 MCP 的协议协商
- 标准化的工具、资源、Prompt 暴露接口

也就是说，当前项目不是在“接一个标准 MCP 生态”，而是在项目内部自己维护了一套工具调用机制。

### 2. 当前项目是自定义工具协议，不是标准协议

项目里工具执行的核心接口是:

- [ToolExecutor.java](D:/Code/zlAI-v2/backend/src/main/java/com/harmony/backend/ai/tool/ToolExecutor.java)
- [AgentToolRegistry.java](D:/Code/zlAI-v2/backend/src/main/java/com/harmony/backend/ai/tool/AgentToolRegistry.java)

这里是典型的“本项目内部定义工具注册表 + 本项目内部调用工具执行器”。

它解决的是:

- 系统里有哪些工具可用
- Agent 可以调用哪些工具
- 工具执行后如何拿结果

但它没有解决 MCP 那个层面的标准化问题，例如:

- 外部客户端如何按统一协议发现这些工具
- 外部模型框架如何无侵入接入
- 工具、资源、Prompt 如何按标准 schema 暴露给第三方

### 3. Skill 和 Workflow 也是项目内编排，不是 MCP 编排

项目里 Skill 相关核心代码是:

- [AgentSkillRegistry.java](D:/Code/zlAI-v2/backend/src/main/java/com/harmony/backend/ai/skill/AgentSkillRegistry.java)
- `DefaultSkillPlanner`
- `DefaultSkillExecutor`

聊天链路里的工具/技能回填逻辑在:

- [ToolFollowupService.java](D:/Code/zlAI-v2/backend/src/main/java/com/harmony/backend/modules/chat/service/support/ToolFollowupService.java)

这里的执行方式是:

1. 模型先产出一个带 `tool` 或 `skill` 字段的内部 JSON
2. 服务端解析这个 JSON
3. 命中内部工具或技能
4. 执行后把结果再次喂回模型
5. 模型基于工具结果生成最终回答

这是一套能工作的 Agent 工程化方案，但它仍然是**应用内部协议**，不是 MCP。

---

## 如果面试官问“那你这个项目做了什么，和 MCP 有什么关系”

可以这样回答:

> 我这个项目没有直接上标准 MCP，但我把 MCP 想解决的核心问题先在业务系统里做了一遍。具体来说，我实现了工具注册、技能封装、技能规划、工具执行、工具结果回填、多 Agent 编排和权限约束。也就是说，模型已经不只是聊天，而是能在受控范围内调用外部能力。区别在于，我当前用的是项目内部自定义协议，而不是 MCP 的标准协议。

这段话的重点是:

- 不硬说自己做了 MCP
- 但明确说明自己做过 MCP 背后的核心工程问题
- 面试官会更容易判断你是“懂协议价值的”，不是只会背概念

---

## 结合项目，MCP 可以怎么理解

如果要结合你的项目去讲，最容易理解的表达是:

### 1. 你现在已经有了 MCP 的“业务前置层”

你项目里已经有这些部件:

- Tool 注册与发现
- Skill 对多个 Tool 的封装
- Planner 决定该调用哪个 Skill
- Executor 执行 Skill 或 Tool
- Tool result 回填给模型生成最终答案
- Agent / Multi-Agent 组合调用

这说明你已经解决了:

- 工具怎样被模型使用
- 工具怎样被权限约束
- 工具结果怎样进入回答链路
- 多工具和多 Agent 怎样协同

而 MCP 本质上是在这些能力之外，再加上一层**标准化协议和互操作性**。

### 2. MCP 对你这个项目的价值，主要不是“多一个功能”，而是“标准化接口”

如果把你现有项目升级成 MCP，核心价值会变成:

- 让现有 `web_search`、RAG 查询、文档读写、后台能力按统一协议暴露
- 让外部 Agent 框架或 IDE 可以直接接入这些能力
- 让工具定义不再只服务 zlAI 自己，而是服务一个更大的 AI 工具生态

也就是说:

- 你现在做的是“应用内工具系统”
- MCP 要做的是“应用外可复用工具系统”

这句话很适合面试时说。

---

## 面试官如果追问“那你为什么没直接做 MCP”

建议这样答:

> 因为这个项目当时的目标是先把业务闭环跑通，包括聊天、Agent、RAG、计费、后台治理和多轮工具调用，所以我优先做了应用内可控的 Tool/Skill/Workflow 体系。这样开发效率更高，链路更可控，也更容易和会话、权限、计费、限流这些业务逻辑打通。MCP 我理解它更适合在工具体系稳定后，再往标准化和生态接入方向演进。

这个回答的逻辑是:

- 不是不会做
- 而是有意识地按工程优先级取舍
- 先做业务闭环，再做协议标准化

这是比较像高级工程师的回答方式。

---

## 如果面试官问“你这个项目哪里最像 MCP”

你可以答这几个点。

### 1. Tool 抽象

项目里已经把工具抽象成统一执行入口，而不是把搜索、RAG、OCR 调用硬编码在聊天逻辑里。

对应代码:

- [ToolExecutor.java](D:/Code/zlAI-v2/backend/src/main/java/com/harmony/backend/ai/tool/ToolExecutor.java)
- [AgentToolRegistry.java](D:/Code/zlAI-v2/backend/src/main/java/com/harmony/backend/ai/tool/AgentToolRegistry.java)

### 2. Skill 封装

项目不是让 Agent 直接裸调所有工具，而是先通过 Skill 对工具能力做一层封装，这和 MCP 里“把能力做成可发现、可调用单元”的思路很接近。

对应代码:

- [AgentSkillRegistry.java](D:/Code/zlAI-v2/backend/src/main/java/com/harmony/backend/ai/skill/AgentSkillRegistry.java)

### 3. 调用-执行-回填闭环

`ToolFollowupService` 已经实现了“模型提出调用请求 -> 服务端执行 -> 将结果再次喂给模型形成最终回答”的闭环，这正是 Agent 工程里非常关键的一步。

对应代码:

- [ToolFollowupService.java](D:/Code/zlAI-v2/backend/src/main/java/com/harmony/backend/modules/chat/service/support/ToolFollowupService.java)

### 4. 多 Agent 编排

你的项目还有多 Agent 协作和 Workflow 路由，这比简单的单次工具调用更进一步。

这说明你做的不只是“函数调用”，而是“受控的智能体执行系统”。

---

## 如果面试官问“那 MCP 和你现在这套系统最大的差别是什么”

建议直接说这三点:

### 1. 标准化程度

- 当前项目: 内部自定义协议
- MCP: 跨系统、跨框架统一协议

### 2. 互操作范围不同

- 当前项目: 主要给 zlAI 自己的 Chat/Agent 用
- MCP: 可以给 IDE、桌面客户端、外部 Agent 平台复用

### 3. 能力发现方式不同

- 当前项目: 工具和技能由服务端注册表维护
- MCP: 更强调标准化 discovery、schema、上下文资源暴露

一句话版:

> 我现在做的是“项目内可控的工具编排系统”，而 MCP 要做的是“面向外部生态的标准工具协议”。

---

## 如果面试官问“如果让你把这个项目改造成支持 MCP，你会怎么做”

可以这样答。

### 方案思路

我会做一层 **MCP Adapter / MCP Gateway**，而不是推翻现有 Tool/Skill 体系。

### 第一步: 把现有 Tool 暴露为 MCP Tool

例如把这些能力映射成标准 MCP 工具:

- `web_search`
- RAG 查询
- 文档列表查询
- 文档删除
- 可能还有用户可见的知识库 session 创建能力

映射时保留现有:

- 权限校验
- 限流
- 计费
- 审计日志

### 第二步: 把 Skill 变成更高层 MCP 能力

Skill 不是直接废掉，而是作为:

- MCP Tool 的组合编排层
- 或 MCP Prompt / Workflow 模块的上层封装

这样可以保留你项目里已经做好的:

- Planner
- Executor
- Multi-step pipeline

### 第三步: 增加标准 schema 和能力发现

要补上的不是业务逻辑，而是协议层:

- 统一工具描述
- 输入输出 schema
- 能力发现
- 标准响应格式

### 第四步: 处理会话上下文边界

你项目现在很多能力默认依赖:

- 登录态
- Session
- Agent 可用技能集合
- `toolModel`
- 计费余额

这些都要在 MCP 化时重新定义边界:

- 哪些能力可匿名暴露
- 哪些能力必须带用户上下文
- 哪些能力是“平台内部调用”而不是“对外 MCP 调用”

这一段很重要，因为它能说明你不是只会说“加个协议层”，而是知道真实改造难点在权限和上下文边界。

---

## 一段适合面试直接说的标准答案

> 我这个项目目前没有直接接入标准 MCP，但已经做了和 MCP 很接近的一套内部能力编排系统。项目里有 Tool 注册与执行、Skill 封装、Skill Planner、Tool/Skill 回填、多 Agent 编排和权限控制。比如模型可以先输出内部的 tool/skill 调用请求，服务端解析后执行 `web_search` 或其他技能，再把结果回填给模型生成最终答案。  
>  
> 所以如果严格说，它现在还不是 MCP，因为没有实现标准协议层和跨系统互操作；但如果后续要往 MCP 演进，我不需要推翻现有架构，只需要在现有 Tool/Skill 之上补一层标准化的 MCP Adapter，把工具描述、schema、发现机制和协议交互补上就可以。

---

## 面试时不要这样说

以下说法不建议用:

- “我这个项目已经做了 MCP”
- “只要有工具调用就是 MCP”
- “MCP 就是 function calling”

这些说法都太粗，会被追问后露出问题。

更稳妥的表述是:

- “我做的是 MCP 前置能力，不是标准 MCP”
- “我已经做了 Tool/Skill/Workflow，离 MCP 差的是协议标准化”
- “如果需要，我可以把现有体系适配成 MCP”

---

## 最后一版超短回答

如果面试官问得很快，你可以直接答:

> 这个项目现在没直接做标准 MCP，我做的是一套自定义的 Tool、Skill 和 Multi-Agent 编排系统，已经把模型调用外部能力、结果回填、权限控制和计费链路跑通了。和 MCP 的差别主要在于我现在是项目内协议，不是面向外部生态的标准协议；但现有架构已经具备往 MCP 演进的基础。
