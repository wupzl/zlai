# zlAI Specify Constitution

## Core Principles

### I. Core User Chain Stability First
Any change MUST protect the platform's core user chains before adding new capability. This includes auth, chat, GPT, agent, RAG, admin, billing, and any skill dispatch path. A feature that adds power but destabilizes streaming chat, message persistence, authorization, billing, or admin traceability is incomplete.

Constraints:
- Any change touching chat, agent orchestration, RAG ingest/query, billing, auth, admin APIs, or skill dispatch MUST explicitly identify the affected user chain first.
- API contracts, database schema, and default configuration MUST remain backward compatible unless a migration plan is documented.
- Breaking changes MUST include migration notes, fallback switches, or compatibility handling.

### II. Solution Options Before Code Changes
Any non-trivial modification MUST evaluate common solution paths before implementation. The goal is to prevent premature patching, local overfitting, and hidden architectural debt. Before code changes begin, the change owner MUST list 2 to 4 realistic solution options, compare them against project constraints, and explicitly choose the most suitable one.

Constraints:
- The option set MUST contain 2 to 4 feasible approaches. Single-solution plans are not acceptable for non-trivial work.
- The comparison MUST cover at least: correctness/robustness, maintainability, extensibility, concurrency/performance impact, implementation complexity, and rollback or compatibility risk.
- The chosen approach MUST state why it is preferred in this repository now, not only why alternatives are imperfect.
- Temporary fixes are acceptable only if marked as an interim option with exit criteria and follow-up conditions.

### III. Module Boundaries Before Shortcuts
The system MUST evolve through stable module boundaries, not cross-layer shortcuts. Backend code should preserve controller, service, adapter, repository/mapper, and support responsibilities. Frontend code should keep API access, view logic, and reusable components separated. Skill-layer code MUST not leak provider-specific or prompt-specific logic across unrelated modules.

Constraints:
- New backend business logic belongs in service/support, not controller glue.
- Model-provider logic MUST stay behind adapters.
- Chat, Agent, Tool, Skill, RAG, User, and Admin responsibilities MUST remain separable and testable.
- Skill registry, routing, execution, and fallback MUST be modeled as distinct concerns.

### IV. Skill Contracts Before Skill Execution
Skill-layer changes MUST be contract-first. Before implementation, each skill capability MUST define its type, trigger mode, input/output schema, dependency boundary, authorization scope, side effects, and fallback behavior. Skill execution without a clear contract is not acceptable.

Constraints:
- Every skill-oriented feature MUST define `Skill Type`, `Trigger Model`, `Execution Contract`, and `Fallback Strategy` in its spec.
- Skill execution MUST not silently bypass auth, quota, tenant isolation, or audit rules.
- Built-in skills, user skills, workflow skills, and tool-wrapper skills MUST be distinguishable in design and runtime handling.

### V. Observability and Governance by Default
zlAI is a platform, not a one-off demo. New features MUST be observable, traceable, and governable. Skill-layer behavior MUST expose enough information for debugging, audit, and runtime control.

Constraints:
- Long-running or async tasks MUST expose status, error result, or traceable logs.
- Admin-relevant actions MUST retain auditability.
- Skill dispatch MUST record why a skill matched, what it consumed, what it produced, and why it failed or fell back.
- Production behavior MUST be controlled through configuration, not hardcoded constants hidden inside logic.

### VI. Concurrency, Capacity, and Degradation Safety
zlAI MUST remain safe under load, not only correct in single-request execution. Any change on hot paths or shared state MUST consider concurrency behavior, bounded resource usage, and graceful degradation. A change that works functionally but risks duplicate execution, lost updates, thread starvation, unbounded memory growth, or cascading latency is incomplete.

Constraints:
- Changes touching auth, rate limiting, chat, streaming, billing, session state, async execution, Redis, database counters, task queues, or RAG ingest/query MUST explicitly describe concurrency behavior.
- Shared mutable state MUST prefer atomic database/Redis operations, idempotency controls, locks with clear scope, or versioned updates over read-modify-write races.
- Thread pools, queues, retry logic, buffering, and timeouts MUST be bounded and justified. Rejection and overload behavior MUST be explicit.
- High-traffic paths SHOULD avoid full scans, repeated config/database lookups, and unnecessary cross-service round trips when cacheable or batchable alternatives exist.
- Failure mode design MUST cover timeout, backpressure, partial dependency failure, replay/duplicate requests, and safe fallback or fast-fail behavior.

### VII. Testing Must Cover Risk, Not Just Happy Paths
Testing is part of the change, not optional decoration. Coverage depth MUST match risk. This project includes auth, quota, rate limiting, streaming output, multi-model routing, document ingest, retrieval, and skill orchestration. High-risk changes cannot rely only on manual verification.

