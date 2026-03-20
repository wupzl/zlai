# Agent Optimization Change Log

This file records changes made to the agent/skill/tool/memory pipeline so the optimization process is traceable.

## 2026-03-14

### Summary

Initialized the optimization change log for the current `agent -> skill -> tool` refactor track.

### Findings Recorded

- Current architecture is usable, but the main risks are skill mis-selection, weak skill abstraction, and insufficient state/memory layering.
- Tool recursion risk is currently limited by the backend execution path, but repeated model attempts to emit tool/skill JSON can still reduce response stability.
- Session state is primarily backed by message history and a trimmed context window, which is not equivalent to structured agent memory.

### Planned Optimization Direction

- Add a `SkillPlanner` layer to narrow and rank skill candidates before model selection.
- Upgrade skill execution from simple tool aliasing to controlled task-unit execution with runtime context.
- Add execution guards such as step limits, retry limits, and visited skill/tool tracking.
- Introduce layered memory: conversation summary, user memory, and task state.
- Keep `ToolFollowupService` as a bounded fallback path instead of letting it grow into the main orchestration path.

### Files Changed In This Entry

- Created this log file: `docs/agent-optimization-change-log.md`

### Implemented Changes

- Added `SkillPlanner` abstraction and default implementation to rank allowed skills before execution.
- Added `SkillCandidate` and `SkillPlan` models so skill selection is explicit and traceable.
- Extended `ToolFollowupService` to parse both `tool` and `skill` JSON calls, execute allowed skills, and continue with a bounded plain-text followup.
- Completed the single-agent `skill -> execute -> answer` path so agent prompts that emit skill JSON now have a backend execution path.
- Wired `SkillPlanner` into `MultiAgentOrchestrator` so multi-agent skill calls are resolved against allowed skills instead of trusting model output directly.
- Added step and repeated-tool guards in `DefaultSkillExecutor` to keep pipeline execution bounded.
- Refactored `ChatServiceImpl` to expose allowed skills for the current session agent and reuse that for tool permission expansion.
- Replaced several corrupted non-ASCII query heuristics in `ChatServiceImpl` with safe Unicode-escape or ASCII-based checks to restore stable compilation and runtime behavior.

### Verification

- `mvn -DskipTests=true compile` in `backend/` passed successfully on 2026-03-14.

### Files Changed In This Entry

- `backend/src/main/java/com/harmony/backend/ai/skill/SkillCandidate.java`
- `backend/src/main/java/com/harmony/backend/ai/skill/SkillPlan.java`
- `backend/src/main/java/com/harmony/backend/ai/skill/SkillPlanner.java`
- `backend/src/main/java/com/harmony/backend/ai/skill/impl/DefaultSkillPlanner.java`
- `backend/src/main/java/com/harmony/backend/ai/skill/impl/DefaultSkillExecutor.java`
- `backend/src/main/java/com/harmony/backend/modules/chat/service/support/ToolFollowupService.java`
- `backend/src/main/java/com/harmony/backend/ai/agent/runtime/MultiAgentOrchestrator.java`
- `backend/src/main/java/com/harmony/backend/modules/chat/service/impl/ChatServiceImpl.java`

## 2026-03-14 (Memory Layering)

### Summary

Implemented the first usable memory layer for agent sessions: conversation summary, user memory, and task state.

### Implemented Changes

- Added persistent entities and MyBatis mappers for `conversation_summary`, `user_memory`, and `task_state`.
- Added `AgentMemoryService` and `AgentMemoryServiceImpl` to:
  - build memory context for prompt injection,
  - maintain a rolling conversation summary from recent messages,
  - infer simple user preferences such as language and timezone,
  - keep a minimal active task record per chat.
- Updated `ChatContextService` to inject persistent memory as a separate `system` message before chat history.
- Updated context trimming so leading system messages are preserved together instead of only keeping the first one.
- Updated `ChatServiceImpl` to write memory after successful assistant replies in stream, regenerate, sync, and immediate fallback paths.
- Added database patch file `backend/db/alter_agent_memory_tables.sql` for the new memory tables.

### Verification

- `mvn -DskipTests=true compile` in `backend/` passed successfully after memory-layer changes on 2026-03-14.

### Follow-up Needed

