# RAG 失败样本分析与优化建议（2026-03-18）

## 1. 本次失败样本概览

这轮 `smoke` 评测一共失败了 2 条，全部来自 `section_summary`：

- `section-summary-1-1`
  - 文档：`api-interface-spec.md`
  - 问题：`《zlAI-v2 接口文档（详细版）》中“1.2 认证方式”这一节主要讲了什么？`
  - 结果：`sectionHit = false`
  - `contextCoverage = 0.1429`
  - `matchCoverage = 0.3929`

- `section-summary-4-1`
  - 文档：`RAG_QA_FOLLOWUP.md`
  - 问题：`《RAG Follow-up Q&A (Code-Accurate, No Handwaving)》中“1) chunkSize=800 is character-based. How do we guarantee prompt context limit?”这一节主要讲了什么？`
  - 结果：`sectionHit = false`
  - `contextCoverage = 0.7297`
  - `matchCoverage = 0.7838`

共同点很明显：

- 不是“完全没召回到相关内容”
- 而是“召回到了语义相近内容，但没有稳定命中期望 section 标题”

所以这是 **章节级精确定位问题**，不是文档级召回缺失问题。

## 2. 当前相关代码机制

当前系统在检索与排序上大致是：

- `resolveWholeDocumentContext(...)`
  - 先尝试 document-aware 命中整篇文档
- `hybridSearch(...)`
  - 向量检索 + 关键词检索混合
- `mergeHybridMatches(...)`
  - 用 `vectorWeight = 0.65`、`keywordWeight = 0.35` 融合
- `rerankScore(...)`
  - 再按规则做启发式加减分

相关位置：

- [RagServiceImpl.java](D:\Code\zlAI-v2\backend\src\main\java\com\harmony\backend\ai\rag\service\impl\RagServiceImpl.java)

当前 `rerankScore(...)` 里和 section 命中最相关的规则有：

- chunk 文本直接包含完整 query：`+0.22`
- 命中 must-have 关键词：`+0.14`，否则 `-0.08`
- 内容带 `[Section]` 前缀：`+0.05`
- metadata 里存在 `headings`：先 `+0.05`
- 每命中一个 heading keyword：额外 `+0.04`，上限 `+0.12`
- `blockType=heading`：`+0.12`
- `blockType=table`：`+0.07`
- `blockType=list`：`+0.04`
- `blockType=mixed`：`-0.02`
- 同时命中 vector + keyword：`+0.08`

这套逻辑说明：

当前确实已经在尝试利用 heading / section 信息，但力度仍然偏“温和加分”，而不是“标题命中优先”。

## 3. 失败样本一：`1.2 认证方式`

### 3.1 现象

正确目标 section 实际上就在文档最前面：

- `### 1.2 认证方式`

但最终命中的高分内容却偏到了：

- `7. 管理端接口（ROLE_ADMIN）`
- `7.4 Chat 管理 /api/admin/chats`

### 3.2 为什么会偏

这个问题里同时出现了这些强检索信号：

- `认证`
- `方式`
- `接口`
- 文档标题 `zlAI-v2 接口文档（详细版）`

而在当前文档里，后面的管理端接口块也会大量重复出现：

- `接口`
- `admin`
- `auth`
- `chat`
- 路径与结构化列表

对当前混合检索来说，会产生两个问题：

1. 关键词面太宽
   当前 `extractKeywords(...)` 是规则式抽取，不是严格标题级 parser。
   所以像“接口”“认证”“方式”这种词，会让多个 chunk 都有命中机会。

2. heading 奖励还不够强
   当前 heading 相关奖励最多也就是：
   - 有 headings：`+0.05`
   - heading 命中最多：`+0.12`
   - 若 blockType 是 heading 再加：`+0.12`

   这对于 section 题来说，还不足以压过其他在正文上更“热词密集”的 chunk。

3. 当前 section 校验是标题包含判断
   评测脚本要求 `expectedSections` 中的标题文本最终要真实出现在 `context + matches` 里。
   这意味着即使召回内容在语义上说对了，但只要没有把 `1.2 认证方式` 这一标题带出来，也会判成 `sectionHit=false`。

### 3.3 工程判断

