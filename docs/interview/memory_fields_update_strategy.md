# Memory Fields Update Strategy

## 1. 总结版

当前项目里的 memory 分三层：

- `conversation_summary`
- `user_memory`
- `task_state`

三者的更新策略不一样：

- `conversation_summary`：按消息增量 + 时间门槛刷新
- `user_memory`：只在出现稳定偏好信号时更新
- `task_state`：按任务事件驱动更新，不只是按回合刷新

所以它们不是“一套逻辑全都每轮更新”，而是按各自职责分开处理。

## 2. `conversation_summary`

### 2.1 谁更新

由 `ChatServiceImpl` 在成功回合结束后，通过 `rememberSuccessfulTurn(...)` 调 `AgentMemoryService.updateMemoryAfterTurn(...)`。

真正的更新逻辑在 `AgentMemoryServiceImpl.maybeUpsertConversationSummary(...)`。

### 2.2 更新策略

不是每轮都更新。

当前主要看两个门槛：

- 距离上次摘要至少新增了 `summaryRefreshEveryMessages` 条消息，默认 `6`
- 最小刷新时间 `summaryMinRefreshMinutes`，默认 `15` 分钟

如果消息积压非常多，即使时间门槛没到，也会强制刷新。

### 2.3 更新方式

优先走模型摘要：

- `summarizeWithModel(...)`

如果模型摘要失败，再走回退逻辑：

- `fallbackSummary(...)`

### 2.4 一句话理解

`conversation_summary` 负责对话摘要，是一种**按消息增量和时间门槛更新的摘要记忆**。

## 3. `user_memory`

### 3.1 谁更新

也是由成功回合后的 `updateMemoryAfterTurn(...)` 触发。

真正逻辑在：

- `maybeInferAndUpsertUserMemory(...)`

### 3.2 更新策略

`user_memory` 不会每轮都抽取。

当前只有在用户输入中出现明显的稳定偏好信号时，才会尝试更新，比如：

- 语言偏好：`请用中文`、`answer in english`
- 时区偏好：`Asia/Shanghai`、`北京时间`
- 回答风格：`简洁`、`详细`、`concise`、`detailed`

### 3.3 额外门槛

还有两个限制：

- 输入长度至少达到 `userMemoryMinPromptLength`，默认 `18`
- 同一个 memory key 的最小刷新间隔 `userMemoryMinRefreshMinutes`，默认 `180` 分钟

### 3.4 更新方式

当前是模型抽取和规则推断结合：

- 先尝试 `extractUserMemoryWithModel(...)`
- 再叠加规则推断
- 最后统一走 `upsertUserMemoryIfNeeded(...)`

### 3.5 去重策略

如果：

- memory 值没变
- 置信度没提升
- 冷却时间没到

那就不会重复写入。

### 3.6 一句话理解

`user_memory` 负责稳定偏好，不是每轮都刷，而是**只在命中稳定偏好信号时保守更新**。

## 4. `task_state`

这是最容易被忽略的一层，因为它的更新来源有三条，不是单一路径。

## 4.1 第一条：普通成功回合后的任务态更新

### 谁更新

仍然是 `updateMemoryAfterTurn(...)`。

真正逻辑在：

- `maybeUpsertAnsweredTaskState(...)`

### 怎么判断要不要更新

先判断这一轮是不是任务型回合：

- `isTaskLikeTurn(...)`

当前会看一批任务信号词，比如：

- `步骤`
- `计划`
- `todo`
- `待办`
- `任务`
- `next step`
- `implement`
- `fix`
- `analyze`
- `optimize`

同时还会尝试模型抽取结构化任务产物：

- `done_items`
- `pending_actions`
- `open_questions`

逻辑在：

- `extractTaskArtifactsWithModel(...)`

### 什么时候不更新

如果：

- 当前没有已有 task state
- 这一轮也不是 task-like turn
- 也没抽出结构化任务产物

那就不会新建 task state。

### 什么时候更新

如果已有 task state，也不是每轮都写。

真正写之前还要过：

- `shouldPersistAnsweredTaskState(...)`

主要判断：

- 重要字段是否变化
- artifacts 是否有结构化变化
- 如果只是弱变化，还要看最小刷新间隔 `taskStateMinRefreshSeconds`，默认 `45` 秒

## 4.2 第二条：skill 执行成功后

### 谁更新

`ToolFollowupService` 会在 skill 成功执行后调用：

- `recordSkillExecution(...)`

### 会写什么

这次写入会直接更新：

- `currentSkill = skillKey`
- `currentStep = skill_executed`
- `status = ACTIVE`
- `artifacts_json` 里带：
  - `skill`
  - `skill_input`
  - `skill_output_excerpt`
  - `done_items`

### 一句话理解

skill 成功执行后，`task_state` 会**立即按事件更新一次**，不等普通回合结束。

## 4.3 第三条：workflow 进度更新

### 谁更新

有两处会调：

- 单 Agent workflow：`AgentWorkflowServiceImpl`
- Multi-Agent workflow：`MultiAgentOrchestrator`

它们都会调用：

- `recordWorkflowProgress(...)`

### 单 Agent 当前会写哪些阶段

- `planned`
- `skill_failed`
- `skill_executed`
- `answered`

### Multi-Agent 当前会写哪些阶段

- `planned`
- `specialists_completed`
- `synthesized`

### 会记录什么

通常会写进：

- `workflowKey`
- `goal`
- `currentStep`
- `status`
- `artifactsJson`

### 一句话理解

workflow 更新是一种**阶段性事件写入**，用于记录任务推进过程。

## 5. `task_state` 的真实定位

如果只用一句话概括：

**`task_state` 不是普通摘要记忆，而是一个事件驱动的任务状态存储。**

它当前主要来自三类事件：

- 普通任务型回合完成
- skill 执行成功
- workflow 阶段推进

所以它比 `conversation_summary` 和 `user_memory` 更接近流程状态，而不是聊天摘要。

## 6. 面试里怎么讲

推荐回答：

> 我现在的 memory 分三层。`conversation_summary` 负责对话摘要，不是每轮都刷，而是按消息增量和最小刷新时间更新；`user_memory` 只在用户表达稳定偏好时才触发，值没变不会重复写；`task_state` 更偏事件驱动，不只是回合结束后更新，skill 成功执行和 workflow 阶段推进时也会单独写入，所以它更像任务过程状态，而不是普通摘要。

## 7. 最后一版速记

- `conversation_summary`：谁更新？成功回合后统一更新；策略？消息增量 + 时间门槛
- `user_memory`：谁更新？成功回合后统一更新；策略？稳定偏好信号触发 + 冷却 + 去重
- `task_state`：谁更新？成功回合、skill 成功事件、workflow 进度事件都能更新；策略？事件驱动 + 结构化产物优先

一句话总结：

**这三层 memory 不是同频更新的，`summary` 偏摘要，`user_memory` 偏稳定偏好，`task_state` 偏任务过程状态。**
