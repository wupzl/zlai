# Implementation Plan: Built-in Skill Routing

**Branch**: `[002-builtin-skill-routing]` | **Date**: 2026-03-19 | **Spec**: `/specs/002-builtin-skill-routing/spec.md`  
**Input**: Feature specification from `/specs/002-builtin-skill-routing/spec.md`

## Summary

Introduce a dedicated built-in skill routing layer that sits between ordinary chat input and skill execution. The router should evaluate explicit and hybrid built-in skill triggers, produce a route decision, execute the selected built-in skill through a separate execution path, and fall back safely when the skill is not applicable or fails.

## Technical Context

**Language/Version**: Java 21, Spring Boot 3.5, Markdown specs  
**Primary Dependencies**: Spring Boot, existing chat/agent modules, existing auth/quota infrastructure, logging/audit support  
**Storage**: Existing runtime state plus any required MySQL/Redis/post-processing persistence, depending on implementation  
**Testing**: JUnit for backend behavior, manual verification for end-to-end routing and fallback  
**Target Platform**: zlAI backend and its chat-facing request paths  
**Project Type**: platform backend feature  
**Performance Goals**: Built-in skill routing should add minimal overhead to non-skill requests and keep failure handling bounded  
**Constraints**: Must preserve existing chat behavior, auth rules, quota boundaries, and backward compatibility of normal assistant requests  
**Scale/Scope**: First iteration for built-in skill routing only, not user-defined or workflow skills

## Constitution Check

*GATE: Must pass before research and design start. Re-check after design is complete.*

- **Core User Chain Stability**: The change touches the chat request path, so it must protect ordinary assistant behavior and avoid routing regressions for non-skill requests.
- **Module Boundary Integrity**: Routing, execution, fallback, and traceability must stay separate from ordinary chat response generation.
- **Skill Contract First**: The feature must define built-in skill type, trigger rules, input contract, execution result shape, and fallback policy before implementation.
- **Observability & Governance**: The router must emit route decisions and execution traces that explain why a skill matched or failed.
- **Risk-Aligned Testing**: The feature requires backend verification for skill hit, miss, unauthorized invocation, invalid input, and execution failure.

## Project Structure

### Documentation (this feature)

```text
specs/002-builtin-skill-routing/
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   └── skill-contract.md
└── tasks.md
```

### Source Code (repository root)

```text
backend/
├── src/
│   ├── main/
│   └── test/
docs/
specs/
```

**Structure Decision**: Keep routing and execution work inside backend modules while documenting the feature under `specs/002-builtin-skill-routing/`.

## Skill Architecture *(mandatory when skill-layer is involved)*

- **Registry**: Built-in skill definitions should be represented as explicit metadata or code-backed definitions, not hidden inside ad hoc chat conditionals.
- **Routing**: A dedicated built-in skill router should evaluate explicit invocation first, then any hybrid rules allowed by the feature scope.
- **Execution**: Built-in skill execution should happen through a dedicated execution path, separate from default assistant rendering.
- **Fallback**: No-match, invalid-input, unauthorized, and execution-failure paths should fall back according to policy.
- **Traceability**: Route decisions and execution records should expose selected skill, rejection reasons, failure reason, and fallback state.

## Research & Design Outputs

### research.md

Compare rule-only routing against hybrid routing and document why the first iteration still keeps a strong explicit-invocation path.

### data-model.md

Define the conceptual entities for built-in skill routing and execution.

### contracts/

Define the minimum skill contract for built-in skill routing.

### quickstart.md

Describe the local verification flow for hit, miss, and fallback scenarios.

## Complexity Tracking

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| Dedicated router layer instead of inline chat conditionals | Built-in skill routing needs auditable selection and safe fallback | Inline branching inside chat flow would hide routing reasons and make future skill types harder to add |