- The SQL patch `backend/db/alter_agent_memory_tables.sql` still needs to be executed in the target database before runtime use.
- Current user-memory extraction is heuristic and intentionally conservative; it should later be upgraded to structured extraction.
- Current conversation summary is generated from compacted recent messages; it should later be upgraded to model-assisted summarization with refresh thresholds.

### Files Changed In This Entry

- `backend/src/main/java/com/harmony/backend/common/entity/ConversationSummary.java`
- `backend/src/main/java/com/harmony/backend/common/entity/UserMemory.java`
- `backend/src/main/java/com/harmony/backend/common/entity/TaskState.java`
- `backend/src/main/java/com/harmony/backend/common/mapper/ConversationSummaryMapper.java`
- `backend/src/main/java/com/harmony/backend/common/mapper/UserMemoryMapper.java`
- `backend/src/main/java/com/harmony/backend/common/mapper/TaskStateMapper.java`
- `backend/src/main/java/com/harmony/backend/modules/chat/service/support/AgentMemoryService.java`
- `backend/src/main/java/com/harmony/backend/modules/chat/service/support/AgentMemoryServiceImpl.java`
- `backend/src/main/java/com/harmony/backend/modules/chat/service/support/ChatContextService.java`
- `backend/src/main/java/com/harmony/backend/modules/chat/service/impl/ChatServiceImpl.java`
- `backend/db/alter_agent_memory_tables.sql`

## 2026-03-14 (Memory Upgrade)

### Summary

Upgraded the first memory layer with model-assisted summary refresh and explicit skill-result tracking in task state.

### Implemented Changes

- Extended `AgentMemoryService` with `recordSkillExecution(...)` so skill execution can update task state immediately.
- Upgraded `AgentMemoryServiceImpl` conversation-summary generation from pure recent-message compaction to:
  - threshold-based refresh,
  - reuse of existing summary,
  - model-assisted summary generation through `LlmAdapterRegistry`,
  - heuristic fallback if summarization fails.
- Added configurable properties:
  - `app.memory.summary-model` with default `deepseek-chat`
  - `app.memory.summary-refresh-every-messages` with default `6`
- Updated task state memory to persist:
  - `current_skill`
  - `current_step`
  - skill input/output excerpts in `artifacts_json`
- Updated `ToolFollowupService` so successful skill execution now writes the selected skill and result excerpt into task memory before final answer followup.

### Verification

- `mvn -DskipTests=true compile` in `backend/` passed successfully after the memory upgrade on 2026-03-14.

### Files Changed In This Entry

- `backend/src/main/java/com/harmony/backend/modules/chat/service/support/AgentMemoryService.java`
- `backend/src/main/java/com/harmony/backend/modules/chat/service/support/AgentMemoryServiceImpl.java`
- `backend/src/main/java/com/harmony/backend/modules/chat/service/support/ToolFollowupService.java`

## 2026-03-14 (DB Apply + Structured User Memory)

### Summary

Applied the memory-table SQL patch to MySQL and upgraded user-memory extraction from rule-only inference to model-assisted structured extraction with rule fallback.

### Implemented Changes

- Executed `backend/db/alter_agent_memory_tables.sql` against MySQL database `zl-ai2`.
- Verified that `conversation_summary`, `user_memory`, and `task_state` now exist in the database.
- Extended `AgentMemoryServiceImpl` user-memory inference to:
  - call the configured summary-capable model,
  - request JSON-only extraction for stable preference fields,
  - parse `preferred_language`, `timezone`, and `response_style`,
  - keep heuristic extraction as fallback and override for obvious signals.
- Added config flag:
  - `app.memory.user-memory-model-enabled` with default `true`

### Verification

- Executed MySQL table checks successfully for:
  - `conversation_summary`
  - `user_memory`
  - `task_state`
- `mvn -DskipTests=true compile` in `backend/` passed successfully after structured user-memory extraction changes on 2026-03-14.

### Files Changed In This Entry

- `backend/src/main/java/com/harmony/backend/modules/chat/service/support/AgentMemoryServiceImpl.java`
- `backend/src/main/java/com/harmony/backend/modules/chat/service/support/AgentMemoryService.java`
- `backend/db/alter_agent_memory_tables.sql` (executed against MySQL)

## 2026-03-14 (Structured Task State)

### Summary

Upgraded task-state memory from loose artifact text to structured task artifacts that are easier for future planning and multi-turn execution to consume.

### Why This Was Needed

