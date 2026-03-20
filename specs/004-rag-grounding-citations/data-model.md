# Data Model: Optimize RAG Grounding With Citations And Hallucination Checks

## 1. RagCitation

Represents one user-visible citation derived from a retrieved match.

### Core Fields

- `docId`
- `documentTitle`
- `chunkId`
- `sectionHeading`
- `excerpt`
- `score`
- `sourceType`
- `order`

### Notes

- `documentTitle` and `sectionHeading` must come from retrieved evidence metadata, not generated text.
- `excerpt` should be clipped from retrieved chunk content and be safe for UI display.
- `score` should reflect retrieval or post-ranking confidence already available in the current path, not a fabricated value.

## 2. GroundingAssessment

Represents the system's internal judgment of how grounded the returned answer is.

### Core Fields

- `status`
  - `grounded`
  - `partial`
  - `insufficient_evidence`
- `groundingScore`
- `evidenceCount`
- `eligibleCitationCount`
- `fallbackReason`
- `policyVersion`

### Notes

- `groundingScore` is allowed to be rule-based in phase 1.
- `fallbackReason` is required whenever `status != grounded`.
- This object describes the answer that is actually returned to the user, not only the raw retrieval quality.

## 3. GroundedChatResponse

Represents the preferred additive sync-chat response shape for RAG-backed answers.

### Core Fields

- `content`
- `citations`
- `grounding`

### Compatibility Notes

- This model is intentionally `content`-based, not `answer`-based, to align with the current frontend and backend message model.
- Current clients may still receive plain string content during migration.
- New clients should tolerate missing `citations` and `grounding` on legacy messages.

## 4. GroundingPolicyConfig

Represents configurable threshold and fallback controls.

### Recommended Placement

- extend current `app.rag` configuration through a nested grounding section under `RagProperties`

### Core Fields

- `enabled`
- `minGroundingScore`
- `minCitationCount`
- `maxCitationCount`
  - current default: `3`
- `allowPartialAnswer`
- `showGroundingHint`
- `attachDiagnostics`

### Notes

- Config should live with current RAG configuration instead of creating an unrelated top-level module.
- Thresholds must be externally configurable, not hidden in service code.

## 5. CitationDerivationContext

Represents the internal input used to build citations from retrieved evidence already present in the answer path.

### Core Fields

- `query`
- `ragContext`
- `matches`
- `normalizedEvidenceText`
- `candidateHeadings`

### Notes

- This object should be created inside the chat answer-assembly path after retrieval has already happened.
- It should reuse current `context + matches` evidence instead of triggering a second independent retrieval contract redesign.

## 6. Current-System Integration Anchors

### Existing runtime objects to align with

- `RagChunkMatch`
  - current retrieval evidence model already returned by `/api/rag/query`
- `RagQueryResponse`
  - current retrieval-only response shape: `context + matches`
- `PreparedSyncMessage`
  - current sync chat orchestration object before final answer return
- `WorkflowExecutionResult`
  - current workflow answer wrapper, still content-oriented
- `RagProperties`
  - existing configuration root for RAG thresholds and search behavior
- `Message`
  - current persisted chat message entity used by chat-history replay

### Important modeling conclusion

Phase 1 should adapt to existing runtime anchors rather than replace them:
- do not replace `RagQueryResponse`
- do not redesign `PreparedSyncMessage`
- do not introduce a brand-new retrieval service boundary
- add grounding-aware response metadata around the final answer path

## 7. DTO / VO / Payload Carriers For Phase 1

### 7.1 Sync chat response carrier

Current path:
- `ChatController.sendMessage(...) -> ApiResponse.success(response)`
- `ChatServiceImpl.finalizeSyncResponse(...)` currently returns a plain string

Phase-1 recommendation:
- this is the **primary additive carrier** for `citations` and `grounding`
- migrate from raw string to `string | object-with-content`
- preferred object shape:
  - `content`
  - `citations`
  - `grounding`

Why this carrier first:
- frontend already tolerates object responses with `content`
- no need to redesign retrieval-only API
- smallest change that is user-visible and backward-aware

### 7.2 Chat history payload carrier

