# 腾讯全栈面试问答（当前系统版）

## Q1：你这个项目主要做了什么？

我独立做了一个 AI 应用平台，里面把 Chat、RAG、Agent、Skill、Tool、Memory、多模型接入、评测和部署都串起来了。重点不是做一个简单调用大模型的 demo，而是把 Agent 执行链路、RAG 检索链路、记忆更新策略和线上可运行部署都做成完整闭环。

## Q2：你说的 `skill -> execute -> followup` 是什么？

这是我当前 Agent 侧最核心的一条执行链路。模型如果输出内部 skill JSON，请求不会直接信任，而是先经过 `SkillPlanner` 在 allowed skills 中做过滤和排序，再由 `SkillExecutor` 按 `inputSchema / toolKeys / stepConfig` 执行。执行成功后会走 billing 和记忆更新，然后再做 followup，把结果组织成用户可见的自然语言回答。

## Q3：你现在的 skill 还是 prompt 吗？

不是。当前 skill 已经是结构化运行时对象，里面有 `key`、`description`、`toolKeys`、`executionMode`、`inputSchema`、`stepConfig`。所以它不是一个纯提示词，而是一个真正可执行的编排单元。

## Q4：你最近把 skill 层做了什么改造？

我把 tool 合同标准化了，引入了 `ExecutableAgentTool`，让 tool 的定义和执行能力分层；`AgentToolRegistry` 现在既能给前端展示元数据，也能给 skill 执行层拿 executable tool。`DefaultSkillExecutor` 也做了兼容，优先走 executable tool，不行再回退到原有 `ToolExecutor`，这样能在不破坏主链路的前提下完成迁移。

## Q5：你这个项目里的 skill 和常见 agent skill 一样吗？

语义上接近，但实现形态不同。常见 agent skill markdown 更像 SOP 或能力说明书，而我项目里的 skill 是结构化执行层。现在我已经支持把 `SKILL.md` 导入成托管 skill，但导入后会先转成项目自己的运行时结构，而不是直接把 markdown 当运行时执行单元。

## Q6：那现在能直接导入 agent skill markdown 吗？

可以导入尝试，但不是所有 markdown 都能直接用。当前 importer 会先解析标题、描述、tools、inputs、workflow，再映射成系统内的 skill。如果文档结构清晰、工具名能映射到系统已有 tool，通常可以直接导入；如果不够结构化，就需要补 `defaultToolKeys` 或 `toolAliases`，导入后可能还要再调一下 schema 和 step config。

## Q7：你已经做成 OpenAI 那种真实 Skills 了吗？

没有硬说做到那种完整形态。当前我已经有结构化 skill runtime，也有 markdown importer 和管理端导入页面，但运行时还没有做 `SKILL.md` section 级按需加载和渐进式披露。所以现在更准确的说法是：我已经有可执行 skill 层，但还没有完整 markdown-native Skills system。

## Q8：RAG 现在到底是什么架构？

当前不是纯向量检索，而是 `document-aware -> hybrid retrieval -> vector MMR + keyword branch -> heuristic rerank`。也就是说，文件总结、按文件名总结、章节问题会先走 document-aware 路由，常规问题走 hybrid retrieval，向量支路用 MMR 去重，最后再做启发式 rerank。

## Q9：为什么不是纯向量 topK？

因为纯向量 topK 在结构化文档场景下很容易出现两个问题：一是重复 chunk 太多，二是标题、章节、文件名这些强词法信号不稳。所以我把 whole-document summary、section-aware 路由和 hybrid retrieval 分开做，提升文件级和章节级问答的稳定性。

## Q10：你关键词检索是 BM25 吗？

不是。当前项目里关键词检索是轻量规则式方案：规则提词、中文扩词、PostgreSQL `ILIKE` 模糊匹配、SQL metadata 加权，再加 Java 侧 lexical score 二次排序。它是 hybrid retrieval 的词法支路，不是单独一套搜索引擎。

## Q11：你为什么没用 Cross-Encoder？

因为当前阶段更看重复杂度、成本、延迟和可解释性。现在我已经有 document-aware、hybrid retrieval、MMR、heuristic rerank 和评测工具链，所以优先把现有链路跑顺、验证清楚，比直接引入更重的 reranker 更合理。后面如果要接，我会放在 merge 后的小候选集上做最终重排，而不是一开始全量跑。

## Q12：你为什么没用 Ragas？

因为我当前优先想把 retrieval 问题和 generation 问题拆开验证。现在的自写工具链主要评 `/api/rag/query` 返回的 `context` 和 `matches`，更偏检索层和上下文组织层回归。Ragas 更适合后续做 faithfulness、answer relevancy 这种端到端生成层评估。

## Q13：你的评测集是谁标注的？

不是人工逐条精标 benchmark，而是我基于真实资料库副本，自己设计规则、自己写脚本，启发式自动生成的第一版工程回归集。它更适合做工程回归和优化对比，而不是对外宣称成标准学术 benchmark。

## Q14：那你现在优化效果能怎么说？

现在我可以直接讲真实回归结果，不只是说“工具链搭好了”。当前 smoke 集是 `20/20`，测试集子集是 `153/168 = 91.07%`。剩余失败样本里大部分已经收敛到 `section_summary` 场景，这说明当前系统的大方向是对的，问题已经从“整体检索是否可用”收敛到了“章节级精度继续优化”。

## Q15：Memory 你做了什么？

我把 memory 做成了三层：`conversation_summary`、`user_memory`、`task_state`。而且我后来把更新策略从“按回合刷”改成了“按价值刷”：summary 有时间和消息双门槛，user memory 只在稳定偏好信号下更新，task state 只在任务型回合或结构化产物回合更新，这样更符合真实工程系统，不会为每轮普通对话都做高成本低价值更新。

## Q16：你前端不强，这个岗位要 Vue/React，怎么回答？

我会实话说，我的强项在后端架构、数据库、Agent 和 RAG 链路，但我已经能完成 Vue3 管理端和业务页面开发、接口联调、异步任务状态展示和交互改造。比如我最近就在管理端接了 Skill Markdown 导入页，把后端 importer 直接接到了现有 admin skill 页面里。

## Q17：如果面试官追问你“真实 Skills 为什么没做完”，怎么答？

我会说当前项目已经把 skill 做成了结构化执行层，这是当前最有价值、最可落地的一步。真实 markdown-native Skills 还差 section 级按需加载和渐进式披露，这属于下一阶段架构演进。我不会把没做完的东西硬说成已经完成，但我能清楚说明现在做到哪、差距在哪、下一步怎么做。