- The previous `task_state` already stored `goal/current_skill/current_step`, but `artifacts_json` was still mostly free-form excerpts.
- That format was weak for downstream planning because it did not clearly separate completed items, pending actions, and unresolved questions.
- The upgrade improves task continuity without requiring a database schema change.

### Implemented Changes

- Added config flag:
  - `app.memory.task-state-model-enabled` with default `true`
- Upgraded `AgentMemoryServiceImpl` response-artifact generation to model-extract structured task fields:
  - `done_items`
  - `pending_actions`
  - `open_questions`
  - plus fallback `last_assistant_excerpt`
- Kept a heuristic-safe fallback when model extraction fails.
- Upgraded skill artifact persistence to include structured fields so task state remains machine-readable after skill execution.
- Updated task-state memory rendering so `ChatContextService` now receives a more readable and more structured task block instead of a raw JSON dump whenever parsing succeeds.

### Verification

- `mvn -DskipTests=true compile` in `backend/` passed successfully after structured task-state changes on 2026-03-14.

### Files Changed In This Entry

- `backend/src/main/java/com/harmony/backend/modules/chat/service/support/AgentMemoryServiceImpl.java`

## 2026-03-15 (Skill InputSchema Normalization Fix)

### Summary

Fixed a real skill-execution failure where agents selected skills correctly but passed free-text input to skills that required JSON objects matching `inputSchema`.

### Problem Observed

- Example user prompt: `介绍一下量子计算的定义`
- Example failure: `Skill execution failed: Skill input must be valid JSON matching inputSchema`
- Root cause: `DefaultSkillPlanner` selected the correct skill but returned raw string input unchanged, while `DefaultSkillExecutor` correctly required schema-compliant JSON.

### Implemented Changes

- Upgraded `DefaultSkillPlanner` so it now normalizes input against the selected skill definition.
- Added schema-aware coercion rules:
  - if input is already a valid JSON object matching required fields, keep it;
  - if the selected skill has exactly one required field, wrap the free-text input into that field;
  - if no raw input is provided, fall back to the last user prompt and wrap it the same way.
- This allows skills such as web research to receive payloads like `{"query":"介绍一下量子计算的定义"}` instead of raw text.

### Verification

- `mvn -DskipTests=true compile` in `backend/` passed successfully after the input-schema normalization fix on 2026-03-15.

### Files Changed In This Entry

- `backend/src/main/java/com/harmony/backend/ai/skill/impl/DefaultSkillPlanner.java`

## 2026-03-16 (Workflow-First Sync Execution)

### Summary

Introduced the first `workflow-first` execution path and wired it into the synchronous single-agent chat flow, while keeping multi-agent and streaming paths backward compatible.

### Why This Was Needed

- The existing agent path still relied on model-led freeform execution even after skill-planner and memory hardening.
- Deterministic workflow routing is better suited for research, tool-backed answering, and stateful task progression.
- The current codebase already had orchestration support and `task_state`, so the lowest-risk path was to add a workflow execution layer above skills instead of replacing the whole chat pipeline.

### Implemented Changes

- Added `AgentWorkflowService` and `AgentWorkflowServiceImpl` as the first workflow execution layer.
- Added `WorkflowExecutionResult` so workflow handling is explicit and can cleanly fall back to legacy chat execution when not applicable.
- Implemented a minimal sync workflow router with three outcomes:
  - `direct_qa_workflow`
  - `skill_workflow`
  - `research_workflow`
- Reused `SkillPlanner` to route eligible single-agent requests into workflow-backed skill execution instead of relying on the model to emit skill JSON first.
- Reused `SkillExecutor` for workflow step execution and kept backend billing for tool-backed skill runs.
- Reused `AgentMemoryService` task-state storage to record workflow progress stages:
  - `planned`
  - `skill_executed`
  - `skill_failed`
  - `answered`
- Extended `AgentMemoryService` and `AgentMemoryServiceImpl` with `recordWorkflowProgress(...)` so workflow state is persisted into `task_state` without adding new tables.
- Updated `ChatServiceImpl.sendMessage(...)` so synchronous single-agent requests now try workflow execution first after the assistant placeholder is created.
- Left multi-agent, streaming, and regenerate flows unchanged for compatibility; they can be migrated later after validating the sync workflow path.

### Current Scope Limits

