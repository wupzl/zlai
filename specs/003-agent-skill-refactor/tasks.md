# Tasks: Refactor Skill Layer To Standard AgentTools

**Input**: Design documents from `/specs/003-agent-skill-refactor/`  
**Prerequisites**: `spec.md`, `plan.md`, `research.md`, `data-model.md`, `quickstart.md`, `contracts/agent-tool-contract.md`

**Tests**: Include backend regression verification for normal chat, skill hit, skill miss, disallowed skill, execution failure, and billing-adjacent behavior.

## Phase 1: Setup

- [x] T001 Confirm the current tool/skill integration points in `ai.tool`, `ai.skill`, `ToolFollowupService`, and `MultiAgentOrchestrator`
- [x] T002 Capture verification scenarios in `specs/003-agent-skill-refactor/quickstart.md`
- [x] T003 Confirm the migration scope stays backend-only for the first iteration

## Phase 2: Foundations

- [x] T004 Define the target AgentTool-oriented execution contract in `specs/003-agent-skill-refactor/contracts/agent-tool-contract.md`
- [x] T005 Define post-refactor entity roles in `specs/003-agent-skill-refactor/data-model.md`
- [x] T006 Identify which current result types should converge or be adapted
- [x] T007 Identify where route decision, execution trace, and fallback visibility must be preserved

## Phase 3: User Story 1 - Preserve Existing Main Chat Chain During Refactor (Priority: P1)

- [x] T008 [US1] Add regression coverage for normal chat and skill-enabled follow-up paths
- [x] T009 [US1] Add regression coverage for disallowed-skill and execution-failure fallback
- [x] T010 [US1] Refactor integration points without changing externally visible mainline behavior

## Phase 4: User Story 2 - Standardize Executable Capability Contracts (Priority: P2)

- [x] T011 [US2] Refactor `ai.tool.AgentTool` toward a standard executable capability contract or introduce a compatible execution sub-interface
- [x] T012 [US2] Update `AgentToolRegistry` to support the standardized contract
- [x] T013 [US2] Adapt built-in tool implementations to the new contract
- [x] T014 [US2] Adapt execution result types so tool-backed and skill-backed execution expose compatible runtime output

## Phase 5: User Story 3 - Keep Skill Layer As Orchestration Instead Of Collapsing It (Priority: P3)

- [x] T015 [US3] Keep `AgentSkillDefinition` as the orchestration-layer model
- [x] T016 [US3] Adapt `SkillExecutor` to consume the new tool-oriented contract internally
- [x] T017 [US3] Preserve planner/router semantics and selection reason visibility
- [x] T018 [US3] Preserve memory, billing, and follow-up hooks on successful and failed execution

## Phase 6: Polish

- [x] T019 Review migration safety and remove duplicated structures only if no regression remains
- [x] T020 Update related docs and architecture notes
- [x] T021 Run targeted regression checks for impacted chat and agent chains

