# Docs Guide

本目录里的文档现在分成三类：

- **当前真相来源**
- **面试与复习文档**
- **历史记录 / 过程文档**

如果你想快速了解项目当前真实系统，优先看第一类；如果你是为了面试，直接进 `docs/interview`；如果你想回溯某次优化过程，再看历史记录。

## 1. 当前真相来源

这些文档已经按当前代码和当前系统状态回填过。

### 1.1 系统与接口

- [api-interface-spec.md](/D:/Code/zlAI-v2/docs/api-interface-spec.md)
  当前核心接口总览，包含认证、Chat、Agent、RAG、管理端 Skill Importer。

- [api-rag-agent-doc.md](/D:/Code/zlAI-v2/docs/api-rag-agent-doc.md)
  当前系统级开发说明，适合快速理解 Chat / Agent / Skill / RAG 主链路。

- [rag-agent-implementation.md](/D:/Code/zlAI-v2/docs/rag-agent-implementation.md)
  当前实现层说明，重点讲 Skill / Tool / RAG / Memory 现在到底做到哪。

### 1.2 Skill / Skills

- [skill-architecture.md](/D:/Code/zlAI-v2/docs/skill-architecture.md)
  当前 skill 层架构边界，以及以后继续用 Specify Kit 做 skill feature 时该怎么写。

- [real_skills_design.md](/D:/Code/zlAI-v2/docs/real_skills_design.md)
  当前为什么还不算完整 markdown-native Skills，以及后续怎么演进。

## 2. 面试与复习文档

所有面试专用材料已经独立收进子目录：

- [interview/README.md](/D:/Code/zlAI-v2/docs/interview/README.md)

其中重点包括：

- [tencent_fullstack_interview_quick.md](/D:/Code/zlAI-v2/docs/interview/tencent_fullstack_interview_quick.md)
- [tencent_fullstack_interview_qa.md](/D:/Code/zlAI-v2/docs/interview/tencent_fullstack_interview_qa.md)
- [rag_interview_master.md](/D:/Code/zlAI-v2/docs/interview/rag_interview_master.md)
- [agent_memory_vs_autonomous_agent.md](/D:/Code/zlAI-v2/docs/interview/agent_memory_vs_autonomous_agent.md)
- [rag_eval_interview_points.md](/D:/Code/zlAI-v2/docs/interview/rag_eval_interview_points.md)
- [mcp_interview_notes.md](/D:/Code/zlAI-v2/docs/interview/mcp_interview_notes.md)

## 3. 历史记录 / 过程文档

这些文档的价值在于“记录当时做过什么、遇到什么问题、怎么分析”，不是当前系统的唯一事实来源。

### 3.1 优化日志

- [agent-optimization-change-log.md](/D:/Code/zlAI-v2/docs/agent-optimization-change-log.md)
- [rag-optimization-change-log.md](/D:/Code/zlAI-v2/docs/rag-optimization-change-log.md)

### 3.2 日期型回归与故障分析

- [rag_eval_real_test_20260318.md](/D:/Code/zlAI-v2/docs/rag_eval_real_test_20260318.md)
- [rag_eval_failure_analysis_20260318.md](/D:/Code/zlAI-v2/docs/rag_eval_failure_analysis_20260318.md)
- [rag_eval_test_subset_failure_analysis_20260319.md](/D:/Code/zlAI-v2/docs/rag_eval_test_subset_failure_analysis_20260319.md)
- [section_aware_root_cause_20260318.md](/D:/Code/zlAI-v2/docs/section_aware_root_cause_20260318.md)

### 3.3 主题深挖文档

- [rag_current_rerank_strategy.md](/D:/Code/zlAI-v2/docs/rag_current_rerank_strategy.md)
- [rag_keyword_retrieval_and_cross_encoder.md](/D:/Code/zlAI-v2/docs/rag_keyword_retrieval_and_cross_encoder.md)
- [memory_update_strategy.md](/D:/Code/zlAI-v2/docs/memory_update_strategy.md)
- [rag_eval_toolchain_explained.md](/D:/Code/zlAI-v2/docs/rag_eval_toolchain_explained.md)
- [rag_eval_annotation_and_effects.md](/D:/Code/zlAI-v2/docs/rag_eval_annotation_and_effects.md)
- [rag_eval_dataset_and_real_test_status.md](/D:/Code/zlAI-v2/docs/rag_eval_dataset_and_real_test_status.md)

## 4. 推荐阅读顺序

如果你是为了理解当前系统，推荐顺序：

1. [api-rag-agent-doc.md](/D:/Code/zlAI-v2/docs/api-rag-agent-doc.md)
2. [api-interface-spec.md](/D:/Code/zlAI-v2/docs/api-interface-spec.md)
3. [rag-agent-implementation.md](/D:/Code/zlAI-v2/docs/rag-agent-implementation.md)
4. [skill-architecture.md](/D:/Code/zlAI-v2/docs/skill-architecture.md)
5. [real_skills_design.md](/D:/Code/zlAI-v2/docs/real_skills_design.md)

如果你是为了面试复习：

1. 先看 [interview/README.md](/D:/Code/zlAI-v2/docs/interview/README.md)
2. 再按里面的 10 分钟 / 30 分钟顺序读

## 5. 使用原则

- 需要确认“现在系统到底怎么实现”，优先看“当前真相来源”
- 需要面试准备，直接看 `docs/interview`
- 需要回溯某次调优过程，再看“历史记录 / 过程文档”
- 遇到日期型文档时，默认把它视为阶段性记录，而不是最终结论