- Workflow routing currently applies to synchronous single-agent requests only.
- Multi-agent orchestration is still manager/specialist based and has not yet been migrated to workflow execution.
- Streaming and regenerate requests still use the prior orchestration path.
- Workflow definitions are code-driven for now rather than admin-configurable DSL records.

### Verification

- `mvn -DskipTests=true compile` in `backend/` passed successfully after workflow integration on 2026-03-16.

### Files Changed In This Entry

- `backend/src/main/java/com/harmony/backend/modules/chat/service/workflow/AgentWorkflowService.java`
- `backend/src/main/java/com/harmony/backend/modules/chat/service/workflow/impl/AgentWorkflowServiceImpl.java`
- `backend/src/main/java/com/harmony/backend/modules/chat/service/workflow/model/WorkflowExecutionResult.java`
- `backend/src/main/java/com/harmony/backend/modules/chat/service/support/AgentMemoryService.java`
- `backend/src/main/java/com/harmony/backend/modules/chat/service/support/AgentMemoryServiceImpl.java`
- `backend/src/main/java/com/harmony/backend/modules/chat/service/impl/ChatServiceImpl.java`

## 2026-03-17 (Stream Workflow Pre-Routing)

### Summary

Extended the `workflow-first` approach into the single-agent streaming chat path by adding pre-stream workflow routing with pseudo-stream output for workflow-backed answers.

### Why This Was Needed

- The previous workflow integration only covered synchronous single-agent execution.
- Streaming requests still used the legacy `model stream first -> detect tool/skill JSON later` path.
- That meant workflow, skill, and task-state capabilities were available in the system, but not being used as the primary execution path for `chat()` streaming requests.

### Implemented Changes

- Extended `AgentWorkflowService` with `executeStream(...)`.
- Refactored `AgentWorkflowServiceImpl` so sync and stream now share the same workflow execution core.
- Added `app.workflow.stream-enabled` with default `true`.
- Defined stream-specific routing behavior:
  - only skill-backed workflows are considered a stream workflow hit;
  - `direct_qa_workflow` does not intercept stream requests;
  - if workflow skill execution fails during stream pre-routing, the request falls back to native model streaming.
- Updated `ChatServiceImpl.chat(...)` so single-agent streaming requests now:
  - try workflow pre-routing before native LLM streaming,
  - pseudo-stream the final workflow answer by chunking it through the existing stream sink when a workflow is selected,
  - keep the original native stream path when no workflow is selected.
- Updated successful stream and sync completion paths to call `rememberSuccessfulTurn(...)` so memory updates now happen reliably after finalized assistant responses.

### Compatibility Notes

- Multi-agent streaming remains on the existing manager/specialist orchestration path.
- Regenerate streaming remains unchanged.
- Tool/skill JSON followup logic is still preserved for native stream fallbacks and existing agent behaviors.

### Verification

- `mvn -DskipTests=true compile` in `backend/` passed successfully after stream workflow routing changes on 2026-03-17.

### Files Changed In This Entry

- `backend/src/main/java/com/harmony/backend/modules/chat/service/workflow/AgentWorkflowService.java`
- `backend/src/main/java/com/harmony/backend/modules/chat/service/workflow/impl/AgentWorkflowServiceImpl.java`
- `backend/src/main/java/com/harmony/backend/modules/chat/service/impl/ChatServiceImpl.java`

## 2026-03-17 (Multi-Agent Team Workflow)

### Summary

Migrated multi-agent execution from a mostly free-form manager/specialist dispatch pattern to a bounded team-workflow execution model with explicit workflow-state persistence.

### Why This Was Needed

- Single-agent execution had already been stabilized with workflow-first routing, but multi-agent execution still relied heavily on model-led dispatch and synthesis.
- Multi-agent paths were the remaining area most likely to drift, over-select agents, or become hard to debug.
- The goal was not to introduce full recursive agent collaboration, but to convert the existing manager/specialist flow into a controlled workflow with bounded steps and persistent state.

### Implemented Changes

- Updated `MultiAgentOrchestrator` to use a `multi_agent_team_workflow` execution model.
- Extended `MultiAgentOrchestrator` with `AgentMemoryService` so multi-agent workflow state is written into `task_state`.
- Updated sync and stream multi-agent entrypoints to pass `userId`, `chatId`, and `assistantMessageId` into the orchestrator so workflow progress can be persisted.
- Added workflow-state checkpoints for multi-agent execution:
  - `planned`
  - `specialists_completed`
  - `synthesized`
