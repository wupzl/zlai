# Real Skills Design

## 1. 结论

当前 `zlAI` 项目里的 `skill` 已经具备**结构化执行层**能力，但还不是 OpenAI / Codex / Claude 那种真正的 **markdown-native Skills**。

目前准确状态是：

- 已实现：结构化 skill runtime
- 已实现：`SKILL.md -> SkillUpsertRequest` 导入转换
- 已实现：管理端 markdown skill 导入页面
- 未实现：运行时 section 级按需加载与渐进式披露

所以当前不是“真实 Skills 已完成”，而是：

**已经有可执行 skill 层，并具备向真实 Skills 演进的基础。**

## 2. 当前已实现能力

### 2.1 结构化 Skill Runtime

当前 skill 运行时模型包括：

- `key`
- `name`
- `description`
- `toolKeys`
- `executionMode`
- `inputSchema`
- `stepConfig`

当前能力：

- planner 选择 skill
- executor 执行 skill
- single tool / pipeline 两种模式
- billing / memory / followup 接回主链路
- multi-agent specialist 也能走 skill 路径

### 2.2 Markdown Importer

当前 importer 已能把结构化 `SKILL.md` 转成托管 skill。

支持解析：

- title / name / key / description
- tools
- execution mode
- inputs / parameters
- steps / workflow

支持导入方式：

- 文本粘贴导入
- markdown 文件上传导入

但当前 importer 的作用是：

**把 markdown 转成结构化 skill 配置。**

不是让 agent 运行时直接阅读整篇 markdown 并按需展开。

## 3. 为什么现在还不算真实 Skills

真实 Skills 至少要满足：

1. `SKILL.md` 本身是能力说明书
2. 只在命中时加载
3. 先加载最小必要 section
4. 不够时再继续披露更多内容
5. 以 token 预算和上下文效率为导向

当前项目还缺：

- `SkillDocument / SkillSection` 存储层
- manifest 与原始 markdown 双层模型
- planner 命中后的 section-level loader
- token budget 驱动的 prompt assembler
- 失败重试时的二段或三段加载机制

## 4. 为什么当前阶段不建议直接全做完

对当前项目和当前简历阶段来说，直接把真实 Skills 一次性做完，投入产出比不高。

原因：

- 当前简历主成果已经成立：skill runtime、RAG、memory、评测、部署
- 完整真实 Skills 会引入一整套新问题：版本管理、一致性、section 切分、预算控制、多 skill 冲突
- 面试里“知道差距并给出演进路线”比“硬做半套系统”更稳

## 5. 推荐演进架构

### 5.1 Runtime Layer

继续保留当前结构化 skill，用于稳定执行。

建议保留或扩展：

- `SkillManifest`
- `SkillExecutionProfile`
- `fallbackPolicy`
- `entrySections`

### 5.2 Document Layer

新增 skill 文档层，用于真实 SOP 与按需加载。

建议结构：

- `SkillDocument`
  - `skillKey`
  - `version`
  - `rawMarkdown`
  - `sourceName`
- `SkillSection`
  - `skillKey`
  - `sectionType`
  - `heading`
  - `content`
  - `tokenEstimate`
  - `priority`

推荐 section 类型：

- `overview`
- `when_to_use`
- `when_not_to_use`
- `inputs`
- `workflow`
- `examples`
- `caveats`
- `fallbacks`

## 6. 推荐运行流程

### 6.1 导入阶段

`SKILL.md` 导入后建议同时产出：

1. 结构化 `SkillUpsertRequest`
2. `SkillDocument + SkillSection`

这样就能把：

- 执行配置
- SOP 文档

拆开管理。

### 6.2 规划阶段

`SkillPlanner` 第一轮只用：

- manifest metadata
- trigger hints
- description

先做 skill selection，不直接把整篇 markdown 塞进上下文。

### 6.3 加载阶段

命中 skill 后，才触发 `SkillLoader`。

推荐加载顺序：

1. `overview + when_to_use`
2. 若任务复杂，再补 `workflow`
3. 若仍不够，再补 `examples / caveats`

### 6.4 执行阶段

执行层仍然优先用当前结构化 runtime config。

markdown skill 的作用是：

- 指导 agent 更专业地组织任务
- 提供 workflow 与边界条件
- 在复杂场景中补充 SOP

而不是取代结构化 execution contract。

## 7. 最小可行 PoC

如果只做一个轻量原型，建议范围控制在：

- 导入时切分 `overview / workflow / examples`
- skill 命中后默认只注入 `overview + workflow`
- 仅在 followup 或失败重试时补 `examples`
- 不改现有 `SkillExecutor / ToolExecutor / Billing / Memory` 主链路

这样改动风险最小，也最符合当前模块边界。

## 8. 面试时怎么讲

推荐表述：

> 我当前项目里的 skill 已经不是简单 prompt，而是结构化的执行编排层，包含 planner、candidate、input schema、tool pipeline 和 followup。  
> 同时我已经做了 markdown importer 和管理端导入页面，可以把 `SKILL.md` 转成托管 skill。  
> 但它还不是 OpenAI 那种完整的 markdown-native Skills，因为运行时还没有做 section 级按需加载和渐进式披露。  
> 如果继续演进，我会把 skill 拆成 runtime config 和 markdown SOP document 两层，让 planner 先用 manifest 选 skill，再按 token 预算逐段加载 workflow 和 examples。

## 9. 最终建议

当前阶段最合理的策略是：

- 保持当前 skill runtime 作为主成果
- 把真实 Skills 明确定位为下一阶段演进方向
- 如需补实现，优先做轻量 PoC，不要为面试临时把整套系统硬做完

一句话总结：

**当前项目已经有可执行 skill 层，也能导入 `SKILL.md`，但还没有完整的 markdown-native Skills；最优路线是在现有 runtime 基础上逐步演进到按需加载的真实 Skills。**
