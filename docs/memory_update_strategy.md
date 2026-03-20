# Memory 更新策略说明

## 1. 背景

原来的记忆更新入口在聊天成功回合结束后统一触发，也就是 `rememberSuccessfulTurn(...) -> agentMemoryService.updateMemoryAfterTurn(...)`。这个设计的优点是简单，所有成功回复都会进入同一条记忆更新链路，功能上容易跑通。

但继续往工程化方向看，会有两个明显问题：

- 不是所有回合都值得更新 memory
- 不同类型的 memory，更新频率应该不同

具体来说：

- `conversation_summary` 适合低频、批量式刷新
- `user_memory` 只应该在出现稳定偏好信号时更新
- `task_state` 应该围绕任务推进来更新，而不是普通闲聊也频繁覆盖

如果所有成功回合都按同样力度更新，很容易带来额外模型调用、数据库写入噪音，以及“记忆价值不高但更新很频繁”的问题。

## 2. 原方案的实际行为

先澄清一点：原方案里 `conversation_summary` 不是“超过阈值后每一轮都更新”。

它原本的逻辑是：

- 读取最近一段消息
- 找到上次摘要对应的 `last_message_id`
- 统计从上次摘要之后新增了多少条消息
- 只有当新增消息数达到 `summaryRefreshEveryMessages` 时才刷新摘要

默认值是：

- `summaryRefreshEveryMessages = 6`

所以摘要本身其实已经有一个“按消息增量刷新”的门槛。

真正比较激进的是另外两类：

- `user_memory`：原来每个成功回合都会尝试跑一次模型抽取和规则更新
- `task_state`：原来每个成功回合都会把 `goal/current_step/artifacts` 覆盖回库

这就比较像“功能先跑通”的方案，而不太像真实线上系统的更新策略。

## 3. 这次改动的目标

这次调整的目标不是推翻 memory 机制，而是把它从“按回合刷新”改成“按价值刷新”。

核心原则有三条：

1. 只有值得更新时才更新
2. 不同 memory 类型使用不同刷新门槛
3. 尽量保留现有调用链，降低改动风险

所以这次没有改外部调用入口，还是保留 `ChatServiceImpl -> rememberSuccessfulTurn -> AgentMemoryServiceImpl.updateMemoryAfterTurn(...)` 这条路径，只在 `AgentMemoryServiceImpl` 内部重构策略。

## 4. 新策略概览

### 4.1 `conversation_summary`

新策略：

- 仍然保留“消息增量达到阈值才刷新”的逻辑
- 新增最小刷新间隔 `summary-min-refresh-minutes`
- 如果消息积压很多，达到 2 倍消息阈值，则允许跳过时间冷却强制刷新
- 如果新摘要和旧摘要内容基本相同，也不重复写库

这意味着摘要更新现在是：

- 消息增量门槛
- 时间门槛
- 内容去重

三层一起控制。

### 4.2 `user_memory`

新策略：

- 先判断用户输入里是否真的出现稳定偏好信号
- 只有命中偏好信号时，才尝试做模型抽取
- 如果 memory 值没有变化，且置信度没有提升，且冷却时间没到，就不写库

也就是说，现在不会因为每次普通聊天都去抽用户偏好。

只有出现像下面这些信号才会触发：

- 语言偏好，比如“请用中文”“answer in english”
- 时区偏好，比如“北京时间”“Asia/Shanghai”
- 回答风格，比如“简洁一点”“详细展开”

### 4.3 `task_state`

新策略：

- 只有当前回合更像“任务型回合”，或者模型提取出了结构化任务产物时，才考虑更新 task state
- 如果只是普通闲聊，不会为了一个普通回答就新建 task state
- 对已有 task state，如果关键字段没变化，且 artifacts 变化不大，就不落库
- 对低价值变化增加了最小刷新间隔 `task-state-min-refresh-seconds`

这样 task state 更像任务推进状态，而不是聊天日志副本。

## 5. 新增配置项

这次把关键阈值也显式写进了配置文件 [application.yaml](D:\Code\zlAI-v2\backend\src\main\resources\application.yaml)。

当前配置如下：

```yaml
app:
  memory:
    summary-model: deepseek-chat
    summary-refresh-every-messages: 6
    summary-min-refresh-minutes: 15
    user-memory-model-enabled: true
    user-memory-min-refresh-minutes: 180
    user-memory-min-prompt-length: 18
    task-state-model-enabled: true
    task-state-min-refresh-seconds: 45
```

这些参数的含义是：

- `summary-refresh-every-messages`
  摘要至少累积多少条新增消息才考虑刷新

