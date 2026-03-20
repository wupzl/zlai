# RAG 评测标准与评测集生成速记

## 1. 这套评测到底在评什么

当前项目里的离线 RAG 评测，重点评的是：

- `/api/rag/query` 返回的 `context`
- `/api/rag/query` 返回的 `matches`

所以它更偏：

- 检索质量
- 上下文组织质量
- 章节命中能力

而不是直接评最终 LLM 回答质量。

## 2. 整体流程

整套工具链分三步。

### 第一步：复制并归一化资料

脚本：
- `test/rag-eval/normalize-study-resource.ps1`

输入：
- 原始资料目录，比如学习资料库

做的事情：
- 先把原始资料复制到 `workspace/copied`
- 再把文本统一转成 UTF-8，输出到 `workspace/normalized`
- 自动尝试多种编码解码：`utf-8`、`utf-16-le`、`utf-16-be`、`gb18030`、`gbk`、`big5`
- 对文本做质量检查，识别乱码、异常标点、替换字符、疑似解码错误
- 输出 `manifest.json` 和 `summary.json`

这样做的目的有两个：
- 不污染原始资料目录
- 先把脏数据筛掉，避免后面生成评测集时混进乱码样本

### 第二步：从归一化文档生成评测集

脚本：
- `test/rag-eval/generate-rag-eval-set.ps1`

输入：
- `workspace/normalized`
- 第一步生成的 `manifest.json`

做的事情：
- 遍历归一化后的 Markdown 文档
- 跳过可疑文件和脏样本
- 自动生成三类问题：
  - `document_summary`
  - `file_name_summary`
  - `section_summary`

生成规则：
- `document_summary`
  - 问“请总结《某文档》这份文件的核心内容”
  - `expectedAnswer` 取文档里第一段较长且较干净的段落
- `file_name_summary`
  - 问“根据文件名《某文档》，总结这整个文件”
  - `expectedAnswer` 也取文档前面的代表性段落
- `section_summary`
  - 问“《某文档》中‘某节’主要讲了什么？”
  - `expectedAnswer` 取该 section 内容裁剪后的片段
  - `expectedSections` 记录章节标题，后面专门做章节命中校验

输出：
- `rag-eval.jsonl`
- `rag-eval-summary.json`

### 第三步：跑真实接口评测

脚本：
- `test/rag-eval/run-rag-eval.ps1`

做的事情：
- 逐条读取 `rag-eval.jsonl`
- 调本地真实接口 `POST /api/rag/query`
- 取返回的：
  - `data.context`
  - `data.matches`
- 用启发式规则做 coverage 和章节命中判断
- 输出逐条结果和汇总结果

输出：
- `rag-eval-result-*.jsonl`
- `rag-eval-summary-*.json`

## 3. 评测集里的样本长什么样

每条样本大概包含这些字段：

- `id`
- `type`
- `relativePath`
- `question`
- `expectedAnswer`
- `expectedSections`

其中：
- `expectedAnswer` 是后面计算 coverage 的基准文本
- `expectedSections` 是章节题的强约束

## 4. 当前到底看哪些指标

真实判分逻辑在 `test/rag-eval/run-rag-eval.ps1`。

### 4.1 `contextCoverage`

含义：
- `expectedAnswer` 的关键词，有多少出现在返回的 `context` 里

做法：
- 文本先归一化
- 英文按 token 切
- 中文按 CJK bigram 切
- 再算命中比例

它反映的是：
- 拼出来的最终上下文，是否真的覆盖到了该题该有的信息

### 4.2 `matchCoverage`

含义：
- `expectedAnswer` 的关键词，有多少出现在 `matches[].content` 拼接后的文本里

它反映的是：
- 检索命中的 chunk 本身，是否找到了正确材料

### 4.3 `sectionHit`

含义：
- `expectedSections` 里的章节标题，是否都出现在：
  - `context + matches`
  里

它反映的是：
- 对章节题来说，系统有没有命中目标 section，而不是只召回到语义相近但章节不对的内容

## 5. 单条样本如何判通过

当前脚本里的通过条件是：

- 请求不能报错
- `contextCoverage >= 0.35` 或 `matchCoverage >= 0.45`
- `sectionHit = true`

也就是说：
- 不能只看 coverage
- 章节题还必须真正命中目标章节

## 6. `score` 是怎么来的

脚本还会算一个辅助综合分：

- `score = contextCoverage * 0.7 + matchCoverage * 0.3 + sectionHit奖励0.1`

它主要用来：
- 排序
- 看趋势
- 辅助比较不同版本

但要注意：

**真正决定 pass/fail 的不是只看 `score`，而是上面的阈值条件。**

## 7. 汇总结果怎么看

汇总文件里会给出：

- `sampleCount`
- `passed`
- `failed`
- `passRate`
- `byType`
  - `avgScore`
  - `avgContextCoverage`
  - `avgMatchCoverage`
  - `passRate`

所以你后面对比优化前后，最常看的就是：
- 总体 `passRate`
- 各题型 `passRate`
- `avgContextCoverage`
- `avgMatchCoverage`

## 8. 这套评测的工程定位

这套评测更准确的定位是：

- 离线回归工具链
- 检索层 / context 层验证工具

它不是：
- 学术 benchmark
- 最终回答质量全量评估平台

## 9. 为什么不用最终答案来评

因为如果直接评最终回答，问题很难分层定位。

最终回答会同时受到：
- 检索结果
- 上下文组织
- 大模型生成

三层共同影响。

现在先评 `/api/rag/query`，好处是：
- 能先把 retrieval 问题和 generation 问题拆开
- 更适合调 chunking、hybrid retrieval、rerank、section routing 这些工程项

## 10. 这套评测的局限

要实话实说，当前这套评测有几个明显局限：

- `expectedAnswer` 主要是启发式自动生成，不是人工逐条精标
- 现在更偏检索层，不是端到端回答质量评测
- coverage 是轻量关键词指标，不等于完整语义理解
- 对复杂多跳问题、开放问答、最终 faithfulness 评估还不够

## 11. 面试时最稳的说法

可以直接这样讲：

我现在这套离线 RAG 评测，重点不是评最终大模型回答，而是评 `/api/rag/query` 返回的 context 和 matches 是否足够覆盖期望信息。流程是先做资料归一化，再自动生成三类评测样本，最后用关键词 coverage 和章节命中规则做回归。它更像工程回归工具链，而不是论文式 benchmark。

## 12. 一句话压缩版

**当前评测标准本质上就是：用 `expectedAnswer` 的关键词覆盖率加上章节命中规则，评估 `/api/rag/query` 返回的 context 和检索结果是否找对了内容。**