Current path:
- `GET /api/chat/{chatId}` returns a map-like payload with:
  - `chatId`
  - `currentMessageId`
  - `messages`
  - etc.
- `messages` are currently built from persisted `Message` entities

Phase-1 recommendation:
- do **not** make history replay the primary carrier for the first implementation
- if citation metadata must survive page refresh in phase 1, use an additive transient wrapper or additive fields only after the sync path is stable
- avoid forcing immediate database-schema changes just to satisfy the first increment

### 7.3 Frontend message object carrier

Current path:
- `ChatView.vue` stores messages as plain JavaScript objects
- `ChatMessageList.vue` renders `msg.content` and ignores unknown extra fields

Phase-1 recommendation:
- frontend message objects can safely carry additive fields such as:
  - `citations`
  - `grounding`
- this is a low-risk UI integration point because the current components are not strict typed DTO consumers

### 7.4 Stream payload carrier

Current path:
- `/api/chat/stream` emits plain text chunks via SSE
- `ChatView.vue` appends chunks directly into `msg.content`

Phase-1 recommendation:
- do **not** choose stream chunks as the primary citation carrier in phase 1
- if stream mode must support citations later, attach them after final message materialization or as a separate additive terminal event in a later task
- do not break current chunk consumer behavior

### 7.5 Retrieval-only payload carrier

Current path:
- `/api/rag/query` returns `RagQueryResponse(context, matches)`

Phase-1 recommendation:
- keep this endpoint as evidence inspection only
- do not use it as the user-facing grounded-answer DTO
- allow it to remain the source of derivation data for citations and grounding logic

## 8. Observability Anchors For Phase 1

### 8.1 RAG retrieval trace

Current anchor:
- `RagServiceImpl` already logs retrieval path details such as:
  - document-aware hit/miss
  - section-aware hit/miss
  - keyword fallback
  - top matches

Phase-1 recommendation:
- grounding-related evidence summaries should attach here or immediately after this layer
- log enough to correlate:
  - query
  - selected evidence count
  - candidate citation count
  - whether retrieval evidence was strong enough for answer grounding

### 8.2 Final sync-answer trace

Current anchor:
- `ChatServiceImpl.finalizeSyncResponse(...)` is the final sync answer choke point

Phase-1 recommendation:
- this is the primary place to log:
  - grounding status
  - grounding score
  - fallback reason
  - citation count
- this is also the safest place to attach additive response metadata before returning to the client

### 8.3 Workflow / agent trace

Current anchors:
- `AgentWorkflowServiceImpl.recordWorkflowProgress(...)`
- `MultiAgentOrchestrator.recordWorkflowProgress(...)`

Phase-1 recommendation:
- do not make these the first implementation target
- if workflow-based RAG answers adopt grounding later, reuse the same grounding fields rather than inventing parallel tracing vocabularies

### 8.4 History / persistence trace

Current anchors:
- `Message` persistence via `ChatWorkflowSupport.markAssistantSucceeded(...)`
- idempotency replay via stored response content

Phase-1 recommendation:
- phase 1 should avoid making DB persistence the mandatory trace carrier
- response/log trace should come first
- persistence can be considered later once additive response metadata is stable

### 8.5 Minimum phase-1 diagnostic fields

The following should be observable in logs, response metadata, or both:
- `grounding.status`
- `grounding.groundingScore`
- `grounding.fallbackReason`
- citation count
- evidence count
- top cited document titles or identifiers
- policy version

## 9. Relationships

- `RagCitation` is derived from retrieved `RagChunkMatch` evidence plus match metadata.
- `GroundingAssessment` is derived from the final answer text, current retrieval evidence, and `GroundingPolicyConfig`.
- `GroundedChatResponse` contains the final assistant `content` plus `RagCitation` and `GroundingAssessment`.
- `GroundingPolicyConfig` should be read by the answer-assembly layer through `RagProperties`.
- `CitationDerivationContext` is the bridge between the current retrieval output and the final grounded chat response.
- The preferred phase-1 additive carrier is the sync chat response object, not persisted `Message`, not SSE chunks, and not `/api/rag/query`.
- The preferred phase-1 observability anchor is `ChatServiceImpl.finalizeSyncResponse(...)`, with retrieval-side support from `RagServiceImpl` logs.


