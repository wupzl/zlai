# Quickstart: Optimize RAG Grounding With Citations And Hallucination Checks

## Goal

Validate that RAG-backed answers expose citations, avoid unsupported confidence when evidence is weak, and preserve normal chat compatibility.

## Scope Confirmation

### In Scope For Phase 1

- backend chat answer assembly for RAG-backed chat responses
- additive response metadata for citations and grounding status
- frontend rendering for citation display on chat messages
- lightweight rule-based grounding assessment and weak-evidence fallback
- docs and contracts describing the new additive behavior

### Explicitly Out Of Scope For Phase 1

- rewriting `/api/rag/query` into a final-answer endpoint
- replacing the existing retrieval contract with a new citation-first contract
- inline token-level citation markers inside generated answer text
- heavyweight LLM-as-judge hallucination detection
- broad admin-side analytics platform for grounding behavior
- schema-heavy persistence redesign for citation history

### First-Iteration Compatibility Commitment

- ordinary non-RAG chat remains unchanged
- `/api/rag/query` remains a retrieval/evidence endpoint, not the final answer contract
- chat responses keep backward compatibility by adding fields instead of replacing current message semantics
- multi-agent and workflow-based paths may consume the same grounding/citation support later, but the first implementation target is the ordinary RAG-backed chat answer path

## T001 Integration Baseline

### Current backend answer chain

The current RAG-backed answer path is:

1. `ChatServiceImpl.prepareSyncMessage(...)` / `prepareStreamChat(...)` / `prepareRegenerateStream(...)`
2. `ChatContextService.resolveRagContext(...)`
3. `RagService.buildContext(...)`
4. `ChatPromptService.mergeSystemPrompt(...)`
5. `ChatContextService.buildContextMessages(...)`
6. final answer generation through one of:
   - ordinary `adapter.chat(...)`
   - `agentWorkflowService.executeSync(...)`
   - `multiAgentOrchestrator.run(...)` / `runTeam(...)`

### Confirmed module boundaries

- Retrieval boundary:
  - `ChatContextService.resolveRagContext(...)` only decides whether RAG is enabled and delegates to `ragService.buildContext(...)`.
- RAG retrieval + context-building boundary:
  - `RagServiceImpl.buildContext(...)` handles document-aware routing, section-aware routing, hybrid retrieval, keyword fallback, and final context string construction.
- Prompt assembly boundary:
  - `ChatPromptService.mergeSystemPrompt(...)` injects retrieved `ragContext` into the system prompt as untrusted knowledge-base context.
- Final answer generation boundary:
  - `ChatServiceImpl` still obtains the final user-visible answer from the ordinary chat/agent path, not from `/api/rag/query` directly.

### Important current-system conclusion

Current system behavior is **not**:
- retrieval returns final grounded answer object with citations

Current system behavior **is**:
- retrieval returns a context string
- that context is merged into the system prompt
- the final answer is then generated as plain assistant text by the normal chat, workflow, or multi-agent path

This means phase-1 citation and grounding work should attach to the **chat answer assembly path**, while still reusing retrieval evidence already available in the RAG path.

### Current compatibility boundary

- `/api/rag/query` currently returns `context + matches` only.
- RAG-backed chat responses currently do not carry structured citation metadata.
- Non-RAG chat must remain unaffected.
- Multi-agent and workflow-based chat paths must remain compatible with any additive answer metadata.

## T002 Local Verification Commands

### Backend compile

```powershell
mvn -q -DskipTests=true compile
```

### Targeted backend tests

Phase 1 should prefer targeted JUnit runs around:
- chat sync path
- chat controller path
- RAG service or answer-assembly support code
- any new grounding/citation service added by this feature

Example command shape:

```powershell
mvn -q "-Dtest=ChatControllerTest,ChatServiceImplTest" test
```

If new dedicated tests are added later, append them here.

### Frontend build

```powershell
cmd /c "npm run build"
```

Run in:
- `frontend/`

### Frontend tests

```powershell
cmd /c "npm test"
```

Run in:
- `frontend/`

### Optional local backend run

