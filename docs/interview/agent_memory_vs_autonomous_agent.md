# Agent Memory vs Autonomous Agent

## 1. 结论

当前 `zlAI` 里的 agent **有记忆**，但**还不是那种会持续判断任务是否完成、再自动推进下一步的自主长程 Agent**。

更准确的定位是：

- 已有：持久化 memory
- 已有：task state 记录
- 已有：workflow progress 写回
- 未有：基于 task completion 的自动循环规划与自动续跑

所以当前系统更像：

**带记忆和任务状态记录的单回合 skill/workflow agent**

而不是：

**带完成判定和自驱动任务闭环的 autonomous agent**

## 2. 当前已经有的记忆能力

当前 memory 分成三层：

- `conversation_summary`
- `user_memory`
- `task_state`

当前这些记忆不只是 prompt window，而是持久化落库的。

### 2.1 conversation_summary

负责对话摘要，帮助减少历史消息压力。

### 2.2 user_memory

负责记录稳定偏好，例如：

- 语言偏好
- 时区偏好
- 回答风格

### 2.3 task_state

负责记录任务型回合的状态，例如：

- `goal`
- `current_skill`
- `current_step`
- `status`
- `artifacts_json`

## 3. 当前这些记忆什么时候会写入

### 3.1 普通成功回合后

`ChatServiceImpl` 成功完成一次回答后，会调用 `updateMemoryAfterTurn(...)`。

这意味着：

- 对话摘要会尝试更新
- 用户记忆会按价值更新
- 任务状态会在任务型回合下更新

### 3.2 skill 执行成功后

`ToolFollowupService` 会调用 `recordSkillExecution(...)`。

所以当前 skill 执行结果不是只出现在临时上下文里，而是会写进 `task_state`。

### 3.3 workflow 路径中

`AgentWorkflowServiceImpl` 和 `MultiAgentOrchestrator` 都会调用 `recordWorkflowProgress(...)`。

当前已经能记录这些阶段：

- `planned`
- `skill_executed`
- `answered`
- `skill_failed`
- multi-agent 下的 `specialists_completed`
- `synthesized`

## 4. 当前为什么还不是真正的 autonomous agent

关键问题不在“有没有 memory”，而在：

**当前 memory 更像记录器，不像控制器。**

也就是说：

- 它能把状态记下来
- 它能在下一轮 prompt 中作为上下文注入
- 但它不会自动驱动下一步执行

## 5. 当前单 Agent 真实行为

当前单 Agent 主路径更像：

1. 用户发起一轮请求
2. 命中 workflow 或 skill
3. 执行一次 skill 或一次 workflow
4. 生成一次 followup answer
5. 把结果和状态写入 memory
6. 当前回合结束

所以当前确实通常是：

**一次高层规划 -> 一次执行 -> 一次回答 -> 结束当前回合**

而不是：

- 执行后判断是否完成
- 若未完成，再自动规划下一步
- 再继续 skill B / skill C
- 直到任务完成再停

## 6. Skill 现在是多步还是单步

这里要区分两层：

### 6.1 skill 内部可以是多步

`DefaultSkillExecutor` 现在支持：

- `single_tool`
- `pipeline`

所以一个 skill 内部不一定只调一个 tool。

### 6.2 但 agent 回合通常只做一次高层决策

当前系统通常是：

- 先选中一个 skill
- 执行完这个 skill
- 生成答案
- 当前回合结束

所以不是“永远只调一个 tool”，而是：

**通常只做一次高层 skill/workflow 决策。**

## 7. Multi-Agent 当前状态

Multi-Agent 比单 Agent 更接近 workflow：

- 会选 specialist
- 支持并行或串行执行
- 会记录 workflow progress
- 最后由 manager 做 synthesis

但它本质上仍然是：

- 一次 team workflow
- 一次 specialist execution
- 一次 synthesis
- 一次结束

它也没有做成长程自治循环。

## 8. 当前最准确的对外表述

如果面试官问：

### Q1：你现在的 agent 有记忆吗？

可以直接答：

> 有，当前已经有 `conversation_summary / user_memory / task_state` 三层持久化记忆，skill 执行和 workflow 进度也会回写到 task state。

### Q2：那它是不是会自己判断任务完成并继续规划下一步？

最稳的答法是：

> 还不是。当前更准确的定位是“带持久化记忆和任务状态记录的单回合 skill/workflow agent”，而不是自主长程闭环 agent。当前 `task_state` 主要负责记录和上下文增强，还没有升级成任务完成判定和下一步自动推进的控制器。

## 9. 真正要变成 autonomous agent 还缺什么

如果后续要做成真正的自主任务型 Agent，最少还需要这几层：

### 9.1 TaskCompletionEvaluator

判断：

- 当前目标是否已经完成
- 当前结果是否满足用户要求
- 是否还需要下一步动作

### 9.2 NextStepPlanner

如果任务未完成，决定：

- 下一步继续什么 skill
- 是继续执行、追问、回退还是终止

### 9.3 TaskLoopController

负责循环控制：

- 最大步数
- 重试次数
- 超时
- 死循环保护
- 终止条件

### 9.4 State-Driven Execution

让 `task_state` 从“记录状态”升级成“驱动执行”：

- 当前阶段是什么
- 下一步应该做什么
- 什么条件下结束

这一步本质上就是从：

- `memory as context`

升级成：

- `memory as controller`

## 10. 最终建议

当前项目现在最真实、最稳的说法是：

- 已经有 memory
- 已经有 task state
- 已经能记录 skill 和 workflow progress
- 还没有真正的任务完成判定与自动续跑闭环

一句话总结：

**现在的 zlAI agent 是“有记忆的执行型 agent”，但还不是“有记忆且会自驱动完成长期任务的 autonomous agent”。**
