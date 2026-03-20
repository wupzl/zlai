# Tasks: Built-in Skill Routing

**Input**: Design documents from `/specs/002-builtin-skill-routing/`  
**Prerequisites**: `spec.md`, `plan.md`, `research.md`, `data-model.md`, `quickstart.md`, `contracts/skill-contract.md`

**Tests**: Include backend verification for hit, miss, invalid input, unauthorized, and execution-failure paths.

## Phase 1: Setup

- [ ] T001 Create or verify feature docs under `specs/002-builtin-skill-routing/`
- [ ] T002 Confirm the target backend modules for routing, execution, and traceability
- [ ] T003 Record local verification commands in `specs/002-builtin-skill-routing/quickstart.md`

## Phase 2: Foundations

- [ ] T004 Define built-in skill metadata shape in `specs/002-builtin-skill-routing/data-model.md`
- [ ] T005 Define route decision and execution trace contract in `specs/002-builtin-skill-routing/contracts/skill-contract.md`
- [ ] T006 Identify auth, quota, and fallback requirements for built-in skill routing
- [ ] T007 Identify the backend integration point where the router should intercept requests

## Phase 3: User Story 1 - Route Explicit Built-in Skill Calls (Priority: P1)

- [ ] T008 [US1] Add or update built-in skill metadata structures in the backend
- [ ] T009 [US1] Implement explicit built-in skill routing path
- [ ] T010 [US1] Implement built-in skill execution handoff
- [ ] T011 [US1] Add backend verification for explicit skill hit path

## Phase 4: User Story 2 - Distinguish Built-in Skill Routing From Ordinary Chat (Priority: P2)

- [ ] T012 [US2] Implement route decision recording for hit and miss paths
- [ ] T013 [US2] Ensure non-skill requests remain on ordinary assistant flow
- [ ] T014 [US2] Add backend verification for non-skill miss path and trace output

## Phase 5: User Story 3 - Support Safe Fallback For Misses and Failures (Priority: P3)

- [ ] T015 [US3] Implement fallback for no-match, invalid-input, and execution-failure cases
- [ ] T016 [US3] Add failure-path diagnostics and operator-visible trace fields
- [ ] T017 [US3] Add backend verification for invalid-input, unauthorized, and execution-failure cases

## Phase 6: Polish

- [ ] T018 Review config defaults and rollout safety for built-in skill routing
- [ ] T019 Update related docs after implementation
- [ ] T020 Run regression checks for impacted chat chains
