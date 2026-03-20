# Section-Aware 根因排查（2026-03-18）

## 结论

这两个章节题继续失败，根因已经确认：

- 不是 rerank 权重不够
- 不是 section-aware 路径没执行
- 而是 chunk 切分与 heading metadata 保存方式导致目标 section 没有稳定留下来

更准确地说：

1. chunk 是按固定长度滑窗切的，不是按 section 边界切的。
2. `chunk_metadata.headings` 只保存 chunk 最终停留时的 heading。
3. 如果一个 chunk 覆盖了多个 section，前面的 section 虽然正文还在 chunk 里，但 metadata 会被后面的 section 覆盖。
4. 这样一来，按 heading 做 section-aware 检索时，目标 section 根本查不到。

## 失败样本 1：`1.2 认证方式`

目标文档：`zlAI-v2 接口文档（详细版）`

数据库里 `doc_id = 02acff67-acaf-4c99-a029-e921ea339cee` 的首个相关 chunk：

- `rag_chunk.id = 138`
- `preview` 里已经能看到 `1.2 认证方...`
- 但 `chunk_metadata.headings` 记录的是：
  - `zlAI-v2 接口文档（详细版）`
  - `1. 通用约定`
  - `1.5 常见错误码`

这说明：

- `1.2 认证方式` 的正文内容确实在 chunk 里
- 但 metadata 里保留下来的 section 已经变成了后面的 `1.5 常见错误码`
- 所以按 heading 检索 `1.2 认证方式`，这个 chunk 不会命中

最后系统只能退化到别的高相关块，于是返回了：

- `7. 管理端接口（ROLE_ADMIN） > 7.4 Chat 管理`
- `7.5 日志管理`
- `7.6 RAG 管理`
- `7.7 系统设置`

## 失败样本 2：`1) chunkSize=800 ...`

目标文档：`RAG Follow-up Q&A (Code-Accurate, No Handwaving)`

数据库里 `doc_id = 9012aff1-1d9c-415e-b6f9-5fba4e3d06b1` 的首个相关 chunk：

- `rag_chunk.id = 204`
- `preview` 里已经能看到 `1) chunkSize=800 is character-bas...`
- 但 `chunk_metadata.headings` 记录的是：
  - `RAG Follow-up Q&A (Code-Accurate, No Handwaving)`
  - `2) You said history has sliding window. How are RAG chunks trimmed?`

同样说明：

- `1) chunkSize=800 ...` 的正文还在 chunk 里
- 但 metadata 已经被后面的 `2)` 标题覆盖
- 所以 section-aware heading 检索命不中该块

结果系统退化到语义最相近的另一篇文档 `RAG Full Call Chain (Code-Accurate)`，返回了：

- `8) Token limit and truncation strategy`
- `9) Direct answers to your exact questions`
- `6) chunkSize unit: chars or tokens?`

## 这说明什么

当前问题的本质不是“检索不会找 section”，而是：

**section 边界在入库阶段就已经丢失了。**

所以后面无论怎么调：

- rerank score
- heading boost
- 文档内搜索

都只能在错误或不完整的 metadata 上工作，效果上限很低。

## 下一步真正该改什么

下一步最值得改的不是继续调排序，而是改入库切块策略：

1. 先按 Markdown heading / section 边界切段
2. 再在 section 内做长度约束切块
3. 每个 chunk 的 metadata 要保留它真实所属的 section
4. 最好不要让一个 chunk 跨多个 section

这样 section-aware 检索才真正有可靠输入。

## 一句话结论

这次排查证明：

**失败根因在 ingest/chunking 阶段，不在 retrieval/rerank 阶段。**