Constraints:
- Backend changes involving API, auth, orchestration, skill routing, or RAG behavior SHOULD add or update JUnit coverage where practical.
- Frontend changes involving routing, API wrappers, or key user interactions SHOULD add or update Vitest coverage where practical.
- High-risk changes MUST validate failure paths, including unauthorized access, quota exhaustion, timeout, provider failure, empty retrieval, skill conflict, and fallback execution.
- High-risk concurrent changes SHOULD validate duplicate requests, shared-state races, overload behavior, and degraded dependency scenarios where practical.
- If automation is not added, the plan MUST state why and define manual verification steps.

### VIII. Grounded RAG Responses Before Confident Claims
RAG-generated answers MUST prefer grounded claims over fluent but unsupported claims. When the system answers from retrieved knowledge, it SHOULD expose attributable evidence, avoid fabricated citations, and degrade gracefully when grounding is weak.

Constraints:
- RAG answer-generation changes MUST define how citations are produced, attached, and rendered, or explicitly justify why citations are unavailable.
- Grounding or hallucination-mitigation logic MUST not fabricate source titles, section names, or document identifiers that were not present in the retrieved evidence.
- When retrieval evidence is weak, conflicting, or empty, the user-facing path MUST prefer uncertainty, partial answer, or safe fallback over high-confidence unsupported output.
- Grounding score, citation metadata, and fallback reasons SHOULD be observable in logs, response metadata, or admin-visible diagnostics.

### IX. Autonomous Agent Runtime Before Linear Agent Flows
The `ai/agent` layer MUST evolve toward autonomous, stateful, long-running execution rather than one-shot linear orchestration. Agent work should be modeled as planning, step execution, waiting, resuming, delegation, tool/skill usage, and completion over time. A design that only handles a single forward pass without durable progress, interruptibility, or bounded autonomy is an interim form, not the target architecture.

Constraints:
- Agent changes MUST define agent lifecycle states such as planned, running, waiting, blocked, resumed, failed, cancelled, and completed when applicable.
- Long-running agent execution MUST separate planning, state persistence, execution, tool/skill invocation, observation, and final synthesis into explicit responsibilities.
- Agent designs MUST support bounded autonomy: max steps, max tool calls, timeout budget, cancellation rules, and escalation or fallback behavior.
- Agent state transitions MUST be traceable and recoverable enough to support retries, resume, or operator-visible diagnostics where practical.
- Agent memory, tool outputs, and skill results MUST not be treated as an unbounded append-only prompt; summarization, pruning, or checkpoint strategy MUST be considered.
- Multi-agent designs MUST make delegation, coordination, and result aggregation explicit rather than hiding them inside a single opaque prompt.

## Engineering Constraints

- Primary stack remains:
  - Frontend: Vue 3, Vue Router, Vite, Vitest
  - Backend: Java 21, Spring Boot 3.5, Spring Security, WebFlux, MyBatis-Plus
  - Data: MySQL, Redis, PostgreSQL + pgvector
- Repository structure defaults to: `frontend/`, `backend/`, `docs/`, `test/`, `.specify/`, `specs/`
- Architecture decisions for non-trivial changes MUST be captured in the feature plan, not only in commit messages or chat discussion.
- API changes MUST update the corresponding API documentation.
- Database or seed changes MUST be delivered through explicit SQL or managed schema changes.
- RAG changes MUST preserve UTF-8 safety, chunking/vector compatibility, ingest traceability, and evidence traceability.
- Skill-layer changes MUST document registry shape, routing strategy, execution trace, and fallback behavior.
- Agent-runtime changes MUST document lifecycle model, persisted state shape, bounded autonomy controls, recovery/resume strategy, and operator-visible execution trace.

## Delivery Workflow

1. Define the affected user chain and module boundary before implementation.
2. Run a constitution check during planning:
   - Does this protect the core user chain?
   - Were 2 to 4 solution options considered and was one chosen with explicit tradeoffs?
   - Does it preserve module boundaries?
   - Does it define a skill contract when skill-layer logic is involved?
   - Does it define an autonomous agent lifecycle and bounded autonomy model when agent-runtime logic is involved?
   - Does it include observability and governance?
   - Does it explain concurrency, capacity, and degradation behavior?
   - Does test depth match risk?
3. Prefer the smallest end-to-end increment that delivers real value.
4. Validate targeted behavior first, then run affected regression checks.
5. Any schema, config, contract, or runtime-operational change MUST be written into the feature docs.

## Governance

- This constitution overrides local convenience and temporary shortcuts.
- Plans generated from `.specify/templates/plan-template.md` MUST contain an explicit Constitution Check.
- Plans for non-trivial modifications MUST contain a `Solution Options` section with 2 to 4 alternatives and a selected approach.
- Exceptions MUST be recorded in `Complexity Tracking` with justification and rejected simpler alternatives.
- Constitution updates MUST edit this file directly and record why the amendment was necessary.
- Reviews SHOULD reject hidden coupling, untraceable behavior, skipped option analysis, race-prone shared-state updates, unbounded resource behavior, high-risk changes without verification, ungoverned skill execution, linear one-shot agent flows presented as autonomous runtimes, or confident RAG output without grounded evidence handling.

**Version**: 1.4.0 | **Ratified**: 2026-03-18 | **Last Amended**: 2026-03-21
