# 当前 RAG 检索与 rerank 策略说明

## 1. 先说结论

当前项目里的 RAG 检索策略，**不是单纯只有 MMR**，也**不是接了外部大模型 reranker**。

更准确地说，当前代码里的链路是：

1. 先尝试 `document-aware` 文件级召回
2. 如果没命中文件级场景，再走 `search(...)`
3. `search(...)` 默认会走 `hybridSearch(...)`，因为 `app.rag.search.hybrid-enabled = true`
4. `hybridSearch(...)` 内部会同时做：
   - 向量检索支路
   - 关键词检索支路
5. 向量检索支路里，默认策略还是 `mmr`，因为 `app.rag.search.strategy = mmr`
6. 最后把向量结果和关键词结果合并，再用一套后端启发式 `rerankScore(...)` 做最终排序

所以如果面试官问“你现在的 rerank 还是 MMR 吗”，**最准确的回答是：不是纯 MMR；MMR 还在，但它只负责向量支路里的候选选择，最终结果是 hybrid merge + heuristic rerank。**

## 2. 代码层面的真实路径

### 2.1 配置默认值

配置文件 [application.yaml](D:\Code\zlAI-v2\backend\src\main\resources\application.yaml) 里当前是：

```yaml
app:
  rag:
    search:
      strategy: mmr
```

而 [RagProperties.java](D:\Code\zlAI-v2\backend\src\main\java\com\harmony\backend\ai\rag\config\RagProperties.java) 里默认值是：

- `strategy = "mmr"`
- `hybridEnabled = true`
- `mmrLambda = 0.7`
- `mmrCandidateMultiplier = 4`
- `vectorWeight = 0.65`
- `keywordWeight = 0.35`

这意味着：

- MMR 仍然是默认向量策略
- 但整个检索入口默认是 hybrid 模式，不是纯向量模式

### 2.2 `search(...)` 的实际入口

在 [RagServiceImpl.java](D:\Code\zlAI-v2\backend\src\main\java\com\harmony\backend\ai\rag\service\impl\RagServiceImpl.java) 里：

- `search(...)` 会先读 `properties.getSearch()`
- 如果 `search.isHybridEnabled()` 为真，就直接走 `hybridSearch(...)`
- 只有 hybrid 关闭时，才走 `vectorSearch(...)`

所以在当前配置下，默认不是纯 MMR 检索，而是 **hybrid retrieval**。

## 3. MMR 现在具体在哪一层

MMR 目前在 **vector 检索支路** 里使用。

也就是：

- `hybridSearch(...)`
  - 先调用 `vectorSearch(...)`
  - 再调用 `keywordSearch(...)`
  - 最后调用 `mergeHybridMatches(...)`

而在 `vectorSearch(...)` 里：

- 如果 `search.strategy == mmr`
- 就会进入 `mmrSearch(...)`

所以 MMR 现在的职责是：

- 先从 pgvector 拉一个向量候选池
- 然后在候选池里做多样性选择
- 避免纯 topK 相似度导致内容重复

换句话说：

**MMR 现在是向量召回阶段的“候选选择/多样性控制”策略，不是最终总排序策略。**

## 4. 当前最终 rerank 是什么

当前最终 rerank 不是外部 reranker 模型，而是项目里自己实现的一套 **启发式后端 rerank**。

核心代码在：

- `mergeHybridMatches(...)`
- `rerankScore(...)`

这套逻辑大致做了三件事：

### 4.1 先合并向量分数和关键词分数

在 `mergeHybridMatches(...)` 里，会对同一内容块做统一 key 合并，然后拿到：

- `vectorScore`
- `keywordScore`

默认权重是：

- `vectorWeight = 0.65`
- `keywordWeight = 0.35`

### 4.2 再叠加启发式 rerank 分

`rerankScore(...)` 会额外看这些信号：

- 查询词是否直接命中内容
- must-have 关键词是否命中
- 是否带 `[Section]` 这样的结构标记
- `chunk_metadata` 里的 `blockType`
- `headings` 是否和 query 或关键词匹配
- 内容块的序号位置等结构信息

这说明当前 rerank 更接近：

**“结构感知 + 关键词感知 + metadata 加权”的启发式重排。**

### 4.3 最终分数

最终分数是：

- `vectorScore * vectorWeight`
- `+ keywordScore * keywordWeight`
- `+ rerankScore(...)`

然后按这个最终分数排序。

所以最终输出不是 MMR 结果原封不动返回，而是 **hybrid merge 之后再重排**。

## 5. 当前还有 document-aware 文件级召回

还有一个容易忽略但实际优先级更高的点：

在 `buildContext(...)` 里，系统会先尝试 `resolveWholeDocumentContext(userId, query)`。

如果命中了文件总结、按文件名总结、章节级问答这类 document-aware 场景，就会直接返回文件级上下文，而不是先走 chunk 检索。

所以完整顺序其实是：

1. document-aware 文件级召回
2. hybrid chunk retrieval
3. hybrid merge + heuristic rerank
4. keyword fallback 或 trust-vector 兜底

这也是为什么你在面试里不能把系统简单描述成“我们就是 MMR 检索”。

## 6. 你在面试里应该怎么回答

### 标准版回答

可以这样说：

当前不是纯 MMR。MMR 还在，但它主要用在向量检索支路里做候选多样性选择。整个系统默认其实是 hybrid retrieval，也就是同时结合向量检索和关键词检索，然后在后端做一层启发式 rerank，会综合向量分、关键词分以及 chunk metadata、标题命中、section 信息这些因素做最终排序。另外在文件总结和章节问答这类场景下，还会优先走 document-aware 文件级召回。

### 更短版回答

可以这样说：

当前不是“只有 MMR”。MMR 现在主要负责向量候选选择，最终结果默认是 hybrid retrieval 加一层启发式 rerank，不是外部 reranker 模型。

## 7. 面试官如果追问“那你到底有没有复杂 rerank”怎么答

你要避免两个极端：

- 不要说“还是 MMR，没别的”
- 也不要说“我们有很强的语义 reranker 模型”

更准确的说法是：

目前没有接独立的 cross-encoder reranker 或大模型 reranker。现在的“复杂度”主要体现在后端启发式重排：它会结合向量分、关键词分、标题和 section 命中、metadata 结构信号做排序。所以它比单纯 MMR 更复杂，但还不是独立模型型 reranker。

## 8. 最终定位

一句话总结：

**当前项目的 RAG 排序策略是 document-aware 优先、hybrid retrieval 为主、MMR 负责向量支路多样性、heuristic rerank 负责最终融合排序。**
