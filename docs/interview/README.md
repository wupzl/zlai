# zlAI-v2 面试与深度解析目录

本目录收录的是和当前实现对齐的复习材料，重点覆盖 RAG、Agent Runtime、Multi-Agent 协作三条主线。

## 核心材料

- [RAG 深度解析与面试指南](/docs/interview/rag_deep_dive.md)
  重点：结构化入库、动态候选召回、混合重排、whole-document summary。
- [Agent 深度解析与面试指南](/docs/interview/agent_deep_dive.md)
  重点：有状态运行、预算门控、等待/恢复、取消与 no-progress guard。
- [Multi-Agent 深度解析与面试指南](/docs/interview/multi_agent_deep_dive.md)
  重点：规则优先 + LLM 辅助规划、顺序/并行执行、专家结果汇总。
- [RAG + Agent 总复习稿](/docs/interview/MASTER_RAG_AGENT_GUIDE.md)
  适合快速串讲。

## 建议阅读顺序

1. 先看 `rag_deep_dive.md`，建立“检索不是纯向量召回”的心智模型。
2. 再看 `agent_deep_dive.md`，理解运行时为何要 checkpoint、WAITING、RESUMED。
3. 最后看 `multi_agent_deep_dive.md`，把规划、专家执行和 synthesis 串起来。

## 注意

- 这些文档描述的是当前仓库已落地实现。
- 若文档和代码细节有差异，以源码中的类、方法和测试为准。