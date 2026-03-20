# RAG 评测集、评测场景与真实测试状态说明

## 1. 先说结论

你这个判断是对的：

**当前项目里已经有离线 RAG 评测集生成工具链，也有已生成的评测集产物，但从当前仓库和当前本机运行状态来看，还不能把它说成“已经稳定完成了可复现的真实在线评测验证”。**

更准确地说：

- 评测集生成这部分是做了的
- 样本类型和打分逻辑也是有的
- 仓库里保存了生成后的样本文件
- 但当前没有看到本机这次会话下重新跑出来的 `run-rag-eval.ps1` 结果文件
- 当前机器也没有正在运行的后端服务，Docker daemon 也没启动，所以现在没法直接补一轮真实在线评测结果

所以面试里最稳的说法不是“我已经完整评测过了”，而是：

**我已经搭好了面向工程回归的离线 RAG 评测工具链，并生成了第一版评测集；但如果按严格标准说，当前还缺少一轮在目标环境上重新执行并保留结果文件的在线回归验证。**

这个说法更真实，也更站得住。

## 2. 当前仓库里已经有什么

### 2.1 已有评测集生成产物

仓库当前已有两套样本产物：

#### 1. `output-java-study-resource`

路径：

- `test/rag-eval/output-java-study-resource/rag-eval.jsonl`
- `test/rag-eval/output-java-study-resource/rag-eval-summary.json`

当前统计是：

- `documentCount = 150`
- `sampleCount = 492`
- `document_summary = 148`
- `file_name_summary = 148`
- `section_summary = 196`
- `skippedSuspiciousFiles = 10`
- `skippedDirtySamples = 2`

#### 2. `output-smoke`

路径：

- `test/rag-eval/output-smoke/rag-eval.jsonl`
- `test/rag-eval/output-smoke/rag-eval-summary.json`

当前统计是：

- `documentCount = 5`
- `sampleCount = 20`
- `document_summary = 5`
- `file_name_summary = 5`
- `section_summary = 10`

### 2.2 当前没有看到什么

当前仓库里没有看到这类文件：

- `rag-eval-result-*.jsonl`
- `rag-eval-summary-*.json`

也就是说：

**仓库里能证明“评测集生成过”，但不能直接证明“当前环境已经重新跑过在线评测并留下结果文件”。**

## 3. 评测集是怎么做出来的

### 3.1 总体流程

当前工具链分三步：

1. `normalize-study-resource.ps1`
   复制原始资料到工作区，并做文本归一化

2. `generate-rag-eval-set.ps1`
   从归一化后的 Markdown 文档生成评测样本

3. `run-rag-eval.ps1`
   调用本地 `/api/rag/query` 跑样本，输出结果和汇总

### 3.2 为什么先做归一化

因为原始资料可能来自不同来源，编码和质量很不一致。脚本先做这些工作：

- 复制原始目录，避免直接污染原始资料
- 检测编码
- 统一转 UTF-8
- 标记乱码或可疑文件
- 输出 `manifest.json`

这样后面的评测样本不会被脏数据直接污染。

### 3.3 样本是怎么生成的

样本不是人工逐条手写的，而是从归一化后的 `.md` 文档里启发式生成的。

生成逻辑主要是：

- 从文件标题里构造文件级问题
- 从第一段较长、较干净的内容构造文件总结参考答案
- 从 Markdown 标题结构里提取 section
- 从 section 内容里构造章节问题和参考答案
- 跳过可疑文件和脏样本

所以它当前更像“工程回归样本集”，而不是“人工精标 benchmark”。

## 4. 评测集基于什么场景做的

当前评测集主要围绕你项目里真正重点优化的 RAG 场景来设计，而不是做一个大而全的问答集。

### 4.1 `document_summary`

问题形式类似：

- `请总结《某某文档》这份文件的核心内容。`

它对应的业务场景是：

- 用户想总结整个文件
- document-aware 文件级召回是否有效
- 不能只靠局部 chunk 拼接

### 4.2 `file_name_summary`

问题形式类似：

- `根据文件名《某某文档》，总结这整个文件。`

它对应的业务场景是：

- 用户显式依赖文件名定位文档
- 文件标题检索和文件级召回是否有效

### 4.3 `section_summary`

问题形式类似：

- `《某某文档》中“某章节”这一节主要讲了什么？`

它对应的业务场景是：

- 章节级检索
- 结构化文档切块
- heading / section metadata 是否发挥作用
- hybrid retrieval + rerank 是否能把目标章节块提上来

### 4.4 为什么先做这三类

因为这三类场景和你项目当前的实际优化方向最一致：

- document-aware 文件级召回
- 章节级问答
- 标题/section/metadata 加权
- hybrid retrieval 与 rerank

如果一开始就铺事实问答、多跳推理、跨文档聚合，会把验证问题搞得太散，不利于先把主链路优化清楚。

## 5. 数据是怎么评估的

