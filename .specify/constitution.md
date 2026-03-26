# zlAI Specify Constitution

## 1. Purpose

This constitution defines the repository's project law: the non-negotiable engineering principles, risk tiers, and governance rules that override local convenience.

It is intentionally short. It answers:
- what must be protected
- what must be explicitly reasoned about
- what level of verification is required
- when exceptions are allowed

Execution workflow, checklists, and directory-specific operating rules belong in `AGENTS.md`, not here.

## 2. Rule Levels

This repository uses three rule levels:
- `MUST`: mandatory. Violations block the change unless an exception is recorded.
- `SHOULD`: default expectation. Deviations must be explained.
- `MAY`: optional guidance when useful.

## 3. Scope And Change Tiers

### 3.1 Scope

This constitution applies to the whole repository.

More local `AGENTS.md` files may tighten execution rules, but they must not weaken any `MUST` in this constitution.

### 3.2 Change Tiers

Use the smallest tier that honestly matches the change.

#### Tier 0: Trivial Change

Examples:
- typo, comment, formatting-only fix
- dead-link fix
- rename with no behavior change
- wording-only doc update

Tier 0 changes:
- MAY skip full solution-option analysis
- SHOULD still avoid hidden behavior changes
- SHOULD state briefly why the change is trivial when there is any doubt

#### Tier 1: Standard Change

Examples:
- ordinary feature work
- local refactor with behavior changes contained inside one module
- non-trivial docs/spec updates tied to implemented behavior

Tier 1 changes:
- MUST identify affected user chain and module boundary
- MUST compare 2 to 4 realistic options before implementation
- MUST define a verification plan matching risk

#### Tier 2: High-Risk Change

Examples:
- auth, billing, chat sync/stream, admin, RAG ingest/query, agent runtime, skill dispatch
- concurrency, idempotency, rate limiting, queues, retries, persistence semantics
- schema, contract, config, or operational behavior changes

Tier 2 changes:
- MUST satisfy all Tier 1 requirements
- MUST explain concurrency, capacity, and degraded behavior
- MUST validate failure paths, not only happy paths
- MUST record compatibility or rollback handling when applicable

## 4. Non-Negotiable Principles

### I. Core User Chains First

The platform's core user chains MUST remain stable before new capability is added.

This includes:
- auth
- chat sync and stream
- billing and quota
- RAG ingest and query
- admin and audit paths
- agent and skill execution flows

Requirements:
- Changes touching a core chain MUST explicitly name the affected chain first.
- Backward compatibility for API, schema, and default config MUST be preserved unless migration handling is documented.
- Hidden regressions in correctness, auditability, or operator control are not acceptable tradeoffs for speed.

### II. Options Before Commitment

For non-trivial changes, implementation MUST follow explicit option analysis before code changes begin.

Requirements:
- The option set MUST contain 2 to 4 realistic approaches.
- The comparison MUST cover:
  - correctness and robustness
  - maintainability
  - extensibility
  - concurrency and performance impact
  - implementation complexity
  - rollback or compatibility risk
- The chosen option MUST explain why it is the best fit for this repository now.

### III. Stable Boundaries Over Shortcuts

The codebase MUST evolve through explicit boundaries, not cross-layer shortcuts.

Requirements:
- Backend business logic MUST live in service/support layers, not controller glue.
- Provider-specific logic MUST stay behind adapters.
- Chat, Agent, Tool, Skill, RAG, User, and Admin responsibilities MUST remain separable and testable.
- Skill registry, routing, execution, and fallback MUST remain distinct concerns.

### IV. Concurrency, Capacity, And Degradation Safety

Hot paths and shared state MUST be designed for real load, not only single-request correctness.

Requirements:
- Shared mutable state MUST prefer atomic database/Redis operations, idempotency controls, or explicitly bounded locking/versioning.
- Queues, thread pools, retry logic, buffering, and timeouts MUST be bounded and justified.
- Failure handling MUST cover timeout, overload, dependency failure, duplicate/replay risk, and safe fallback or fast-fail behavior.
- High-traffic paths SHOULD avoid unnecessary scans, repeated lookups, and duplicate expensive work when safe reuse is available.

### V. Verification Must Match Risk

Testing and verification are part of the change, not follow-up decoration.

Requirements:
- High-risk changes MUST validate representative failure or rejection paths, not only successful execution.
- Changes touching concurrency, idempotency, billing, auth, rate limiting, async execution, RAG, or agent runtime MUST include targeted regression coverage where practical.
- If automation is not added, the change MUST record why and what was manually verified.

### VI. Grounded RAG And Governed Agent Behavior

RAG and Agent features are first-class product behavior and MUST be governable.

RAG requirements:
- RAG answer-generation changes MUST define how grounding, citations, or evidence references are attached, or justify why they are unavailable.
- When retrieval evidence is weak, conflicting, or empty, the user-facing path MUST prefer uncertainty or safe fallback over unsupported confidence.
- Source metadata and fallback reasons SHOULD remain observable.

Agent requirements:
- Agent-runtime changes MUST define lifecycle states, bounded autonomy, stop conditions, and recovery/resume behavior.
- Long-running agent execution MUST separate planning, state persistence, execution, observation, tool/skill invocation, and synthesis into explicit responsibilities.
- Agent memory and intermediate artifacts MUST not be treated as unbounded append-only prompt history.

## 5. Domain-Specific Required Checks

These checks apply only when the domain is involved.

### 5.1 Skill-Layer Changes

Skill-oriented changes MUST define:
- skill type
- trigger model
- execution contract
- authorization scope
- side effects
- fallback strategy

### 5.2 RAG Changes

RAG changes MUST preserve:
- UTF-8 safety
- chunking/vector compatibility
- ingest traceability
- evidence traceability

### 5.3 Agent Runtime Changes

Agent-runtime changes MUST document:
- lifecycle model
- persisted state shape
- bounded autonomy controls
- cancellation/resume strategy
- operator-visible trace or diagnostics

## 6. Exceptions And Governance

### 6.1 Exception Rule

Exceptions are allowed only when the change owner records:
- what rule is being bent
- why the normal path is not practical now
- the risk being accepted
- the exit criteria or follow-up condition

### 6.2 Review Rule

Reviews SHOULD reject changes that introduce:
- hidden coupling
- skipped option analysis for non-trivial work
- race-prone shared-state updates
- unbounded resource behavior
- high-risk behavior with weak verification
- ungoverned skill execution
- agent flows presented as autonomous runtime without lifecycle and bounded autonomy
- confident RAG behavior without grounded evidence handling

### 6.3 Amendment Rule

Constitution updates MUST edit this file directly and briefly record why the amendment was necessary.

**Version**: 2.0.0 | **Ratified**: 2026-03-18 | **Last Amended**: 2026-03-26