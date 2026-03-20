# Implementation Plan: Refactor Skill Layer To Standard AgentTools

**Branch**: `[003-agent-skill-refactor]` | **Date**: 2026-03-19 | **Spec**: `/specs/003-agent-skill-refactor/spec.md`  
**Input**: Feature specification from `/specs/003-agent-skill-refactor/spec.md`

## Summary

Refactor the current `ai.skill` and `ai.tool` split into a more standard AgentTools-oriented runtime contract while preserving the existing mainline behavior. The goal is not to delete the skill layer, but to clarify roles:
- `AgentTool` becomes the standard executable capability contract
- `AgentSkillDefinition` remains the orchestration-layer model
- planner/router stays responsible for selecting skills
- executor/follow-up stays responsible for runtime execution and safe continuation

## Technical Context

**Language/Version**: Java 21, Spring Boot 3.5, Markdown specs  
**Primary Dependencies**: Spring Boot, Jackson, existing chat/agent modules, optional future compatibility with Spring AI / LangChain4j tool-style contracts  
**Storage**: Existing MySQL skill definitions, existing billing/audit flows, optional new execution log persistence if required  
**Testing**: JUnit for backend regression and refactor safety, manual verification for chat and skill follow-up  
**Target Platform**: zlAI backend mainline chat and agent orchestration flow  
**Project Type**: platform backend refactor  
**Performance Goals**: Preserve or improve current mainline latency; avoid adding extra model roundtrips for ordinary chat  
**Constraints**: Do not break existing skill keys, agent skill configuration, billing flow, or tool follow-up logic  
**Scale/Scope**: Current built-in tools + managed DB skills + skill follow-up chain

## Constitution Check

*GATE: Must pass before research and design start. Re-check after design is complete.*

- **Core User Chain Stability**: The refactor touches chat, skill follow-up, agent orchestration, and billing-sensitive execution paths. Mainline behavior must be preserved.
- **Module Boundary Integrity**: Tool contract, skill definition, planning, execution, and follow-up must be separated instead of mixed into controller or assistant text parsing logic.
- **Skill Contract First**: The feature must define a standard executable tool contract, a preserved skill orchestration contract, and a migration path between old and new runtime shapes.
- **Observability & Governance**: Route decisions, execution outcomes, used tools, and fallback reasons must remain traceable.
- **Risk-Aligned Testing**: Refactor safety requires regression coverage for normal chat, skill hit, skill miss, disallowed skill, execution failure, and billing-adjacent paths.

## Project Structure

### Documentation (this feature)

```text
specs/003-agent-skill-refactor/
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   └── agent-tool-contract.md
└── tasks.md
```

### Source Code (repository root)

```text
backend/src/main/java/com/harmony/backend/ai/tool/
backend/src/main/java/com/harmony/backend/ai/skill/
backend/src/main/java/com/harmony/backend/ai/agent/
backend/src/main/java/com/harmony/backend/modules/chat/service/support/
backend/src/test/java/
```

**Structure Decision**: Keep the refactor focused on backend runtime contracts and follow-up services. Do not spread the first iteration into frontend or admin UI changes unless necessary for traceability.

## Skill Architecture *(mandatory when skill-layer is involved)*

- **Registry**: Keep registries explicit. `AgentToolRegistry` should resolve standardized executable capabilities; `AgentSkillRegistry` should continue resolving orchestration-layer skill definitions.
- **Routing**: Skill routing remains the responsibility of planner/router classes, not raw tool registry lookup alone.
- **Execution**: The runtime should converge on a standard executable tool contract, while `SkillExecutor` may act as the bridge from skill selection to tool-backed execution.
- **Fallback**: Existing disallowed-skill, execution-failure, and no-selection fallbacks must remain intact during migration.
- **Traceability**: Route decision, selected skill, used tools, model usage, and fallback outcome must stay visible in logs and memory/audit hooks.

## Proposed Refactor Direction

### 1. Do Not Replace Skill Layer With A Flat Interface

The repository already has:
- `AgentTool` for tool metadata
- `AgentSkillDefinition` for orchestration-level skill definitions
- `SkillPlanner` for routing
- `SkillExecutor` for execution
- `ToolFollowupService` / `MultiAgentOrchestrator` for mainline integration

A better refactor is:
- enrich `AgentTool` into a standard capability contract
- keep `AgentSkillDefinition` as the higher-level skill model
- make skill execution adapt into the same tool-oriented runtime result shape

### 2. Recommended Contract Shape

Instead of replacing everything with `execute(Map<String,Object>)`, prefer a contract closer to the current codebase:

```java
public interface AgentTool {
    String getKey();
    String getName();
    String getDescription();
    Map<String, Object> getParametersSchema();
    ToolExecutionResult execute(ToolExecutionRequest request);
}
```

Or, if execution must remain decoupled from definitions, split it into:
- `AgentToolDefinition`
- `ExecutableAgentTool`

This fits the current registries better than flattening everything into `AgentSkill.execute(Map<String,Object>)`.

### 3. Keep Skill As Orchestration

`AgentSkillDefinition` should continue to represent:
- skill key/name/description
- allowed input schema
- execution mode
- tool bindings
- optional step config

This preserves the current meaning of "skills assigned to an agent".

### 4. Migration Strategy

- Phase 1: introduce the standard tool execution contract without deleting old behavior
- Phase 2: adapt current tool implementations to the new contract
- Phase 3: adapt `SkillExecutor` to consume the new tool contract internally
- Phase 4: simplify duplicated metadata structures only after regression safety is proven

## Research & Design Outputs

### research.md

Compare flattening skill execution into one interface versus preserving the current layered design with a better runtime contract.

### data-model.md

Describe the post-refactor roles of tool contract, skill definition, route decision, and execution record.

### contracts/

Define the standard AgentTool-oriented execution contract and the migration notes from the current model.

### quickstart.md

List regression scenarios for normal chat, skill-enabled follow-up, disallowed skill, and failure fallback.

## Complexity Tracking

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| Preserve both skill and tool layers instead of merging everything into one type | The current product already relies on skill-level orchestration semantics | A flat `AgentSkill.execute(Map)` contract would erase planner/executor boundaries and make migration riskier |
