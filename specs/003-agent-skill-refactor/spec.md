# Feature Specification: Refactor Skill Layer To Standard AgentTools

**Feature Branch**: `[003-agent-skill-refactor]`  
**Created**: 2026-03-19  
**Status**: Draft  
**Input**: User description: "Refactor the existing skill layer into a standard AgentTools-oriented architecture while preserving the main chat path and agent skill workflow."

## Feature Classification *(mandatory)*

### Change Type

- **Primary Domain**: skill-layer
- **Feature Shape**: refactor
- **Risk Level**: high

### Skill Classification *(mandatory when skill-layer is involved)*

- **Skill Scope**: mixed
- **Trigger Model**: hybrid
- **Dispatch Ownership**: chat + agent + dedicated skill router
- **Fallback Strategy**: default assistant fallback

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Preserve Existing Main Chat Chain During Refactor (Priority: P1)

As a user, I want ordinary chat and existing skill-enabled agent flows to keep working while the skill layer is refactored, so architecture cleanup does not break the main product path.

**Why this priority**: The chat chain is the highest-risk path. The refactor is invalid if it improves structure but regresses normal chat, tool follow-up, or agent skill behavior.

**Independent Test**: Run a normal chat request, a tool-enabled request, and a skill-enabled agent request and verify that all still complete through their current business path without functional regression.

**Acceptance Scenarios**:

1. **Given** a normal chat request without skill use, **When** the refactored system handles it, **Then** it remains on the ordinary assistant path.
2. **Given** an agent with allowed skills, **When** a request triggers a skill path, **Then** the system still selects, executes, and follows up with skill results as before.

### User Story 2 - Standardize Executable Capability Contracts (Priority: P2)

As a developer, I want executable capabilities to follow a standard AgentTool-oriented contract so that built-in tools, skill-backed tools, and future agent abilities share a consistent metadata and execution model.

**Why this priority**: The current split between `ai.tool` metadata-only interfaces and separate skill execution objects makes future extension harder. Standardization is the main architectural value of this refactor.

**Independent Test**: Inspect the refactored contracts and verify that executable capabilities expose standard identity, description, parameter schema, and execution result shape through a unified contract.

**Acceptance Scenarios**:

1. **Given** a built-in executable capability, **When** it is registered, **Then** it exposes standard metadata and execution behavior through the unified contract.
2. **Given** a managed skill definition, **When** it is resolved for execution, **Then** its execution path can be adapted into the same tool-oriented runtime contract.

### User Story 3 - Keep Skill Layer As Orchestration Instead Of Collapsing It (Priority: P3)

As a reviewer, I want skills to remain a higher-level orchestration layer instead of being flattened into raw tools, so the system can still model allowed skills, managed skill definitions, input normalization, and multi-step execution.

**Why this priority**: A naive refactor that turns every skill into a plain tool would erase the useful distinction that already exists in the codebase.

**Independent Test**: Verify that the refactored design still distinguishes:
- executable tools
- skill definitions
- skill planning/routing
- skill execution trace and fallback

**Acceptance Scenarios**:

1. **Given** a managed skill with tool bindings and input schema, **When** the refactor is complete, **Then** it still exists as a first-class skill definition rather than only a raw tool object.
2. **Given** a request that fails skill execution, **When** fallback handling runs, **Then** the system preserves skill-level diagnostics and mainline safety.

## Edge Cases

- What happens when a skill expands to multiple tool candidates?
- What happens when the tool-oriented contract needs structured JSON input but the existing skill path still passes normalized string input?
- What happens when a managed DB skill references a tool that now uses a stricter schema?
- What happens when the new AgentTool contract supports execution but existing callers still expect metadata-only tool registry behavior?

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The refactor MUST preserve current chat, tool-followup, and skill-followup behavior on the main user chain.
- **FR-002**: The system MUST define a standard executable AgentTool-oriented contract for runtime capabilities.
- **FR-003**: The refactor MUST keep skill definitions as a separate orchestration layer rather than collapsing all skill concepts into raw tools.
- **FR-004**: The system MUST support adapting existing managed skill definitions into the new contract without breaking current agent configuration semantics.
- **FR-005**: The system MUST preserve auth, quota, billing, fallback, and audit behavior through the refactor.
- **FR-006**: The system MUST document the boundary between registry, planner/router, executor, and follow-up logic.

### Skill Contract Requirements *(mandatory when skill-layer is involved)*

- **FR-SKILL-001**: The system MUST standardize capability metadata: key/name/description/parameter schema.
- **FR-SKILL-002**: The system MUST standardize execution result shape for tool- or skill-backed runtime execution.
- **FR-SKILL-003**: The system MUST preserve skill planning inputs such as requested skill key, normalized input, allowed skills, and selection reason.
- **FR-SKILL-004**: The system MUST preserve or improve execution traceability for skill success, failure, and fallback.
- **FR-SKILL-005**: The system MUST define a migration path from the current `AgentTool + AgentSkillDefinition + SkillExecutor` split to the new architecture.

### Key Entities *(include if feature involves data)*

- **AgentToolContract**: The unified runtime capability contract, including metadata, parameter schema, and execution result shape.
- **AgentSkillDefinition**: The orchestration-layer definition that maps skill identity, allowed inputs, tool bindings, and execution mode.
- **SkillRouteDecision**: The routing/planning artifact that selects a skill and records the reason.
- **SkillExecutionRecord**: The runtime trace that captures execution result, used tools, failure reason, and fallback state.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Existing chat and skill-enabled flows still pass targeted regression checks after the refactor.
- **SC-002**: Executable runtime capabilities expose a single standard metadata + execution contract.
- **SC-003**: Skill orchestration semantics remain explicit and are not lost in the refactor.
- **SC-004**: Operators and developers can identify route decision, execution result, and fallback outcome from the refactored path.