- Added structured task artifacts for multi-agent workflow state, including selected agents, planner reason, step outputs, and final answer excerpt.
- Converted sequential multi-agent execution into explicit workflow steps where later specialists receive prior specialist findings as bounded context.
- Kept parallel execution support when the planner marks the task as parallel-safe.
- Changed streaming multi-agent execution to pseudo-stream the synthesized final answer from the team workflow result, matching the workflow-first approach already used in single-agent streaming.

### Compatibility Notes

- This is still a bounded team workflow, not a recursive multi-round agent conversation system.
- Manager synthesis remains the final aggregation step, but specialist execution is now more workflow-shaped and stateful.
- Skill execution inside specialist agents is still allowed, but it is now nested inside the bounded team workflow instead of being the primary control structure.

### Verification

- `mvn -DskipTests=true compile` in `backend/` passed successfully after multi-agent workflow changes on 2026-03-17.

### Files Changed In This Entry

- `backend/src/main/java/com/harmony/backend/ai/agent/runtime/MultiAgentOrchestrator.java`
- `backend/src/main/java/com/harmony/backend/modules/chat/service/impl/ChatServiceImpl.java`

## 2026-03-17 (Planner Split From Manager)

### Summary

Separated multi-agent planning responsibility from the user-defined manager agent so planning is now handled by a dedicated system planner service instead of being coupled to the manager runtime role.

### Why This Was Needed

- The previous multi-agent implementation still used the manager's runtime path as the planner carrier, which blurred three concerns together:
  - user-defined manager persona,
  - system planning,
  - final synthesis.
- That coupling made the planning layer harder to reason about and weaker against manager prompt/style contamination.
- A dedicated planner service makes the boundary explicit: manager is for final orchestration style, planner is for system-controlled routing.

### Implemented Changes

- Added `MultiAgentPlanner` as a dedicated planning abstraction.
- Added `MultiAgentPlan` as an explicit planner output model.
- Added `DefaultMultiAgentPlanner` as the system planner implementation.
- Moved team-planning logic out of `MultiAgentOrchestrator`, including:
  - LLM planner prompt,
  - heuristic fallback,
  - plan JSON parsing,
  - agent catalog construction.
- Updated the planner prompt to explicitly state that it is a system planner and not the manager persona.
- Added planner model configuration support:
  - `app.agents.workflow-planner-model`
  - when unset, planner falls back to the request default model rather than manager instructions/persona.
- Updated `MultiAgentOrchestrator` to consume `MultiAgentPlanner` output and focus on workflow execution plus final synthesis only.

### Resulting Role Split

- `manager agent`
  - still user-defined
  - still used for final synthesis style/instructions
- `system planner`
  - now backend-owned
  - responsible for selecting agents and deciding parallel vs sequential execution
- `team workflow executor`
  - runs bounded specialist steps and persists workflow state

### Verification

- `mvn -DskipTests=true compile` in `backend/` passed successfully after planner separation on 2026-03-17.

### Files Changed In This Entry

- `backend/src/main/java/com/harmony/backend/ai/agent/planner/MultiAgentPlanner.java`
- `backend/src/main/java/com/harmony/backend/ai/agent/planner/MultiAgentPlan.java`
- `backend/src/main/java/com/harmony/backend/ai/agent/planner/impl/DefaultMultiAgentPlanner.java`
- `backend/src/main/java/com/harmony/backend/ai/agent/runtime/MultiAgentOrchestrator.java`

## 2026-03-17 (Single-Agent Hybrid Planner)

### Summary

Split single-agent workflow planning out of `AgentWorkflowServiceImpl` and upgraded it to a hybrid planner architecture combining rule-first routing, optional LLM disambiguation, and backend execution fallback.

### Why This Was Needed

- Single-agent workflow execution had planner behavior, but it was still embedded inside the workflow service.
- That mixed planning, execution, answer composition, and memory updates in one class.
- Multi-agent had already been split into planner and executor layers, so single-agent needed the same long-term architecture boundary.
- A hybrid planner is a better fit than rule-only or LLM-only planning for long-term workflow-first execution.

### Implemented Changes

