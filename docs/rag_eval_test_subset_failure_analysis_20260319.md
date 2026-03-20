# RAG 测试集子集失败样本分析（2026-03-19）

## 1. 本次测试范围

- 评测文件：`test/rag-eval/output-java-study-resource/rag-eval-ingested96.jsonl`
- 实际样本数：`168`
- 对应文档数：`48`
- 测试结果：`153 / 168` 通过，`passRate = 0.9107`

这次不是 `smoke` 小集合，而是从更大的 `java-study-resource` 评测集中抽出的一个可真实入库、可真实执行的测试子集。

## 2. 失败分布

失败总数：`15`

按题型分布：

- `section_summary`：`13`
- `document_summary`：`1`
- `file_name_summary`：`1`

结论：

当前系统的主要剩余问题不是全文总结路由失效，而是 **section 级问题的稳定命中率还不够高**。

## 3. 高风险文档

失败较集中的文档如下：

- `Java八股\\Java基础.md`：`2`
- `算法\\4.图.md`：`2`
- `算法\\7.算法题.md`：`2`

其余各 `1` 条：

- `AI智能体\\提示词工程指南\\2、提示技术.md`
- `AI智能体\\提示词工程指南\\3、提示应用.md`
- `Java八股\\WebSearchAPI.md`
- `vpn.md`
- `期末复习\\面向对象-真题分类版+简答题答案版.md`
- `期末复习\\众智科学.md`
- `算法\\3.查找.md`
- `算法\\5.字符串.md`
- `算法\\6.背景.md`

## 4. 失败样本清单

### batch-01

- `section-summary-3-2`
  - 文档：`AI智能体\\提示词工程指南\\2、提示技术.md`
  - 类型：`section_summary`
  - 分数：`0.1432`
- `section-summary-4-1`
  - 文档：`AI智能体\\提示词工程指南\\3、提示应用.md`
  - 类型：`section_summary`
  - 分数：`0.2298`
- `section-summary-5-1`
  - 文档：`Java八股\\Java基础.md`
  - 类型：`section_summary`
  - 分数：`0.3345`
- `section-summary-5-2`
  - 文档：`Java八股\\Java基础.md`
  - 类型：`section_summary`
  - 分数：`0.2338`

### batch-03

- `section-summary-9-2`
  - 文档：`Java八股\\WebSearchAPI.md`
  - 类型：`section_summary`
  - 分数：`0.2873`

### batch-06

- `section-summary-26-1`
  - 文档：`vpn.md`
  - 类型：`section_summary`
  - 分数：`0.0565`

### batch-08

- `section-summary-46-2`
  - 文档：`期末复习\\面向对象-真题分类版+简答题答案版.md`
  - 类型：`section_summary`
  - 分数：`0.2916`

### batch-09

- `section-summary-48-2`
  - 文档：`期末复习\\众智科学.md`
  - 类型：`section_summary`
  - 分数：`0.0411`
- `section-summary-51-1`
  - 文档：`算法\\3.查找.md`
  - 类型：`section_summary`
  - 分数：`0.3271`
- `doc-summary-52`
  - 文档：`算法\\4.图.md`
  - 类型：`document_summary`
  - 分数：`0.2581`
- `file-name-summary-52`
  - 文档：`算法\\4.图.md`
  - 类型：`file_name_summary`
  - 分数：`0.2000`
- `section-summary-53-1`
  - 文档：`算法\\5.字符串.md`
  - 类型：`section_summary`
  - 分数：`0.1536`
- `section-summary-54-1`
  - 文档：`算法\\6.背景.md`
  - 类型：`section_summary`
  - 分数：`0.1129`
- `section-summary-55-1`
  - 文档：`算法\\7.算法题.md`
  - 类型：`section_summary`
  - 分数：`0.2182`
- `section-summary-55-2`
  - 文档：`算法\\7.算法题.md`
  - 类型：`section_summary`
  - 分数：`0.1125`

## 5. 原因归纳

### 5.1 主要问题仍然是 section 命中

`13 / 15` 失败来自 `section_summary`，说明：

- whole-document 路由已经基本稳定
- 文件级总结能力整体可用
- 主要短板在“指定章节”检索与上下文拼装

### 5.2 教学型长文档更容易失败

像 `Java基础.md`、`算法题.md`、`面向对象-真题分类版+简答题答案版.md` 这类文档通常有：

- 章节很多
- 标题相似
- 正文里重复出现相近术语
- 局部知识点之间语义距离不大

这种情况下，哪怕文档级命中了，也可能在 section 级别被相邻章节抢分。

### 5.3 算法类文档暴露了 summary 盲区

`算法\\4.图.md` 同时挂了：

- `document_summary`
- `file_name_summary`

这说明当前的全文总结路由虽然对多数文档有效，但对这类“标题很短、内容分节密、知识点并列”的文档，whole-document context 还不够稳。

更具体地说，问题可能在于：

- 文档标题过短，候选文档区分度有限
- 文档内部 chunk 太分散，summary 时拼出的上下文不够全面
- 算法文档各章节术语密度高，导致局部 chunk 抢占了整体摘要位

## 6. 当前可下的工程结论

可以比较稳地说：

1. 当前 RAG 在测试集子集上已经达到了 `91.07%` 的通过率。
2. 剩余失败样本高度集中在 `section_summary`，说明问题已经从“整体检索能力”收敛到了“章节级精确命中”。
3. 全文总结链路已经大体可用，但在算法类、结构并列型文档上还存在少量盲区。

## 7. 下一步优化方向

### 7.1 对 section 题进一步强化标题约束

可以继续增强：

- heading exact match boost
- heading path prefix match
- 同文档内 section 候选优先级

目标是让“指定章节问题”优先命中真正的 heading 块，而不是语义相近的邻接块。

### 7.2 对长教学文档做 section tree aware 检索

当前 markdown section-aware chunking 已经有了，但还可以继续做：

- 记录更完整的 heading path
- 支持父标题 / 子标题联合命中
- 对编号标题如 `1.2`、`2.3.1` 做更强约束

这对课程讲义、八股文档、算法笔记会很有帮助。

### 7.3 对算法类文档增加 whole-document summary 保护

对 `document_summary` / `file_name_summary` 可以进一步做：

- 当 query 明显是“总结全文”时，强制扩大 context 覆盖范围
- 降低局部 chunk 对全文总结的干扰
- 对短标题文档增加 doc-level recall protection

## 8. 面试时的推荐说法

可以直接这样说：

> 我后来没有只停留在 smoke 集，而是把更大的评测集切出一个真实可执行的测试子集，先把对应 48 份文档真实入库，再跑了 168 条样本。结果是 153 条通过，整体通过率 91.07%。剩余 15 条失败里有 13 条都是 section 题，所以说明当前系统的大方向是对的，问题已经收敛到章节级命中精度，而不是全文检索或摘要能力整体失效。