```powershell
mvn -q -DskipTests=true spring-boot:run
```

Run in:
- `backend/`

## T002 Manual Verification Targets

### Primary API entry points

- `POST /api/chat/message`
- `POST /api/chat/stream`
- optional retrieval-only cross-check: `POST /api/rag/query`

### Manual scenario set

1. Strong-evidence RAG question
   - Use a query that clearly maps to an ingested document or known section.
   - Expect additive citation metadata in the final chat response after phase-1 implementation.

2. Multi-source RAG question
   - Use a query that reasonably spans two sections or two high-confidence matches.
   - Expect more than one citation item or at least evidence derived from multiple retrieved matches.

3. Weak-evidence RAG question
   - Use a query with weak, empty, or low-confidence support in the knowledge base.
   - Expect uncertainty, partial answer, or insufficient-evidence fallback instead of unsupported certainty.

4. Non-RAG ordinary chat
   - Send a normal chat question with `useRag = false` or in a non-RAG context.
   - Expect no citation metadata side effect and no rendering regression.

5. Retrieval-only cross-check
   - Call `/api/rag/query` with the same question.
   - Use `context + matches` only as evidence inspection, not as the final-answer contract.

## Verification Flow

1. Compile backend and ensure no contract break.
2. Run targeted backend tests for the touched answer path.
3. Build frontend and run frontend tests if UI rendering changes are introduced.
4. Ask a RAG-backed question with a clearly matching document and verify that the final answer includes citation metadata.
5. Ask a question whose answer draws from multiple retrieved sections and verify that multiple citation items are returned.
6. Ask a weak-evidence question and verify that the system returns uncertainty or insufficient-evidence fallback instead of an overconfident unsupported answer.
7. Ask a normal non-RAG chat question and verify that ordinary chat behavior is unchanged.
8. Verify that the frontend can render citation items without breaking markdown display.
9. Verify that logs or response metadata expose grounding status and fallback reason.

## Verification Matrix

| Scenario | Entry Point | Expected Boundary | Expected Observability |
|----------|-------------|-------------------|------------------------|
| Strongly grounded RAG answer | chat path with RAG enabled | retrieval -> answer assembly -> citations attached -> answer returned | citation list present; grounding status = grounded |
| Multi-source answer | chat path with RAG enabled | multiple retrieved matches contribute to citation payload | citation items show more than one source or section |
| Weak-evidence query | chat path with RAG enabled | answer path downgrades to partial answer or insufficient-evidence fallback | fallback reason and grounding status visible |
| Non-RAG ordinary chat | ordinary chat path | no citation logic injected into unrelated path | no unintended citation metadata side effects |
| Frontend rendering | chat view / message component | citations rendered as list or panel without breaking message markdown | user can see source title and excerpt |
| Retrieval-only cross-check | `/api/rag/query` | evidence inspection only, not final answer contract | returned `context + matches` remain inspectable |

## Manual Review Questions

- Are citations clearly derived from retrieved documents rather than invented by the model?
- Can a user tell which document or section the answer came from?
- When evidence is weak, does the answer become more conservative instead of more fluent?
- Are thresholds and fallback decisions observable enough to tune later?
- Did any non-RAG chat response shape or rendering regress?

## T008 Backend Coverage Added

Targeted backend contract coverage added for phase-1 citation work:

- `GroundedChatPayloadContractTest`
  - verifies sync chat API payload can carry additive `content + citations + grounding` fields without replacing the existing `ApiResponse` envelope
- `RagQueryResponseContractTest`
  - verifies retrieval evidence payload preserves `docId`, `content`, `score`, and `chunkMetadata`, which are the minimum fields needed for deterministic citation derivation later

Current targeted command:

```powershell
mvn -q "-Dtest=ChatControllerTest,GroundedChatPayloadContractTest,RagQueryResponseContractTest" test
```

## T009 Frontend Verification Added

Targeted frontend verification added for phase-1 citation-compatible rendering:

- `ChatView.test.js`
  - verifies the sync chat path can consume an additive grounded payload object and still extract `content` correctly
