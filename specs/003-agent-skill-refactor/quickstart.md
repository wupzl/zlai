# Quickstart: Refactor Skill Layer To Standard AgentTools

## Goal

Validate that the skill-layer refactor preserves the mainline while standardizing capability contracts.

## Scope Confirmation

- First iteration stays backend-only.
- No frontend or admin UI behavior is required for the initial migration.
- Mainline chat, skill follow-up, and multi-agent orchestration remain the protected chains.

## Verification Flow

1. Run a normal chat request and confirm it stays on the ordinary assistant path.
2. Run a skill-enabled request and confirm skill planning still selects the correct skill.
3. Run a disallowed-skill request and confirm the existing fallback still works.
4. Run a failing skill execution and confirm the failure is logged and surfaced safely.
5. Verify that built-in tools expose standardized metadata and execution contracts.
6. Verify that `DefaultSkillExecutor` prefers executable tools and falls back to `ToolExecutor` when needed.

## Verification Matrix

| Scenario | Entry Point | Expected Boundary | Expected Observability |
|----------|-------------|-------------------|------------------------|
| Normal chat, no tool/skill call | `ToolFollowupService.handleToolCallIfNeeded(...)` | Return original assistant text without invoking planner, tool executor, skill executor, billing, or memory hooks | No route-selection side effects |
| Skill JSON call, allowed and successful | `ToolFollowupService.handleToolCallIfNeeded(...)` | `SkillPlanner -> SkillExecutor -> follow-up LLM` | Selected skill reason remains available; billing is recorded; skill execution is written to memory |
| Skill JSON call, disallowed or unresolved | `ToolFollowupService.handleToolCallIfNeeded(...)` | Follow fallback path without executing skill | Fallback reason remains explicit |
| Tool JSON call, allowed and successful | `ToolFollowupService.handleToolCallIfNeeded(...)` | `ToolExecutor -> follow-up LLM` | Tool billing remains recorded |
| Team agent emits skill JSON | `MultiAgentOrchestrator.trySkillCall(...)` path | `SkillPlanner -> SkillExecutor -> team follow-up synthesis` | Workflow progress and tool usage remain recorded |
| Skill execution failure | single-agent and team-agent paths | Safe failure response without leaking partial state mutation | Failure remains visible in logs/result |
| Skill executor prefers executable tool | `DefaultSkillExecutor.execute(...)` | Direct executable tool contract is used before `ToolExecutor` fallback | Used tool remains visible in `SkillExecutionResult` |
| Skill executor fallback path | `DefaultSkillExecutor.execute(...)` | Falls back to `ToolExecutor` when executable tool is not registered | Output and token metadata remain preserved |

## Current Verification Status

- Implemented in [ToolFollowupServiceTest](D:\Code\zlAI-v2\backend\src\test\java\com\harmony\backend\modules\chat\service\support\ToolFollowupServiceTest.java):
  - ordinary chat stays on the mainline without planner/executor side effects
  - successful skill follow-up executes planner/executor and preserves billing plus memory hooks
  - disallowed or unresolved skill requests fall back through the explicit no-skill path without execution side effects
  - skill execution failure returns a safe failure message without billing, memory, or follow-up mutation
- Implemented in [AgentToolContractTest](D:\Code\zlAI-v2\backend\src\test\java\com\harmony\backend\ai\tool\AgentToolContractTest.java):
  - `AgentTool` now guarantees a non-null `parametersSchema`
  - `ExecutableAgentTool` provides the execution-oriented compatibility contract without changing current mainline execution routing
- Implemented in [AgentToolRegistryTest](D:\Code\zlAI-v2\backend\src\test\java\com\harmony\backend\ai\tool\AgentToolRegistryTest.java):
  - `AgentToolRegistry` still serves metadata consumers unchanged
  - registry now exposes executable-capable tools separately for future migration
  - tool definitions now retain `parametersSchema` and executable capability metadata
- Implemented in [BuiltinExecutableToolTest](D:\Code\zlAI-v2\backend\src\test\java\com\harmony\backend\ai\tool\BuiltinExecutableToolTest.java):
  - built-in tools implement `ExecutableAgentTool`
  - built-in tool execution delegates to existing handlers with the tool's own stable key
