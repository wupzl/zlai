# Research: Optimize RAG Grounding With Citations And Hallucination Checks

## 1. Problem Statement

The current RAG system can retrieve relevant evidence and produce a usable answer, but the final user-visible answer does not consistently expose where the answer came from. It also lacks an explicit grounding gate before returning the final answer, so unsupported claims can still be phrased fluently.

## 2. What We Are Optimizing In Phase 1

Phase 1 focuses on three concrete outcomes:

- add citation metadata to RAG-backed answers
- use retrieval-derived evidence to judge whether the answer is sufficiently grounded
- downgrade to uncertainty or partial answer when grounding is weak

This phase does **not** attempt to build a full academic hallucination benchmark or a heavyweight LLM-as-judge subsystem.

## 3. Options Considered

### Option A - Rule-Based Grounding From Retrieved Evidence

Approach:
- derive citation items directly from retrieved matches
- compute grounding status from retrieval evidence quality and answer-to-evidence overlap
- downgrade when coverage or citation eligibility is weak

Pros:
- low latency and low cost
- deterministic and easier to debug
- easy to attach to current RAG answer path
- no additional model dependency required

Cons:
- less semantically rich than a judge model
- overlap heuristics can miss subtle unsupported claims

### Option B - LLM Judge For Hallucination Detection

Approach:
- after answer generation, call a second model to evaluate whether answer claims are supported by retrieved evidence

Pros:
- more expressive
- can catch some unsupported paraphrases that lexical heuristics miss

Cons:
- extra latency and cost
- harder to make deterministic
- harder to operate safely without carefully designed prompts and fallback logic
- adds another provider dependency to a path that is already latency-sensitive

### Decision

Choose **Option A** for phase 1.

Reason:
- It aligns with the current architecture and current need.
- It solves the immediate product gap: no citations and no explicit grounded fallback.
- It preserves observability and keeps the first increment end-to-end deliverable.

## 4. Citation Rendering Shape

### Option A - Inline Markers Only

Pros:
- compact in the answer body

Cons:
- harder to implement well across markdown rendering paths
- harder to inspect when citations are dense

### Option B - Citation List Only

Pros:
- simpler backend contract and simpler frontend rendering
- stable even if answer text changes slightly

Cons:
- weaker claim-to-source mapping

### Option C - List First, Inline Later

Pros:
- smallest safe increment
- preserves future room for inline markers

### Decision

Choose **Option C**.

Phase 1 returns a structured citation list. Inline markers can be added later once payload shape and rendering are stable.

## 5. Grounding Policy Choice

### Candidate policies

- always answer if retrieval returned any evidence
- answer only if evidence passes a minimum grounding threshold
- answer partially and explicitly mark uncertainty when only part of the request is grounded

### Decision

Use a tiered rule-based policy:
- strong evidence -> return normal grounded answer with citations
- partial evidence -> return limited answer with citations and uncertainty note
- weak or empty evidence -> return safe fallback stating insufficient source support

## 6. Operational Notes

- Thresholds must be config-driven.
- Citation payload size must be bounded.
- Diagnostics should be available without exposing sensitive internals to ordinary users.
- Existing clients must remain compatible if they ignore new citation fields.

## 7. Follow-On Work After Phase 1 Stability

### 7.1 Inline Citations

Recommended next step:
- keep the current citation list as the source of truth
- add optional inline markers only after the payload shape and frontend rendering remain stable in production

Suggested approach:
- emit stable citation ids in the grounded response
- let the frontend map inline markers back to the existing citation list instead of duplicating source data
- avoid token-level citation generation in the backend until the answer contract is proven stable

### 7.2 Stronger Hallucination Checks

Recommended next step:
- keep the current rule-based grounding gate as the fast default
- add a second-stage judge only for borderline or high-risk answers, not for every request

Suggested order:
1. improve unsupported-claim detection using stronger answer-to-evidence alignment heuristics
2. add claim-level or sentence-level checking for partial answers
3. evaluate a lightweight LLM-judge path behind a feature flag for only the highest-risk scenarios

### 7.3 Eval Updates

Recommended next step:
- extend evaluation beyond retrieval coverage and section hit
- add grounded-answer checks on the final sync chat output

Suggested order:
1. add checks that `/api/chat/message` returns valid citation metadata when retrieval evidence exists
2. add weak-evidence regression scenarios that assert `partial` or `insufficient_evidence` instead of unsupported confident answers
3. add operator-trace regression checks for `fallbackReason`, `policyVersion`, and citation counts
4. only after those are stable, consider adding a heavier LLM-judge benchmark or offline hallucination eval

## 8. Phase-1 Exit Conclusion

Phase 1 is now the stable baseline:
- citation list rendering is in place
- grounding status is exposed in a backward-compatible shape
- weak-evidence fallback is in place
- operator trace fields are available

The next phase should focus on **finer attribution and stronger evaluation**, not on replacing the current phase-1 contract.