当前 `run-rag-eval.ps1` 评估的重点不是最终大模型回答，而是 `/api/rag/query` 返回的：

- `context`
- `matches`

也就是说，当前主要评的是 **检索层和上下文组织层**，而不是最终生成层。

### 5.1 为什么评检索层而不是最终答案

因为如果直接评最终模型回答，你很难区分：

- 是检索没命中
- 还是上下文拼接不好
- 还是模型回答本身出了问题

所以当前工具链的定位是：

**先把 RAG 检索链路单独拉出来做回归。**

### 5.2 当前指标怎么打

每条样本会计算：

#### 1. `contextCoverage`

比较：

- `expectedAnswer`
- 返回的 `context`

看关键词覆盖率。

#### 2. `matchCoverage`

比较：

- `expectedAnswer`
- 所有 `matches.content` 拼接后的内容

看关键词覆盖率。

#### 3. `sectionHit`

如果样本有 `expectedSections`，就看：

- 目标章节名有没有出现在 `context` 或 `matches` 里

### 5.3 通过条件

脚本里的通过逻辑大致是：

- 没有请求错误
- `contextCoverage >= 0.35` 或 `matchCoverage >= 0.45`
- `sectionHit = true`

### 5.4 当前这种打分方式的定位

它不是论文级语义评估，而是一个：

- 轻量
- 可复现
- 低依赖
- 面向工程回归

的近似方案。

## 6. 目前真实测试做到哪一步

### 6.1 已经做到的

可以明确说已经做到的部分：

- 做了资料归一化工具
- 做了脏数据过滤
- 做了样本自动生成脚本
- 生成了两套评测集产物
- 设计了调用 `/api/rag/query` 的回归脚本和评分逻辑

### 6.2 目前不能硬说已经做到的

从当前这台机器和当前仓库看，不能硬说的部分是：

- 当前环境已经重新跑过真实在线评测
- 当前版本已经有可直接展示的结果文件
- 当前结果已经证明这套检索和 rerank 在线上稳定达标

因为现在实际状态是：

- `127.0.0.1:8080` 当前不可达
- 本机没有运行后端服务
- Docker daemon 当前也没启动
- 仓库里没有 `run-rag-eval.ps1` 跑出的结果文件

所以这个点必须实话实说。

## 7. 面试里怎么说最稳

### 最稳说法

可以这样说：

我已经搭好了离线 RAG 评测工具链，覆盖资料归一化、样本生成和接口级回归。当前样本主要基于文件总结、按文件名总结和章节问答这三类真实场景生成，重点验证 document-aware 文件级召回和章节级命中能力。当前仓库里已经保留了生成后的评测集，但如果按严格标准说，我还缺少一轮在目标环境上重新执行并保留结果文件的在线回归验证，这部分我下一步会补上。

这个回答的好处是：

- 不夸大
- 也不否定已有工作
- 能体现你知道什么叫“工具链”和“真实验证”的区别

## 8. 如果现在要补真实测试，应该怎么做

你现在确实需要一轮真实测试。最合理的补法是：

### 8.1 先把环境拉起来

至少需要这些服务可用：

- MySQL
- Redis
- PostgreSQL + pgvector
- backend

当前机器上：

- 后端没启动
- Docker daemon 没启动

所以第一步不是讨论指标，而是先把运行环境拉起来。

### 8.2 准备测试账号 / JWT

`run-rag-eval.ps1` 需要能调 `/api/rag/query`，通常要带 `BearerToken`。

### 8.3 真正跑一轮接口级评测

例如：

```powershell
powershell -ExecutionPolicy Bypass -File .\test\rag-eval\run-rag-eval.ps1 `
  -BaseUrl 'http://127.0.0.1:8080' `
  -EvalFile '.\test\rag-eval\output-java-study-resource\rag-eval.jsonl' `
  -OutputDir '.\test\rag-eval\results' `
  -BearerToken '<your-jwt>'
```

### 8.4 保留结果文件

跑完以后至少应该保留：

- `rag-eval-result-时间戳.jsonl`
- `rag-eval-summary-时间戳.json`

这样你面试时才能说：

- 我不仅做了评测集
- 我还真跑过
- 而且保留了结果

## 9. 为什么我认为你现在必须补真实测试

因为当前如果只说“我做了工具链”，面试官继续追问：

- 那你真实跑过没有？
- 跑出来怎么样？
- 哪类场景表现最好？哪类最差？
- 你是根据什么结果去调 chunk / rerank / hybrid 权重的？

你现在会卡住。

所以从面试准备角度，当前最需要补的是：

**不是再写更多文档，而是补一轮可展示结果的真实接口级评测。**

## 10. 当前最真实的结论

一句话总结：

**项目里已经有离线 RAG 评测集和评测脚本，但从当前环境看，还缺一轮真正跑起来并可展示结果文件的在线回归测试。**

这个结论比“我已经完整评估过了”更真实，也更专业。
