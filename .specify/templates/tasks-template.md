---

description: "Task list template for feature implementation"
---

# Tasks: [FEATURE NAME]

**Input**: Design documents from `/specs/[###-feature-name]/`  
**Prerequisites**: `plan.md`, `spec.md`, plus `research.md`, `data-model.md`, `quickstart.md`, `contracts/` when present

**Tests**: Include test tasks whenever the feature changes risky behavior, APIs, skill routing, auth, billing, RAG, or admin behavior.

**Organization**: Group tasks by user story so each story can be delivered and verified independently.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no hard dependency)
- **[Story]**: Story label such as `US1`, `US2`, `US3`
- Include exact file paths in each task description

## Phase 1: Setup

- [ ] T001 Create or verify feature docs under `specs/[###-feature-name]/`
- [ ] T002 Capture local verification commands in `specs/[###-feature-name]/quickstart.md`
- [ ] T003 [P] Update or confirm affected architecture docs in `docs/`

## Phase 2: Foundations

- [ ] T004 Define or update the core data model and contracts for this feature
- [ ] T005 [P] Add config, logging, and traceability support needed by the feature
- [ ] T006 [P] Add or update auth/quota/audit guardrails if the feature touches protected paths
- [ ] T007 Add baseline tests or verification harness for the affected path

## Phase 3: User Story 1 - [Title] (Priority: P1)

- [ ] T008 [P] [US1] Add or update contract/integration coverage for the primary flow
- [ ] T009 [P] [US1] Add or update failure-path coverage for the primary flow
- [ ] T010 [P] [US1] Update data structures or schemas in [exact file path]
- [ ] T011 [P] [US1] Implement service/support logic in [exact file path]
- [ ] T012 [US1] Wire API/UI/dispatch path in [exact file path]
- [ ] T013 [US1] Add tracing, audit, or metrics for this story

## Phase 4: User Story 2 - [Title] (Priority: P2)

- [ ] T014 [P] [US2] Add or update targeted coverage for this story
- [ ] T015 [P] [US2] Implement the required model/service/UI changes in [exact file path]
- [ ] T016 [US2] Integrate with existing P1 flow without breaking independent testability
- [ ] T017 [US2] Add logging or fallback handling specific to this story

## Phase 5: User Story 3 - [Title] (Priority: P3)

- [ ] T018 [P] [US3] Add or update targeted coverage for this story
- [ ] T019 [P] [US3] Implement the required changes in [exact file path]
- [ ] T020 [US3] Complete fallback, observability, or migration details in [exact file path]

## Phase 6: Skill-Layer Tasks *(include when skill-layer is involved)*

- [ ] T021 Define or update skill metadata model in [exact file path]
- [ ] T022 [P] Implement skill registry or registry adapter in [exact file path]
- [ ] T023 [P] Implement skill routing/trigger logic in [exact file path]
- [ ] T024 Implement skill execution and fallback handling in [exact file path]
- [ ] T025 Add skill execution trace, audit, and diagnostics in [exact file path]

## Phase 7: Polish & Cross-Cutting Concerns

- [ ] T026 [P] Update docs in `docs/` and `specs/[###-feature-name]/quickstart.md`
- [ ] T027 Run regression checks for impacted chains
- [ ] T028 Review compatibility, config defaults, and operational notes
