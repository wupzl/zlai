# zlAI-v2 Execution Charter

This file defines how work should be carried out in this repository.

When both apply:
- `.specify/constitution.md` defines project law and review gates.
- `AGENTS.md` defines execution workflow, required checks, and delivery discipline.
- More local `AGENTS.md` files may tighten rules for their directory.

## 1. Default Working Mode

For any change above Tier 0:
1. identify the affected user chain
2. identify the module boundary
3. list 2 to 4 realistic solution options
4. compare them using the constitution criteria
5. select one option and explain why it fits now
6. implement the smallest end-to-end increment that delivers value
7. verify the change at a depth matching risk

For Tier 0 changes:
- use the fast path
- avoid unnecessary process overhead
- do not silently include behavior changes

## 2. Required Planning Output

For Tier 1 and Tier 2 changes, the working notes or plan MUST explicitly cover:
- affected user chain
- module boundary
- selected option and tradeoffs
- shared mutable state impact, if any
- hot-path or read amplification impact, if any
- queue, retry, or thread-pool bounds, if any
- fallback or fast-fail behavior
- verification plan

If the change touches schema, config, API contract, runtime operations, or user-visible behavior, also state what docs/specs must be updated.

## 3. Execution Priorities

Default priorities in this repository:
- protect auth, chat, billing, RAG, admin, and agent flows first
- prefer small, verifiable, end-to-end changes over broad rewrites
- prefer explicit degradation over hidden overload
- prefer observable behavior over clever but opaque behavior
- prefer stable boundaries over short-term cross-layer shortcuts

## 4. Change Checklists

### 4.1 Standard Checklist

Use this for Tier 1 and Tier 2 changes.

Before implementation, check:
- Which user chain is affected?
- What module boundary is being changed?
- Is there shared mutable state?
- Is this on a hot path?
- Could this increase repeated reads, scans, or duplicate expensive work?
- Are timeout, retry, rejection, and overload behaviors explicit?
- What is the rollback or compatibility risk?
- How will the change be verified?

### 4.2 High-Risk Checklist

Use this when touching auth, billing, chat stream/sync, RAG, skill dispatch, agent runtime, concurrency, idempotency, rate limiting, queues, or persistence semantics.

Also check:
- What is the atomicity model?
- What prevents duplicate execution or lost updates?
- What are the queue/thread-pool/cache/context bounds?
- What is the degraded behavior if a dependency slows down or fails?
- Which failure paths are covered by tests or manual validation?
- What operator-visible signal exists for diagnosis?

## 5. Verification Standard

### 5.1 Minimum Expectation

Every non-trivial change SHOULD include targeted verification of changed behavior.

### 5.2 High-Risk Expectation

High-risk changes MUST validate representative failure paths, not only happy paths.

Typical expectations:
- backend: targeted JUnit coverage where practical
- frontend: targeted Vitest coverage where practical
- manual validation: required when automation is blocked, with residual risk stated explicitly

### 5.3 Verification Record

When reporting completion, state:
- what was tested
- what was not tested
- what risk remains

## 6. Preferred Change Shape

Prefer these shapes unless there is a clear reason not to:
- controller -> service/support -> adapter/repository separation
- provider-specific behavior behind adapters
- atomic DB/Redis operations over read-modify-write on shared state
- bounded caches, queues, retries, and context windows
- extraction from oversized mixed-responsibility files instead of further growth

## 7. Documentation And Spec Sync

Update docs/specs when the change alters:
- API contracts
- schema or seed behavior
- runtime-operational behavior
- config semantics
- skill contracts or routing behavior
- RAG evidence behavior
- agent lifecycle, persistence, autonomy, or recovery behavior

## 8. Directory Strategy

Use more local `AGENTS.md` files for domain-specific rules.

Main local override areas in this repository:
- `backend/src/main/java/com/harmony/backend/common/`
- `backend/src/main/java/com/harmony/backend/modules/chat/`
- `backend/src/main/java/com/harmony/backend/ai/rag/`
- `backend/src/main/java/com/harmony/backend/ai/agent/`
- `backend/src/main/java/com/harmony/backend/ai/skill/`
- `backend/src/main/java/com/harmony/backend/ai/tool/`
- `backend/src/main/java/com/harmony/backend/ai/runtime/`

## 9. Trivial Change Fast Path

A Tier 0 change may skip the full planning ritual when all of the following are true:
- no behavior change
- no schema/config/contract change
- no concurrency or state semantics change
- no core user-chain risk
- no ambiguity that would make review harder later

If any of those are false, do not use the fast path.

## 10. When In Doubt

- follow `.specify/constitution.md`
- choose the more robust option
- choose the more observable option
- shrink the scope before lowering the quality bar