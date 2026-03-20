# Contract: RAG Grounding And Citation Metadata

## 1. Purpose

Define the additive contract for grounded RAG chat answers and the rules used to derive citations and grounding status, while preserving the current chat API behavior.

## 2. Current Compatibility Baseline

### Current sync chat behavior

- `POST /api/chat/message` currently wraps `chatService.sendMessage(...)` with `ApiResponse.success(response)`.
- `chatService.sendMessage(...)` currently returns a **plain string** for the final assistant content.
- The current frontend already tolerates:
  - raw string response
  - object response with `content`

This means phase 1 should prefer an additive contract that preserves existing string-oriented clients where possible.

### Current retrieval-only behavior

- `POST /api/rag/query` currently returns:
  - `context`
  - `matches`
- It is a retrieval/evidence endpoint, not the final answer contract.
- Phase 1 does **not** turn `/api/rag/query` into the final grounded-answer endpoint.

## 3. Phase-1 Contract Decision

### Primary contract target

Phase 1 targets the **sync chat response path** first.

Preferred response shape for RAG-backed sync chat after implementation:

```json
{
  "content": "...final assistant answer...",
  "citations": [
    {
      "docId": "uuid",
      "documentTitle": "zlAI-v2 接口文档（详细版）",
      "chunkId": 138,
      "sectionHeading": "1.2 认证方式",
      "excerpt": "- 头部：Authorization: Bearer <accessToken> ...",
      "score": 0.91,
      "sourceType": "rag_chunk",
      "order": 1
    }
  ],
  "grounding": {
    "status": "grounded",
    "groundingScore": 0.82,
    "evidenceCount": 4,
    "eligibleCitationCount": 2,
    "fallbackReason": null,
    "policyVersion": "v1"
  }
}
```

### Why `content` instead of `answer`

Use `content` as the primary field because:
- current frontend already reads `response?.content`
- current backend message model is content-oriented
- this minimizes compatibility risk compared with introducing a totally new root field name

## 4. Stream Compatibility Rule

Phase 1 does **not** require a full structured SSE redesign.

Rules:
- ordinary streaming text must continue to work
- if citation metadata is not yet streamable in phase 1, the implementation may defer citation attachment for stream mode or expose it only after the final message is materialized
- stream-mode changes must not break current chunk consumption in `ChatView.vue`

This keeps phase 1 within safe scope.

## 5. Data Shapes

### 5.1 `RagCitation`

```json
{
  "docId": "uuid",
  "documentTitle": "string",
  "chunkId": 138,
  "sectionHeading": "string or null",
  "excerpt": "string",
  "score": 0.91,
  "sourceType": "rag_chunk",
  "order": 1
}
```

### 5.2 `GroundingAssessment`

```json
{
  "status": "grounded | partial | insufficient_evidence",
  "groundingScore": 0.82,
  "evidenceCount": 4,
  "eligibleCitationCount": 2,
  "fallbackReason": null,
  "policyVersion": "v1"
}
```

## 6. Derivation Rules

### Citation Rules

- Citation items MUST be derived from retrieved evidence already available in the request path.
- `documentTitle`, `sectionHeading`, `docId`, and `chunkId` MUST come from retrieval metadata, not from answer-generation text.
- Citation ordering SHOULD follow final evidence relevance or final answer-support priority.
- Citation count MUST be bounded by config.
- Citation excerpts SHOULD be clipped from retrieved chunk content, not regenerated from the final answer.

### Grounding Rules

- `grounding.status = grounded` when evidence passes configured minimum grounding thresholds.
- `grounding.status = partial` when some answer content is supportable but the full request is not sufficiently grounded.
- `grounding.status = insufficient_evidence` when evidence is weak, empty, or below configured minimums.
- `fallbackReason` MUST be populated whenever the status is not `grounded`.
- Grounding metadata MUST describe the answer actually returned to the user, not only the raw retrieval quality.

## 7. Compatibility Rules

- Existing clients that treat the response as plain string must remain operable during migration.
- Existing clients that already read `response?.content` must continue to function.
- Backend contracts should remain additive where possible.
- Frontend rendering should tolerate missing `citations` and `grounding` on legacy messages.
- `/api/rag/query` remains backward compatible in phase 1.

## 8. Non-Goals In Phase 1

- inline token-level citation markers
- full LLM-as-judge hallucination scoring
- full academic faithfulness benchmark integration
- stream-protocol redesign for structured citation events
- cross-session citation persistence model changes unless later required