- Added `SingleAgentPlanner` as a dedicated planning abstraction.
- Added `SingleAgentPlan` as the explicit planner result model.
- Added `DefaultSingleAgentPlanner` implementing a hybrid planning strategy:
  - rule-first candidate evaluation,
  - strong-rule direct routing for obvious intents,
  - score-threshold and score-gap based auto-routing,
  - optional LLM planner disambiguation when the request is ambiguous,
  - backend fallback to the highest-scoring allowed skill when needed.
- Refactored `AgentWorkflowServiceImpl` so it now:
  - consumes `SingleAgentPlan`,
  - executes the selected workflow/skill,
  - records workflow progress,
  - composes the final answer,
  - no longer owns single-agent planning logic directly.
- Added single-agent planner configuration knobs:
  - `app.workflow.single-planner-rule-gap-threshold`
  - `app.workflow.single-planner-rule-auto-threshold`
  - `app.workflow.single-planner-llm-enabled`
  - `app.workflow.single-planner-model`

### Architecture Result

- `single-agent planner`
  - now decides `workflowKey`, `selectedSkillKey`, `normalizedInput`, `confidence`, and whether LLM planning was used.
- `single-agent workflow executor`
  - now focuses on execution, memory updates, and final answer generation.
- `multi-agent planner`
  - was already hybrid-like before, but remains more LLM-led with heuristic fallback.
- `single-agent planner`
  - is now explicitly hybrid and more rule-forward than the current multi-agent planner.

### Verification

- `mvn -DskipTests=true compile` in `backend/` passed successfully after single-agent hybrid planner changes on 2026-03-17.

### Files Changed In This Entry

- `backend/src/main/java/com/harmony/backend/modules/chat/service/workflow/planner/SingleAgentPlanner.java`
- `backend/src/main/java/com/harmony/backend/modules/chat/service/workflow/planner/SingleAgentPlan.java`
- `backend/src/main/java/com/harmony/backend/modules/chat/service/workflow/planner/impl/DefaultSingleAgentPlanner.java`
- `backend/src/main/java/com/harmony/backend/modules/chat/service/workflow/impl/AgentWorkflowServiceImpl.java`

## 2026-03-17 (Multi-Agent Rule-First Hybrid Planner)

### Summary

Upgraded the multi-agent planner from an LLM-first planner with heuristic fallback into a rule-first hybrid planner where LLM planning is only used for ambiguous candidate sets.

### Why This Was Needed

- Multi-agent planning has higher execution cost than single-agent planning because each extra selected specialist triggers additional workflow steps and synthesis noise.
- LLM-first planning was too willing to over-select agents for tasks that could be handled by one or two specialists.
- For multi-agent systems, the most important control point is candidate restriction before specialist execution, so rule-first routing is more appropriate.

### Implemented Changes

- Extended `MultiAgentPlan` with:
  - `usedLlmPlanner`
  - `confidence`
- Refactored `DefaultMultiAgentPlanner` into a true hybrid planner:
  - first ranks all candidate agents by role/name/description/skills/tools match,
  - narrows the candidate set to a bounded top-K,
  - directly returns a rule-based plan when one candidate is dominant or the task is simple,
  - only calls the LLM planner when the candidate set is ambiguous,
  - constrains the LLM planner to choose only from the already filtered candidate set.
- Added multi-agent planner configuration knobs:
  - `app.agents.workflow-planner-llm-enabled`
  - `app.agents.workflow-planner-rule-auto-gap`
  - `app.agents.workflow-planner-rule-auto-score`
  - `app.agents.workflow-planner-candidate-limit`
- Added lightweight task-complexity and dependency cues so rule plans can better decide:
  - whether a single specialist is enough,
  - whether multiple specialists should default to parallel or sequential execution.

### Architecture Result

- `single-agent planner`
  - already rule-forward hybrid
- `multi-agent planner`
  - now also rule-forward hybrid instead of LLM-first
- `LLM planner`
  - now acts as an ambiguity resolver rather than the primary selection authority

### Verification

- `mvn -DskipTests=true compile` in `backend/` passed successfully after multi-agent hybrid planner changes on 2026-03-17.

### Files Changed In This Entry

- `backend/src/main/java/com/harmony/backend/ai/agent/planner/MultiAgentPlan.java`
- `backend/src/main/java/com/harmony/backend/ai/agent/planner/impl/DefaultMultiAgentPlanner.java`
