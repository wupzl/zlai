# RAG 真实测试记录（2026-03-18）

## 1. 测试目的

这次测试不是继续停留在“工具链已经搭好”的阶段，而是实际把评测集对应文档重新入库到当前 RAG 库，然后真实调用后端接口 `/api/rag/query` 跑一轮离线评测，拿到一份可以作为 baseline 的结果。

## 2. 测试前状态

测试前先检查了当前环境：

- 后端：本地已启动并可响应 `http://127.0.0.1:8080`
- MySQL：可连通
- Redis：可连通
- PostgreSQL / pgvector：可连通
- RAG 文档库：原本主要是历史文档，标题集中在 `readme.txt` 和 `Java 高级特性.md`

这里有一个关键问题：

`output-smoke/rag-eval.jsonl` 这套 smoke 评测集期待的文档是：

- `zlAI-v2 接口文档（详细版）`
- `zlAI-v2 接口文档 + RAG/Agent 设计说明`
- `zlAI-v2 RAG 与 Agent 实现说明`
- `RAG Full Call Chain (Code-Accurate)`
- `RAG Follow-up Q&A (Code-Accurate, No Handwaving)`

但这些文档在当前 RAG 库里原本并不存在，所以最开始直接跑评测得到的是 `5/5 failed`。这个结果不能用于评价当前检索质量，因为它本质上是“评测集和入库语料不对齐”。

## 3. 入库动作

我随后把 `test/rag-eval/workspace-smoke/normalized` 目录下的 5 份 Markdown 文档，通过接口：

- `POST /api/rag/ingest/markdown`

重新入库到当前 RAG 库中。

成功入库的文档与 `docId`：

- `zlAI-v2 接口文档（详细版）` -> `02acff67-acaf-4c99-a029-e921ea339cee`
- `zlAI-v2 接口文档 + RAG/Agent 设计说明` -> `27b035e0-e74b-46c3-b265-a4ae5c16adbd`
- `zlAI-v2 RAG 与 Agent 实现说明` -> `99875886-e3bf-4fbd-8626-8be82ccf5b50`
- `RAG Full Call Chain (Code-Accurate)` -> `f12ffc16-41ee-4e74-b84f-9a78286253f3`
- `RAG Follow-up Q&A (Code-Accurate, No Handwaving)` -> `9012aff1-1d9c-415e-b6f9-5fba4e3d06b1`

入库后数据库计数从原来的历史语料基础上增加，最终 `rag_document` 总数变为 `50`。

## 4. 评测执行方式

运行脚本：

- [run-rag-eval.ps1](D:\Code\zlAI-v2\test\rag-eval\run-rag-eval.ps1)

输入评测集：

- [rag-eval.jsonl](D:\Code\zlAI-v2\test\rag-eval\output-smoke\rag-eval.jsonl)

输出结果：

- [rag-eval-summary-20260318-125958.json](D:\Code\zlAI-v2\test\rag-eval\results\rag-eval-summary-20260318-125958.json)
- [rag-eval-result-20260318-125958.jsonl](D:\Code\zlAI-v2\test\rag-eval\results\rag-eval-result-20260318-125958.jsonl)

评测脚本的打分方式是：

- 对 `/api/rag/query` 返回的 `context` 做 `contextCoverage`
- 对 `matches` 拼接文本做 `matchCoverage`
- 对期望章节标题做 `sectionHit`
- 最终按：
  - `score = contextCoverage * 0.7 + matchCoverage * 0.3 + sectionHitBonus(0.1)`
- 通过条件：
  - 没有请求错误
  - `contextCoverage >= 0.35` 或 `matchCoverage >= 0.45`
  - `sectionHit = true`

## 5. 真实测试结果

整体结果：

- `sampleCount = 20`
- `passed = 18`
- `failed = 2`
- `passRate = 0.90`
- `topK = 8`

按样本类型拆分：

- `document_summary`
  - `total = 5`
  - `passed = 5`
  - `passRate = 1.00`
  - `avgContextCoverage = 1.0000`
  - `avgMatchCoverage = 0.8310`
  - `avgScore = 1.0493`

- `file_name_summary`
  - `total = 5`
  - `passed = 5`
  - `passRate = 1.00`
  - `avgContextCoverage = 1.0000`
  - `avgMatchCoverage = 0.7667`
  - `avgScore = 1.0300`

- `section_summary`
  - `total = 10`
  - `passed = 8`
  - `passRate = 0.80`
  - `avgContextCoverage = 0.7377`
  - `avgMatchCoverage = 0.8437`
  - `avgScore = 0.8495`

## 6. 结果解读

这轮结果说明几件事：

1. 当前 document-aware + hybrid retrieval + heuristic rerank 的整体方案，对“整篇总结类”和“按文件名总结类”问题已经比较稳定。

2. 当前真正的短板在章节级命中，也就是：
   - 问题里已经明确点出某个 section / heading
   - 但最后返回的高分 chunk 仍有可能被其他主题相近但标题不完全对应的 chunk 抢走

3. 所以当前系统更准确的评价不是“整体检索不行”，而是：
   - 文档级与整篇级能力已经比较稳
   - 章节级精确定位还需要继续优化

## 7. 这次测试的工程价值

这轮测试最大的价值不是单纯拿到一个 `0.90`，而是把评测链路真正闭环了：

- 有固定评测集
- 有真实入库语料
- 有真实在线接口调用结果
- 有可复跑的结果文件
- 可以把这轮结果作为后续优化的 baseline

也就是说，现在终于可以做真正的 before / after 对比了。

## 8. 后续正确对比方式

后续如果调整这些策略：

- `mmrLambda`
- `vectorWeight / keywordWeight`
- `rerankScore(...)`
- chunk 切分策略
- heading / section 加权逻辑

就应该始终使用同一份评测集重新跑：

- [rag-eval.jsonl](D:\Code\zlAI-v2\test\rag-eval\output-smoke\rag-eval.jsonl)

并重点比较：

- 总体 `passRate`
- `section_summary.passRate`
- `avgContextCoverage`
- `avgMatchCoverage`
- 失败样本数量和分布是否收敛

## 9. 面试里可以怎么讲

最稳的说法是：

> 我不只是搭了离线 RAG 评测工具链，还实际把 smoke 评测集对应文档重新入库，在本地后端上真实跑过一轮接口级评测。当前 20 条 smoke 样本的总体通过率是 90%，其中整篇总结和文件级总结是 100%，章节级问题是 80%。所以我现在对系统的判断不是泛泛地说“效果还可以”，而是明确知道它目前强在文档级召回，弱在章节级精确命中。