- `ChatMessageList.test.js`
  - verifies an assistant message carrying `citations` and `grounding` metadata still renders markdown content without UI regression

Current targeted command:

```powershell
cmd /c "npm test -- src/views/ChatView.test.js src/components/chat/ChatMessageList.test.js"
```

## T010 Citation Derivation Support Added

Backend answer-assembly path now carries RAG evidence for the sync chat path and derives citation candidates before final response return:

- `ChatContextService.resolveRagEvidence(...)`
  - resolves a context + match bundle without moving retrieval logic out of the RAG boundary
- `PreparedSyncMessage.ragEvidence`
  - carries retrieval evidence through the sync answer-assembly path
- `RagCitationService`
  - derives deterministic citation candidates from retrieved matches, chunk metadata, and lexical overlap with the final answer
- `ChatServiceImpl.finalizeSyncResponse(...)`
  - now derives citation candidates in the sync answer path so `T011` can expose them without redesigning the pipeline

Current targeted backend commands:

```powershell
mvn -q "-Dtest=RagCitationServiceTest,GroundedChatPayloadContractTest,RagQueryResponseContractTest" test
mvn -q -DskipTests=true compile
```

## T011 Citation Payload Exposure Added

The sync chat response path now exposes additive citation metadata when citations are available:

- `GroundedChatResponse`
  - response DTO with `content + citations` and a reserved `grounding` slot for the next phase
- `ChatServiceImpl.finalizeSyncResponse(...)`
  - returns a `GroundedChatResponse` when citation candidates exist, otherwise preserves the legacy plain-string response path
- sync idempotency replay
  - structured grounded responses are serialized into the idempotency store and decoded back into `GroundedChatResponse` on replay, avoiding first-response vs replay shape drift

Current targeted backend commands:

```powershell
mvn -q "-Dtest=GroundedChatPayloadContractTest,RagCitationServiceTest,RagQueryResponseContractTest" test
mvn -q -DskipTests=true compile
```

## T012 Citation UI Rendering Added

The chat UI now renders additive citation metadata without disturbing legacy assistant markdown rendering:

- `ChatView.sendMessageSync(...)`
  - attaches returned `citations` and `grounding` metadata back onto the latest assistant message after the sync response and session refresh
- `ChatMessageList.vue`
  - renders a lightweight grounding chip and a `Sources` panel only when citation metadata exists
- `style.css`
  - adds isolated citation/grounding styles without changing the base markdown renderer

Current targeted frontend commands:

```powershell
cmd /c "npm test -- src/views/ChatView.test.js src/components/chat/ChatMessageList.test.js"
cmd /c "npm run build"
```

## T013 Citation Diagnostics Added

Backend observability now exposes which retrieved evidence became citations for the sync RAG answer path:

- `RagCitationDiagnostics`
  - summarizes evidence count, selected citation count, selected doc ids, selected titles, and citation scores
- `RagCitationService.buildDiagnostics(...)`
  - builds a stable diagnostics payload from resolved RAG evidence and the selected citations
- `ChatServiceImpl.finalizeSyncResponse(...)`
  - emits a structured log line for RAG-backed sync answers with selected evidence identifiers and citation scores

Current targeted backend commands:

```powershell
mvn -q "-Dtest=RagCitationServiceTest,GroundedChatPayloadContractTest,RagQueryResponseContractTest" test
mvn -q -DskipTests=true compile
```

## T014 Weak-Evidence Coverage Added

Backend contract coverage now fixes the weak-evidence and partial-grounding response shapes before policy logic is implemented:

- `GroundingAssessment`
  - strongly typed model for `grounded / partial / insufficient_evidence` states
- `GroundedChatPayloadContractTest`
  - verifies three sync-chat grounding contract shapes:
    - grounded response
    - partial-grounding response with fallback reason
    - insufficient-evidence fallback response with zero eligible citations

Current targeted backend commands:

```powershell
mvn -q "-Dtest=GroundedChatPayloadContractTest,RagCitationServiceTest,RagQueryResponseContractTest" test
mvn -q -DskipTests=true compile
```