- Implemented in [AgentExecutionResultViewTest](D:\Code\zlAI-v2\backend\src\test\java\com\harmony\backend\ai\runtime\AgentExecutionResultViewTest.java):
  - `ToolExecutionResult` and `SkillExecutionResult` now share a common runtime result view
- Implemented in [DefaultSkillExecutorTest](D:\Code\zlAI-v2\backend\src\test\java\com\harmony\backend\ai\skill\impl\DefaultSkillExecutorTest.java):
  - `DefaultSkillExecutor` prefers executable tools
  - `DefaultSkillExecutor` falls back to `ToolExecutor` when no executable tool is registered
- Repaired [MultiAgentOrchestratorTest](D:\Code\zlAI-v2\backend\src\test\java\com\harmony\backend\ai\agent\runtime\MultiAgentOrchestratorTest.java) so targeted regression can compile against the current orchestrator constructor.
- Targeted regression passed with:
  - `mvn -q "-Dtest=AgentExecutionResultViewTest,AgentToolContractTest,AgentToolRegistryTest,BuiltinExecutableToolTest,DefaultSkillExecutorTest,ToolFollowupServiceTest,MultiAgentOrchestratorTest" test`

## T019 Migration Safety Review

- Removed only low-risk duplication confirmed by regression:
  - duplicate `AgentExecutionResultView` imports in `ToolExecutionResult` and `SkillExecutionResult`
- Explicitly retained the following parallel structures because they are still migration-safe and behaviorally necessary:
  - `ToolHandler` and `ToolExecutor` as the general runtime fallback path
  - `ExecutableAgentTool` as the gradual contract-upgrade path
  - `AgentToolDefinition` as the operator-facing metadata shape returned by registry/controller layers
- Review conclusion:
  - no further structural deletion is safe yet without broadening regression scope to controller, workflow, and agent-management surfaces
  - the current state is intentionally transitional, not accidental duplication
## Review Questions

- Did normal chat behavior remain unchanged?
- Did skill routing still produce a selected skill and reason?
- Did execution results still carry model usage, used tools, and error information?
- Is the new tool contract concrete enough for future Spring AI / LangChain4j alignment?
- Can built-in tools participate in the new contract without bypassing `ToolHandler` safety boundaries?

## T001 Integration Baseline

### Current module boundaries

- `ai.tool.AgentTool` is metadata-first and now also carries `parametersSchema`.
- `ai.tool.AgentToolRegistry` serves metadata lookup for all tools and executable lookup for tools that implement `ExecutableAgentTool`.
- `ai.tool.ToolExecutor` remains the active general tool execution boundary.
- `ai.skill.AgentSkillDefinition` remains the orchestration-layer model.
- `ai.skill.SkillPlanner` remains the route-selection boundary.
- `ai.skill.SkillExecutor` now prefers executable tool contracts internally but still falls back to `ToolExecutor`.

### Mainline integration points

- `ToolFollowupService` is the single-agent chat integration point. It parses assistant JSON action calls, checks policy, invokes `SkillPlanner` or direct tool execution, records billing and memory hooks, and performs LLM follow-up with tool or skill output.
- `MultiAgentOrchestrator` is the team-agent integration point. Specialist agents may emit JSON skill requests, which are re-planned through `SkillPlanner`, executed through `SkillExecutor`, and then synthesized back into the workflow answer.

### Traceability and safety constraints confirmed

- Route decision visibility already exists through `SkillPlan` and log lines in `ToolFollowupService` and `DefaultSkillExecutor`.
- Execution outcome visibility already exists through `ToolExecutionResult`, `SkillExecutionResult`, and the shared `AgentExecutionResultView`.
- Mainline-sensitive hooks already exist in `ToolFollowupService`: billing is recorded after successful tool or skill execution, and skill success records agent memory.
- Fallback behavior remains split by concern: disallowed skill, disallowed tool, no selection, tool failure, search fallback, and tool-executor fallback each have separate paths.

