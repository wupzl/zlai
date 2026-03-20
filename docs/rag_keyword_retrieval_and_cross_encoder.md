# RAG 关键词检索与 Cross-Encoder 面试点

## 1. 先说结论

当前项目里的关键词检索，**不是 BM25，也不是 Elasticsearch，也不是成熟的 Cross-Encoder reranker**。

它现在的真实实现是：

- 查询侧先做一层轻量关键词抽取
- 然后在 PostgreSQL 里对 `rag_chunk` 和 `rag_document` 做 `ILIKE` 模糊匹配
- SQL 层先给一个基础 lexical score
- Java 层再做一层词法增强分
- 最后和向量检索结果一起进入 hybrid merge 和 heuristic rerank

所以如果面试官问“你的关键词检索算法是什么”，最准确的回答是：

**当前是轻量级的规则式关键词抽取 + PostgreSQL 模糊匹配 + metadata 加权 + Java 词法重排，不是 BM25，也不是独立搜索引擎方案。**

## 2. 当前关键词检索的真实链路

### 2.1 查询侧先提关键词

代码在 [RagServiceImpl.java](D:\Code\zlAI-v2\backend\src\main\java\com\harmony\backend\ai\rag\service\impl\RagServiceImpl.java) 的 `extractKeywords(...)`。

它的做法不是接中文分词器，而是：

- 先清洗 query
- 用正则抽出：
  - 连续 2 个以上汉字
  - 连续 3 个以上字母/数字/下划线 token
- 如果抽到了中文词，再做中文扩展
- 如果 query 很短，还允许单个汉字兜底

正则规则大致是：

- `([\p{IsHan}]{2,}|[A-Za-z0-9_]{3,})`

这意味着：

- 中文至少 2 个字才算一个关键词
- 英文/数字类至少 3 个字符才算一个关键词

### 2.2 中文关键词还会扩展

在 `expandChineseKeywords(...)` 里，项目会对中文词继续扩展：

- 保留原始词
- 如果词很短，允许拆成单字
- 如果词长度 >= 3，会继续切成连续二元组 bigram

举例：

假设 query 里提取到关键词：

- `事务传播行为`

扩展后可能会得到：

- `事务传播行为`
- `事务`
- `务传`
- `传播`
- `播行`
- `行为`

这样做的目的，是提升在中文场景下的模糊命中能力，减少“用户说法和文档标题不完全一样”时的漏召回。

### 2.3 SQL 层怎么查

真正的关键词 chunk 检索在 [RagRepository.java](D:\Code\zlAI-v2\backend\src\main\java\com\harmony\backend\ai\rag\repository\RagRepository.java) 的 `searchChunkMatchesByKeyword(...)`。

它用的是 PostgreSQL 的：

- `ILIKE '%keyword%'`
- 再配合 `POSITION(LOWER(?) IN LOWER(content))` 做位置排序

也就是说：

- 先找包含这个关键词的 chunk
- 如果关键词在内容里出现得更靠前，会排得更前

这是一种很轻量但很务实的做法。

## 3. SQL 层的 lexical score 是怎么打的

当前 SQL 里已经内置了一层基础分，不是简单查出来就完了。

在 `searchChunkMatchesByKeyword(...)` 里，大致是这样打分：

### 3.1 内容匹配分

- `LOWER(content) = LOWER(keyword)`：`1.0`
- `LOWER(content) LIKE LOWER('%keyword%')`：`0.92`
- 否则基础分：`0.78`

### 3.2 metadata 加分

如果 chunk metadata 里 block type 不同，还会额外加分：

- `blockType = heading`：`+0.14`
- `blockType = table`：`+0.08`
- `blockType = list`：`+0.05`

### 3.3 关键词出现在 metadata 中再加分

如果 `chunk_metadata` 里也匹配到关键词：

- 再加 `+0.10`

### 3.4 排序规则

SQL 最终排序时会优先看：

1. 关键词在正文里出现的位置
2. score 高低
3. 创建时间

这说明它不仅看“有没有命中”，还看“命中得准不准、是不是结构块”。

## 4. Java 层还会再做一层 lexicalScore

在 `keywordSearch(...)` 里，SQL 查出来的 `hit.getScore()` 还不是最终关键词分。

Java 代码会再用 `lexicalScore(...)` 做增强。

这层主要加两类分：

### 4.1 query 全句直接命中

如果整个 query 归一化后直接出现在 chunk 内容里：

- `+0.25`

### 4.2 关键词覆盖率加分

如果 query 提取出了多个关键词，就统计命中了几个关键词，按覆盖率再加分：

- 最多 `+0.3`

也就是说，Java 层相当于又做了一层：

**全句命中加分 + 多关键词覆盖率加分**

这比只依赖 SQL 里的模糊匹配要更稳一些。

## 5. 用一个具体例子说明关键词检索怎么工作

假设 query 是：

`Spring Boot 事务传播行为`

### 第一步：提词

`extractKeywords(...)` 可能得到：

- `Spring`
- `Boot`
- `事务传播行为`

中文扩展后还会多出：

- `事务`
- `传播`
- `行为`
- 以及一些中文 bigram

### 第二步：SQL 检索

假设某个 chunk 内容是：

`[Section] Spring 事务传播行为\nSpring 支持 REQUIRED、REQUIRES_NEW、NESTED 等事务传播行为。`

它可能拿到：

- 内容 `LIKE` 命中：`0.92`
- `blockType = heading`：`+0.14`
- metadata 里也包含关键词：`+0.10`

SQL 层初始分：

- `0.92 + 0.14 + 0.10 = 1.16`

### 第三步：Java lexicalScore

如果整个 query 或大部分关键词都命中：