## T015 Rule-Based Grounding Assessment Added

Backend support code now contains a standalone rule-based grounding assessor, ready for later integration into the final answer path:

- `GroundingAssessmentService`
  - evaluates `answer + ragEvidence + citations` and returns one of:
    - `grounded`
    - `partial`
    - `insufficient_evidence`
- current rule inputs:
  - retrieved evidence count
  - eligible citation count
  - lexical overlap between answer text and evidence/citation text
- current fallback reasons:
  - `no_retrieved_evidence`
  - `no_eligible_citations`
  - `partial_answer_only`
  - `weak_evidence_overlap`

Current targeted backend commands:

```powershell
mvn -q "-Dtest=GroundingAssessmentServiceTest,GroundedChatPayloadContractTest,RagCitationServiceTest" test
mvn -q -DskipTests=true compile
```

## T016 Grounding Assessment Integrated Into Sync Answer Path

The sync RAG answer path now computes grounding before returning the user-visible response:

- `ChatServiceImpl.finalizeSyncResponse(...)`
  - derives citations
  - computes `GroundingAssessment`
  - returns a structured `GroundedChatResponse` whenever grounding metadata is present
- non-RAG or non-grounded legacy paths
  - still keep the plain-string response path when neither citations nor grounding metadata applies
- current phase boundary
  - grounding is now attached to the final sync response
  - answer degradation and weak-evidence content fallback are still deferred to `T017`

Current targeted backend commands:

```powershell
mvn -q "-Dtest=GroundingAssessmentServiceTest,GroundedChatPayloadContractTest,RagCitationServiceTest,RagQueryResponseContractTest" test
mvn -q -DskipTests=true compile
```

## T017 Safe Weak-Evidence Fallback Added

The sync RAG answer path now degrades user-visible content when grounding is weak:

- `GroundingFallbackService`
  - `grounded` => keep the answer unchanged
  - `partial` => prepend a limited-evidence disclaimer and keep the partial answer
  - `insufficient_evidence` => replace the answer with the safe no-context message
- `ChatServiceImpl.finalizeSyncResponse(...)`
  - now persists and returns the fallback-adjusted content, not the raw unsupported answer
  - suppresses citations in the user-visible response when status is `insufficient_evidence`

Current targeted backend commands:

```powershell
mvn -q "-Dtest=GroundingFallbackServiceTest,GroundingAssessmentServiceTest,GroundedChatPayloadContractTest,RagCitationServiceTest,RagQueryResponseContractTest" test
mvn -q -DskipTests=true compile
```

## T018 Grounding Thresholds And Fallback Behavior Configured

Grounding thresholds and fallback behavior are now externalized under `app.rag.grounding`:

- `enabled`
- `min-grounded-score`
- `min-partial-score`
- `min-citation-count`
- `allow-partial-answer`
- `show-grounding-hint`
- `policy-version`
- `insufficient-evidence-message`
- `partial-answer-prefix`

Current implementation details:

- `GroundingAssessmentService`
  - reads thresholds and policy version from `RagProperties`
- `GroundingFallbackService`
  - reads partial-answer hint visibility and fallback messages from `RagProperties`
- `application.yaml`
  - now exposes the default phase-1 grounding policy explicitly for tuning

Current targeted backend commands:

```powershell
mvn -q "-Dtest=GroundingFallbackServiceTest,GroundingAssessmentServiceTest,GroundedChatPayloadContractTest,RagCitationServiceTest,RagQueryResponseContractTest" test
mvn -q -DskipTests=true compile
```

## T019 Diagnostics Visibility Verification Added

Targeted contract verification now confirms that grounded and downgraded sync-chat payloads expose the tuning-critical fields operators need without changing the response envelope:

- `GroundedChatPayloadContractTest`
  - grounded payload now verifies visible citation source fields (`docId`, `sourcePath`, `headings`) and grounding trace fields (`status`, `groundingScore`, `evidenceCount`, `eligibleCitationCount`, `policyVersion`)
  - partial payload verifies downgraded-answer visibility for `status`, `groundingScore`, `evidenceCount`, `eligibleCitationCount`, `fallbackReason`, and `policyVersion`
  - insufficient-evidence payload verifies safe fallback visibility for `status`, `groundingScore`, `evidenceCount`, `eligibleCitationCount`, `fallbackReason`, and the downgraded content body

