# Implementation Plan: Optimize RAG Grounding With Citations And Hallucination Checks

**Branch**: `[004-rag-grounding-citations]` | **Date**: 2026-03-19 | **Spec**: [spec.md](D:/Code/zlAI-v2/specs/004-rag-grounding-citations/spec.md)  
**Input**: Feature specification from `/specs/004-rag-grounding-citations/spec.md`

## Summary

Add first-phase grounded-answer support to the existing RAG chat path by attaching citation metadata to RAG-backed answers, introducing a lightweight grounding assessment before final answer return, and degrading safely when retrieved evidence is weak. The first iteration stays rule-based and evidence-derived instead of introducing a heavyweight LLM judge.

## Technical Context

**Language/Version**: Java 21, Spring Boot 3.5, Vue 3, TypeScript, Markdown specs  
**Primary Dependencies**: Existing chat modules, RAG services, Jackson, Vue Router, Vite, existing admin/docs flow  
**Storage**: PostgreSQL + pgvector for RAG data, existing chat persistence, optional config only for first iteration  
**Testing**: JUnit, Vitest where needed, targeted manual verification, existing RAG eval scripts for regression context  
**Target Platform**: Web chat UI, backend chat/RAG pipeline, operator diagnostics  
**Project Type**: mixed  
**Performance Goals**: Keep first-iteration grounding checks lightweight enough that answer latency does not materially regress for ordinary RAG requests  
**Constraints**: Preserve existing chat API compatibility, preserve RAG ingest/query contracts where practical, do not fabricate citations, keep logic observable and configurable  
**Scale/Scope**: RAG-backed chat answers first; no full academic hallucination-evaluation platform in this iteration

## Constitution Check

*GATE: Must pass before research and design start. Re-check after design is complete.*

- **Core User Chain Stability**: The protected chain is RAG-backed chat answer generation. The change must not break normal chat, streaming behavior, retrieval-only APIs, or frontend rendering for messages without citations.
- **Module Boundary Integrity**: Retrieval stays in RAG services, grounding policy in service/support, API exposure in DTO/controller layers, and citation rendering in frontend view/components. Do not leak frontend formatting rules into backend retrieval logic.
- **Skill Contract First**: Not applicable for this feature.
- **Observability & Governance**: Grounding status, citation list, evidence identifiers, and downgrade reasons must be traceable in response metadata and/or logs. Thresholds must be config-driven.
- **Risk-Aligned Testing**: Add backend coverage for grounded answer assembly and weak-grounding fallback. Add frontend coverage or manual verification for citation rendering. Validate non-RAG path regression explicitly.

## Project Structure

### Documentation (this feature)

```text
specs/004-rag-grounding-citations/
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   └── rag-grounding-contract.md
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

**Structure Decision**: This feature touches backend chat + RAG answer assembly, shared DTO/VO contracts, frontend chat rendering, and docs. It should not require a new standalone service in the first iteration.

## Skill Architecture *(mandatory when skill-layer is involved)*

Not applicable.

## Research & Design Outputs

### research.md

- Compare rule-based grounding vs LLM-judge-based hallucination detection
- Decide whether citations should be inline markers, side list, or both in phase 1
- Decide whether grounding status should be exposed to users directly, only via UI hint, or both

### data-model.md

- Define citation item shape
- Define grounding assessment shape
- Define final payload shape and compatibility expectations
- Define config surface for thresholds and fallback behavior

### contracts/

- Document API response additions for grounded answers
- Document grounding metadata and citation derivation rules
- Document compatibility rules for existing clients

### quickstart.md

- Local setup
- Minimal grounded-answer verification flow
- Weak-evidence fallback verification
- Frontend citation rendering verification

## Complexity Tracking

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| None currently expected | N/A | N/A |
