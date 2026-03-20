# zlAI Specify Constitution

## Core Principles

### I. Core User Chain Stability First
Any change MUST protect the platform's core user chains before adding new capability. This includes auth, chat, GPT, agent, RAG, admin, billing, and any skill dispatch path. A feature that adds power but destabilizes streaming chat, message persistence, authorization, billing, or admin traceability is incomplete.

Constraints:
- Any change touching chat, agent orchestration, RAG ingest/query, billing, auth, admin APIs, or skill dispatch MUST explicitly identify the affected user chain first.
- API contracts, database schema, and default configuration MUST remain backward compatible unless a migration plan is documented.
- Breaking changes MUST include migration notes, fallback switches, or compatibility handling.

### II. Module Boundaries Before Shortcuts
The system MUST evolve through stable module boundaries, not cross-layer shortcuts. Backend code should preserve controller, service, adapter, repository/mapper, and support responsibilities. Frontend code should keep API access, view logic, and reusable components separated. Skill-layer code MUST not leak provider-specific or prompt-specific logic across unrelated modules.

Constraints:
- New backend business logic belongs in service/support, not controller glue.
- Model-provider logic MUST stay behind adapters.
- Chat, Agent, Tool, Skill, RAG, User, and Admin responsibilities MUST remain separable and testable.
- Skill registry, routing, execution, and fallback MUST be modeled as distinct concerns.

### III. Skill Contracts Before Skill Execution
Skill-layer changes MUST be contract-first. Before implementation, each skill capability MUST define its type, trigger mode, input/output schema, dependency boundary, authorization scope, side effects, and fallback behavior. Skill execution without a clear contract is not acceptable.

Constraints:
- Every skill-oriented feature MUST define `Skill Type`, `Trigger Model`, `Execution Contract`, and `Fallback Strategy` in its spec.
- Skill execution MUST not silently bypass auth, quota, tenant isolation, or audit rules.
- Built-in skills, user skills, workflow skills, and tool-wrapper skills MUST be distinguishable in design and runtime handling.

### IV. Observability and Governance by Default
zlAI is a platform, not a one-off demo. New features MUST be observable, traceable, and governable. Skill-layer behavior MUST expose enough information for debugging, audit, and runtime control.

Constraints:
- Long-running or async tasks MUST expose status, error result, or traceable logs.
- Admin-relevant actions MUST retain auditability.
- Skill dispatch MUST record why a skill matched, what it consumed, what it produced, and why it failed or fell back.
- Production behavior MUST be controlled through configuration, not hardcoded constants hidden inside logic.

### V. Testing Must Cover Risk, Not Just Happy Paths
Testing is part of the change, not optional decoration. Coverage depth MUST match risk. This project includes auth, quota, rate limiting, streaming output, multi-model routing, document ingest, retrieval, and skill orchestration. High-risk changes cannot rely only on manual verification.

Constraints:
- Backend changes involving API, auth, orchestration, skill routing, or RAG behavior SHOULD add or update JUnit coverage where practical.
- Frontend changes involving routing, API wrappers, or key user interactions SHOULD add or update Vitest coverage where practical.
- High-risk changes MUST validate failure paths, including unauthorized access, quota exhaustion, timeout, provider failure, empty retrieval, skill conflict, and fallback execution.
- If automation is not added, the plan MUST state why and define manual verification steps.

### VI. Grounded RAG Responses Before Confident Claims
RAG-generated answers MUST prefer grounded claims over fluent but unsupported claims. When the system answers from retrieved knowledge, it SHOULD expose attributable evidence, avoid fabricated citations, and degrade gracefully when grounding is weak.

Constraints:
- RAG answer-generation changes MUST define how citations are produced, attached, and rendered, or explicitly justify why citations are unavailable.
- Grounding or hallucination-mitigation logic MUST not fabricate source titles, section names, or document identifiers that were not present in the retrieved evidence.
- When retrieval evidence is weak, conflicting, or empty, the user-facing path MUST prefer uncertainty, partial answer, or safe fallback over high-confidence unsupported output.
- Grounding score, citation metadata, and fallback reasons SHOULD be observable in logs, response metadata, or admin-visible diagnostics.

## Engineering Constraints

- Primary stack remains:
  - Frontend: Vue 3, Vue Router, Vite, Vitest
  - Backend: Java 21, Spring Boot 3.5, Spring Security, WebFlux, MyBatis-Plus
  - Data: MySQL, Redis, PostgreSQL + pgvector
- Repository structure defaults to: `frontend/`, `backend/`, `docs/`, `test/`, `.specify/`, `specs/`
- API changes MUST update the corresponding API documentation.
- Database or seed changes MUST be delivered through explicit SQL or managed schema changes.
- RAG changes MUST preserve UTF-8 safety, chunking/vector compatibility, ingest traceability, and evidence traceability.
- Skill-layer changes MUST document registry shape, routing strategy, execution trace, and fallback behavior.

## Delivery Workflow

1. Define the affected user chain and module boundary before implementation.
2. Run a constitution check during planning:
   - Does this protect the core user chain?
   - Does it preserve module boundaries?
   - Does it define a skill contract when skill-layer logic is involved?
   - Does it include observability and governance?
   - Does test depth match risk?
3. Prefer the smallest end-to-end increment that delivers real value.
4. Validate targeted behavior first, then run affected regression checks.
5. Any schema, config, contract, or runtime-operational change MUST be written into the feature docs.

## Governance

- This constitution overrides local convenience and temporary shortcuts.
- Plans generated from `.specify/templates/plan-template.md` MUST contain an explicit Constitution Check.
- Exceptions MUST be recorded in `Complexity Tracking` with justification and rejected simpler alternatives.
- Constitution updates MUST edit this file directly and record why the amendment was necessary.
- Reviews SHOULD reject hidden coupling, untraceable behavior, high-risk changes without verification, ungoverned skill execution, or confident RAG output without grounded evidence handling.

**Version**: 1.2.0 | **Ratified**: 2026-03-18 | **Last Amended**: 2026-03-19