Current targeted backend commands:

```powershell
mvn -q "-Dtest=GroundedChatPayloadContractTest,GroundingFallbackServiceTest,GroundingAssessmentServiceTest,RagCitationServiceTest,RagQueryResponseContractTest" test
mvn -q -DskipTests=true compile
```

## T020 Backward-Compatible Grounding Exposure Confirmed

The sync chat contract now exposes additive `grounding` metadata without breaking legacy clients:

- `ChatServiceImpl.buildSyncResponsePayload(...)`
  - still returns a plain string when neither citations nor grounding metadata applies
  - returns `GroundedChatResponse` only when additive metadata is present
- `ChatController.sendMessage(...)`
  - continues to wrap either legacy string responses or structured grounded responses in the existing `ApiResponse<Object>` envelope
- `ChatView.sendMessageSync(...)`
  - continues to support both response shapes:
    - legacy plain string
    - additive object with `content + citations + grounding`

Verification added:

- `ChatControllerTest`
  - verifies `/api/chat/message` can return a structured grounded payload inside the existing response envelope
- `ChatView.test.js`
  - verifies legacy plain-string sync responses still work while grounded-object responses keep exposing additive metadata

Current targeted verification commands:

```powershell
mvn -q "-Dtest=ChatControllerTest,GroundedChatPayloadContractTest" test
mvn -q -DskipTests=true compile
cmd /c "npm test -- src/views/ChatView.test.js src/components/chat/ChatMessageList.test.js"
cmd /c "npm run build"
```

## T021 Operator Trace And Diagnostics Added

Operator-facing grounding diagnostics are now normalized into a dedicated trace object before logging:

- `GroundingTraceDiagnostics`
  - standardizes the operator-visible trace fields for one sync answer:
    - `chatId`
    - `assistantMessageId`
    - `query`
    - `status`
    - `groundingScore`
    - `fallbackReason`
    - `policyVersion`
    - `downgraded`
    - `evidenceCount`
    - `eligibleCitationCount`
    - `selectedCitationCount`
    - `selectedDocIds`
    - `selectedTitles`
    - `citationScores`
- `GroundingTraceService`
  - assembles operator diagnostics from `GroundingAssessment` and `RagCitationDiagnostics` without leaking UI concerns into retrieval code
- `ChatServiceImpl.finalizeSyncResponse(...)`
  - now logs the normalized grounding trace instead of a partially assembled ad-hoc field set
  - current structured log includes grounding decision, downgrade reason, policy version, citation selection summary, and the triggering user query

Verification added:

- `GroundingTraceServiceTest`
  - verifies both grounded and insufficient-evidence traces expose the expected operator fields

Current targeted backend commands:

```powershell
mvn -q "-Dtest=GroundingTraceServiceTest,GroundingFallbackServiceTest,GroundingAssessmentServiceTest,RagCitationServiceTest,GroundedChatPayloadContractTest,ChatControllerTest" test
mvn -q -DskipTests=true compile
```

## T022 RAG Docs Updated

The current-truth docs under `docs/` now describe the grounded-answer phase-1 behavior consistently:

- `docs/api-interface-spec.md`
  - documents the backward-compatible sync chat response shape:
    - legacy plain string
    - additive grounded object with `content + citations + grounding`
  - clarifies that `/api/rag/query` remains an evidence-inspection endpoint
- `docs/api-rag-agent-doc.md`
  - documents citations, grounding assessment, weak-evidence fallback, and the current compatibility boundary
- `docs/rag-agent-implementation.md`
  - documents the actual answer-assembly path and operator-oriented grounding trace objects
- `docs/README.md`
  - updates the ¡°current truth¡± guide so grounded-answer docs are easier to find

This task updates docs only. No additional runtime behavior was changed here.

## T023 Rollout Safety Review Added