这个失败不是“认证相关信息找不到”，而是：

- 系统对 `section_title exact match` 的优先级还不够高
- 章节题会被语义相关但标题不精确的接口块抢分

## 4. 失败样本二：`chunkSize=800` 那一节

### 4.1 现象

问题问的是：

- `1) chunkSize=800 is character-based. How do we guarantee prompt context limit?`

但最终命中的内容偏到了另一个文档：

- `RAG Full Call Chain (Code-Accurate)`
- `8) Token limit and truncation strategy (what exists vs missing)`

这条失败和第一条不同，它不是低相关，而是 **跨文档高相关干扰**。

### 4.2 为什么会偏

原因很直接：

1. 两份文档的主题高度重合
   `RAG_QA_FOLLOWUP.md` 和 `RAG_FLOW_DEEP_DIVE.md` 都在讲：
   - chunk size
  - token limit
   - context truncation
   - sliding window

   所以向量检索会天然认为它们很接近。

2. 语义相近块比标题精确块更容易得高分
   当前系统最终排序不是 cross-encoder，而是：
   - vector score
   - keyword score
   - heuristic rerank

   如果另一个文档的正文里对“token limit / truncation strategy”讲得更集中，它就可能在融合排序里赢过真正的目标 section。

3. section 标题的“强约束”还不存在
   现在 heading 命中只是加分项，不是硬过滤条件。
   所以即使 query 里已经明确带了完整标题，系统也仍然允许别的高语义相似块排到前面。

### 4.3 从数据上怎么理解

这条失败样本的：

- `contextCoverage = 0.7297`
- `matchCoverage = 0.7838`

并不低。

这说明：

- 召回结果里已经包含了大量正确知识点
- 失败真正发生在“标题精确命中”和“最终 section 归属”上

所以这不是“回答错得很离谱”，而是“检索和排序没有优先保护目标 heading”。

## 5. 当前最值得做的优化

### 5.1 优先级最高：section-intent 专项加权

如果 query 明确包含：

- `“1.2 认证方式”`
- `“1.3 统一返回结构”`
- `“2.1 入库流程（纯文本/Markdown）”`

这种 section 标题模式，建议在检索阶段增加一个分支：

- 先抽取目标 section title
- 对 `chunk_metadata.headings` 做更强匹配
- 命中 section title 的 chunk 直接额外给一档明显更高的分
- 或者先从命中该 heading 的 chunk 中选候选，再做普通 rerank

也就是说：

**section 题不应该和普通开放式问法完全共享同一套排序优先级。**

### 5.2 第二优先级：标题 exact match 保护

现在 heading 命中只是 `+0.04 ~ +0.12` 级别，偏弱。

更合理的做法是：

- query 中若出现完整 heading 文本
- 且 chunk metadata 的 headings 中也出现完整 heading
- 直接给一个明显更大的 boost，例如 `+0.25 ~ +0.4`

这样可以让“标题精确命中”真正压过泛语义相似。

### 5.3 第三优先级：跨文档主题冲突保护

第二个失败样本反映出：

- 两篇 RAG 文档主题非常近
- 语义检索会把它们互相干扰

可以考虑：

- 当 query 里带了明确文档标题时，先做 document-aware title 命中
- 或者在 hybrid merge 后，对同文档 chunk 做一次聚合加权
- 让“文档标题命中 + section 标题命中”的组合具有更高优先级

## 6. 面试里怎么讲这 2 个失败点

最稳的说法是：

> 我真实跑过一轮 smoke 评测，20 条样本通过了 18 条，失败的 2 条都不是文档级召回失败，而是章节级精确定位失败。也就是说系统对整篇总结和文件级检索已经比较稳定，但当 query 明确指定某个 section 标题时，当前 heading 权重还不够强，会被语义相近但标题不完全匹配的 chunk 抢掉排序。这说明我的下一步优化重点不应该是盲目提高向量召回，而是为 section-intent 增加更强的标题精确匹配和跨文档干扰抑制。 

## 7. 一句话结论

这轮失败样本告诉我们的不是“当前 RAG 不行”，而是：

**当前系统已经过了文档级可用线，接下来最值得优化的是章节级精确命中，而不是继续泛化地讨论召回。**

