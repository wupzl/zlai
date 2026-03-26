# Implementation Plan: [FEATURE]

**Branch**: `[###-feature-name]` | **Date**: [DATE] | **Spec**: [link]  
**Input**: Feature specification from `/specs/[###-feature-name]/spec.md`

## Summary

[Summarize the user value, primary technical approach, and major constraints]

## Solution Options

*Required for any non-trivial modification. List 2 to 4 realistic options before implementation and choose one.*

| Option | Description | Robustness | Maintainability | Extensibility | Concurrency / Performance | Complexity | Main Risks |
|--------|-------------|------------|-----------------|---------------|---------------------------|------------|------------|
| A | [approach] | [assessment] | [assessment] | [assessment] | [assessment] | [assessment] | [risk] |
| B | [approach] | [assessment] | [assessment] | [assessment] | [assessment] | [assessment] | [risk] |
| C | [optional approach] | [assessment] | [assessment] | [assessment] | [assessment] | [assessment] | [risk] |
| D | [optional approach] | [assessment] | [assessment] | [assessment] | [assessment] | [assessment] | [risk] |

**Selected Approach**: [chosen option and why it is the best fit here]

## Technical Context

**Language/Version**: [e.g., Java 21, Vue 3, TypeScript 5.x]  
**Primary Dependencies**: [e.g., Spring Boot, Vue Router, pgvector, Redis]  
**Storage**: [e.g., MySQL, Redis, PostgreSQL, files, N/A]  
**Testing**: [e.g., JUnit, Vitest, manual verification]  
**Target Platform**: [e.g., Linux server, web browser, admin console]  
**Project Type**: [e.g., web application / platform backend / mixed]  
**Performance Goals**: [e.g., p95 latency target, async throughput target]  
**Constraints**: [e.g., backward compatibility, auth boundary, quota limits]  
**Scale/Scope**: [e.g., all users, admin-only, internal architecture only]

## Constitution Check

*GATE: Must pass before research and design start. Re-check after design is complete.*

- **Core User Chain Stability**: [How the affected user chain is protected]
- **Solution Option Review**: [Which 2 to 4 options were compared and why the selected one is preferred]
- **Module Boundary Integrity**: [How responsibilities are kept separated]
- **Skill Contract First**: [If skill-layer is involved, define skill type, trigger, contract, fallback]
- **Observability & Governance**: [How traces, logs, admin visibility, and config control are handled]
- **Concurrency / Capacity Safety**: [How races, backpressure, queue limits, retries, timeouts, and overload behavior are handled]
- **Risk-Aligned Testing**: [What automated/manual verification will cover and why]

## Project Structure

### Documentation (this feature)

```text
specs/[###-feature]/
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
└── tasks.md
```

### Source Code (repository root)

```text
backend/
├── src/
│   ├── main/
│   └── test/
frontend/
├── src/
└── tests/
docs/
test/
```

**Structure Decision**: [Describe what part of the repository this feature will touch and why]

## Skill Architecture *(mandatory when skill-layer is involved)*

- **Registry**: [Where skill definitions live and how they are versioned]
- **Routing**: [How a request becomes a skill selection]
- **Execution**: [Who runs the skill and how dependencies are resolved]
- **Fallback**: [What happens when skill selection or execution fails]
- **Traceability**: [What is logged and how operators inspect it]

## Research & Design Outputs

### research.md

- Open questions
- Alternatives considered
- Rejected approaches and reasons

### data-model.md

- Runtime entities
- Stored entities
- State transitions
- Compatibility notes

### contracts/

- API contract changes
- Skill contract shape
- Internal dispatch contract if needed

### quickstart.md

- Local setup
- Minimal verification flow
- Failure-path verification

## Complexity Tracking

> Fill ONLY if Constitution Check has violations that must be justified.

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| [example] | [reason] | [why simpler path failed] |