Configuration defaults and rollout-safety notes are now explicitly reviewed for phase 1:

### Current grounding defaults

- `app.rag.grounding.enabled = true`
- `app.rag.grounding.min-grounded-score = 0.70`
- `app.rag.grounding.min-partial-score = 0.30`
- `app.rag.grounding.min-citation-count = 1`
- `app.rag.grounding.max-citation-count = 3`
- `app.rag.grounding.allow-partial-answer = true`
- `app.rag.grounding.show-grounding-hint = true`
- `app.rag.grounding.policy-version = v1`

### Rollout safety notes

- keep `max-citation-count` small in phase 1 to avoid noisy citation panels and oversized sync payloads
- tune `min-grounded-score` and `min-partial-score` conservatively before relaxing them; false-grounded answers are worse than conservative fallback in the first rollout
- keep `policy-version` updated when thresholds or fallback wording materially change so operator traces remain interpretable
- do not treat `/api/rag/query` pass/fail as the only rollout gate; verify final sync chat behavior because fallback and citation derivation happen after retrieval
- if partial answers become too frequent in production, first inspect `eligibleCitationCount` and `selectedCitationCount` in operator traces before lowering thresholds

### Implementation change in this task

- `RagCitationService` no longer hardcodes the citation cap
- citation output is now bounded by `app.rag.grounding.max-citation-count`
- `RagCitationServiceTest` verifies the configured citation cap is respected

Current targeted backend commands:

```powershell
mvn -q "-Dtest=RagCitationServiceTest,GroundingAssessmentServiceTest,GroundingFallbackServiceTest,GroundedChatPayloadContractTest,GroundingTraceServiceTest" test
mvn -q -DskipTests=true compile
```

## T024 Targeted Regression Checks Completed

Targeted regression checks for the impacted phase-1 chat and RAG chains were run successfully.

### Backend regression set

Executed:

```powershell
mvn -q "-Dtest=ChatControllerTest,GroundedChatPayloadContractTest,RagQueryResponseContractTest,RagCitationServiceTest,GroundingAssessmentServiceTest,GroundingFallbackServiceTest,GroundingTraceServiceTest" test
mvn -q -DskipTests=true compile
```

Covered areas:

- sync chat controller compatibility
- grounded payload contract
- retrieval evidence contract
- citation derivation
- rule-based grounding assessment
- weak-evidence fallback
- operator grounding trace assembly

### Frontend regression set

Executed:

```powershell
cmd /c "npm test -- src/views/ChatView.test.js src/components/chat/ChatMessageList.test.js"
cmd /c "npm run build"
```

Covered areas:

- sync chat compatibility for legacy string responses
- sync chat compatibility for additive grounded object responses
- citation panel rendering
- grounding chip rendering
- production build safety

### Current regression conclusion

- the phase-1 grounded sync chat path remains backward compatible
- the frontend still accepts both legacy and additive sync payload shapes
- retrieval-only `/api/rag/query` contract remains stable
- no additional regression was observed in the targeted phase-1 scope

## T025 Follow-On Work Reviewed

Phase-1 follow-on work is now explicitly reviewed and ordered for the next increment.

### Recommended next-step backlog

1. Inline citations
- keep the current side-panel citation list as the source of truth
- add inline markers only after the current additive payload shape proves stable
- prefer frontend-side marker rendering backed by stable citation ids instead of duplicating source payloads

2. Stronger hallucination checks
- keep the current rule-based grounding gate as the default fast path
- improve answer-to-evidence alignment heuristics before introducing a second model
- if an LLM judge is introduced later, keep it behind a feature flag and reserve it for borderline or high-risk answers

3. Eval updates
- add final-answer eval coverage for `/api/chat/message`, not just `/api/rag/query`
- add regression cases for `partial` and `insufficient_evidence`
- add checks for operator trace fields such as `fallbackReason`, `policyVersion`, and citation counts

### Phase-1 closeout conclusion

Phase 1 should now be treated as the stable baseline for grounded sync chat. The next phase should refine attribution quality and evaluation depth instead of replacing the current response contract.