- query 全句命中：`+0.25`
- 关键词覆盖率接近满分：比如 `+0.24`

那么 Java 词法分可能会变成：

- `1.16 + 0.25 + 0.24 = 1.65`

这时这个 chunk 的关键词分就会比较高，进入 hybrid merge 时也更有优势。

## 6. 为什么当前不用成熟的 Cross-Encoder

这个问题很容易被问，而且你必须答得像工程取舍，不要答成“我不会”。

### 标准答案

当前没有接 Cross-Encoder，主要是因为项目当前阶段更看重：

- 系统链路完整可控
- 本地和线上部署复杂度低
- 推理成本可控
- 检索结果可解释

Cross-Encoder 的效果通常更强，但它也会带来几个现实问题：

- 每次检索后都要对 query-chunk 对做额外推理，延迟明显更高
- 候选集一大，重排成本会上升得很快
- 需要额外模型服务或部署依赖
- 对我当前这个项目阶段来说，先把 hybrid retrieval 和启发式 rerank 打磨好，收益更直接

### 更具体一点的工程理由

当前项目默认：

- `topK = 5`
- `mmrCandidateMultiplier = 4`

也就是向量侧默认候选池大约是 20 个。如果再叠加关键词侧候选，merge 之后还要去重和重排。

如果这里直接上 Cross-Encoder，就意味着：

- query 要和每个候选 chunk 组成 pair
- 每次检索都做多次额外模型推理
- 延迟和成本都会明显上升

对一个当前还在快速迭代、强调工程闭环的项目来说，这个投入不一定是第一优先级。

## 7. 为什么我认为现在自己的算法是合理的

### 7.1 它已经覆盖了当前项目最关键的信号

当前方案已经把这些信号都纳入了：

- 语义相似度
- 关键词命中
- 全句命中
- 标题命中
- section 命中
- block type
- must-have 关键词

这说明它不是一个“纯 keyword contains”的弱方案，而是一套比较完整的轻量融合检索逻辑。

### 7.2 它更容易调试和解释

Cross-Encoder 的得分通常更黑盒，而现在这套策略你能明确解释：

- 为什么这个 chunk 上来了
- 为什么那个 chunk 被压下去了
- 哪个权重在起作用
- 哪个结构信号在纠偏

这在项目阶段和面试阶段都很重要。

### 7.3 它更适合当前系统复杂度

项目现在已经有：

- document-aware 文件级召回
- hybrid retrieval
- MMR
- heuristic rerank
- 离线 RAG 评测工具链

在这种情况下，先把现有体系调顺，再用评测验证效果，是比直接接 Cross-Encoder 更稳妥的路线。

## 8. 面试官可能继续拷打的问题

### Q1：你这个关键词检索是不是太简陋了？

建议回答：

如果和 Elasticsearch 或 BM25 搜索引擎比，当前方案当然更轻量。但它不是简单的 contains，而是做了关键词抽取、中文扩展、SQL 层 metadata 加权、Java 层 coverage 加分，再和向量结果融合。对当前项目阶段来说，这是一个成本、复杂度和效果之间的折中方案。

### Q2：为什么不用 BM25？

建议回答：

BM25 是很成熟的方案，但会引入新的检索基础设施或更重的文本索引体系。当前项目已经有 PostgreSQL 和 pgvector，我优先选择在现有栈内把关键词检索能力补起来。这样部署和调试成本更低，也更适合当前项目阶段。

### Q3：你的关键词提取为什么不用中文分词器？

建议回答：

当前项目优先追求轻量和可控，所以用了正则抽词加中文 bigram 扩展，而不是引入额外中文分词依赖。这样虽然不如成熟分词器细致，但足够支撑当前章节问答、文件名问答和术语型问答场景。

### Q4：你这个方案和 Cross-Encoder 相比差在哪？

建议回答：

Cross-Encoder 能更细粒度建模 query 和 chunk 的交互语义，理论上重排效果会更强。当前方案的不足是它仍然偏规则和启发式，语义交互能力不如真正的交叉编码器。但它的优势是轻量、低成本、容易解释，而且已经能覆盖当前项目最重要的结构化文档场景。

### Q5：如果后面你真要上 Cross-Encoder，怎么接？

建议回答：

我会把它放在 merge 之后，只对一个更小的候选集做最终重排，而不是一开始就对大候选池全量跑。这样可以控制成本。同时我会先用离线 RAG 评测工具链证明当前方案的瓶颈确实在 final rerank，再决定要不要上 Cross-Encoder。

### Q6：为什么你说这个方案适合当前项目？

建议回答：

因为当前项目阶段重点是把整个 RAG 闭环搭完整，包括 ingest、OCR、chunking、hybrid retrieval、document-aware、评测和部署。Cross-Encoder 是可以做的升级项，但不是现阶段最先需要补的点。当前这套方案在复杂度、可解释性和工程可落地性上更平衡。

## 9. 你面试里最好背熟的几句话

- 当前关键词检索不是 BM25，而是轻量规则抽词 + PostgreSQL `ILIKE` + metadata 加权 + Java 词法重排
- 中文 query 会做扩词，既保留原词，也补中文 bigram，提高模糊命中率
- 关键词检索不是独立存在的，它是 hybrid retrieval 里的词法支路
- 当前没有接 Cross-Encoder，因为现阶段更看重低成本、低复杂度和可解释性
- 如果后面要接 Cross-Encoder，我会把它放在 merge 后的小候选集上做最终重排，而不是全量跑

## 10. 一句话总结

当前项目的关键词检索，本质上是一套 **轻量、可解释、依赖现有 PostgreSQL 栈的词法检索与重排方案**；而没有上 Cross-Encoder，是出于当前项目阶段对复杂度、成本和可控性的工程取舍。