- `summary-min-refresh-minutes`
  摘要刷新最小时间间隔，避免短时间内频繁重算

- `user-memory-model-enabled`
  是否允许用模型抽取用户稳定偏好

- `user-memory-min-refresh-minutes`
  同一个 user memory key 的最小刷新间隔

- `user-memory-min-prompt-length`
  用户输入长度太短时，不值得做偏好抽取

- `task-state-model-enabled`
  是否允许从回答中抽取结构化任务产物

- `task-state-min-refresh-seconds`
  task state 对低价值变化的最小刷新间隔

## 6. 代码实现位置

核心实现都在 [AgentMemoryServiceImpl.java](D:\Code\zlAI-v2\backend\src\main\java\com\harmony\backend\modules\chat\service\support\AgentMemoryServiceImpl.java)。

关键位置如下：

- `updateMemoryAfterTurn(...)`
  入口位置：按新的“按价值更新”策略分发

- `maybeUpsertConversationSummary(...)`
  摘要刷新策略

- `containsUserMemorySignal(...)`
  用户偏好触发门槛

- `maybeInferAndUpsertUserMemory(...)`
  用户偏好提取与去重更新

- `maybeUpsertAnsweredTaskState(...)`
  task state 更新入口

- `shouldPersistAnsweredTaskState(...)`
  task state 是否真的需要写库的判断逻辑

- `isCooldownElapsed(...)`
  各类 memory 公共冷却逻辑

## 7. 为什么这个方案更符合实际工程

### 7.1 降低无意义模型调用

原来 `user_memory` 每个成功回合都可能跑模型抽取。现在只有命中稳定偏好信号时才会触发，能显著减少无意义调用。

### 7.2 降低无意义写库

原来 `task_state` 基本每轮覆盖。现在如果只是普通回答、内容没变化、或者冷却时间没到，就不会重复写数据库。

### 7.3 更符合不同 memory 的职责

`summary`、`user_memory`、`task_state` 的职责本来就不同，所以刷新策略也应该不同：

- `summary` 偏长期压缩记忆
- `user_memory` 偏稳定偏好
- `task_state` 偏任务推进状态

按同样频率更新它们，本身就不合理。

### 7.4 对现有主链路侵入小

这次没有重构外部调用链，也没有新增表结构，主要是内部策略收敛，所以风险比较低。

## 8. 目前策略的边界

这版已经比原来更合理，但还不是最终形态，仍然有一些边界：

- 用户偏好信号还是以规则触发为主，不是更复杂的长期行为建模
- task state 的“任务型回合判断”目前是启发式关键词判断，不是完整 planner 驱动
- 摘要刷新目前还是基于消息数和时间门槛，没有引入更细的成本预算控制

这些边界是可接受的，因为当前目标是低风险优化，而不是把整个 memory 系统重写一遍。

## 9. 如果继续优化，可以往哪里走

后续如果继续升级，我会优先考虑这几个方向：

### 9.1 给 memory 引入分层预算

比如按会话活跃度、模型成本、用户等级动态控制摘要和偏好抽取频率。

### 9.2 用更强的事件驱动更新机制

例如只在这些事件上强制更新：

- 任务开始
- 任务完成
- 用户明确表达偏好
- 工作流状态切换
- 长对话摘要需要压缩时

### 9.3 引入异步 memory 写入

当前还是同步调用链中的更新逻辑。后续如果流量更大，可以把一部分低优先级 memory 更新改成异步队列消费。

### 9.4 更细地拆 user memory 类型

例如区分：

- 稳定偏好
- 临时偏好
- 当前会话偏好

这样不同层的记忆就不会混在一起。

## 10. 面试时怎么解释这次改动

如果面试官问你“你怎么优化 memory 更新频率”，可以这样答：

我把原来偏按回合刷新的 memory 逻辑，改成了按价值刷新。摘要保留消息增量门槛，同时增加最小刷新时间；用户记忆只有在用户明确表达稳定偏好时才会触发抽取，并且值没变不会重复写；任务状态只有在任务型回合或者抽出了结构化任务产物时才更新，还加了去抖和最小刷新间隔。这样更符合真实工程系统，不会为了每一轮普通对话都做高成本或低价值的 memory 更新。

## 11. 验证结果

这次改动后，我做了后端编译验证：

```powershell
mvn -q -DskipTests=true compile
```

编译通过，说明当前改动没有破坏主链路的 Java 编译。

这次没有补自动化测试，如果后面要继续完善，建议给 memory 更新策略补针对性的单元测试或集成测试。
