# RAG Follow-up Q&A (Code-Accurate, No Handwaving)

This answers your follow-up questions against current repository behavior.

## 1) `chunkSize=800` is character-based. How do we guarantee prompt context limit?

Short answer: we do **not guarantee strict model context-window safety today**.

What exists now:

- Chunking is char-window split (`chunkSize=800`, `overlap=100`), not token-aware.
- RAG context assembly uses char cap in `buildContextFromMatches(...)`:
  - `maxChars = max(2000, chunkSize * 6)` (default ~= 4800 chars).
- Chat history is windowed by message count (`trimContext(...)`), not prompt-token budget.
- Output side has completion cap (`resolveMaxCompletionTokens(...)`), but input prompt hard cap is not enforced.

So current behavior is "soft control by chars + history window", not exact token budget packing.

---

## 2) You said history has sliding window. How are RAG chunks trimmed?

Today RAG chunk trimming is by **character length** and dedup:

- top matches -> `buildContextFromMatches(...)`
- deduplicate same chunk text
- append until `maxChars` then cut

No token-aware rank-and-pack for retrieved chunks is implemented yet.

So yes, this part is currently missing if your requirement is strict token-budgeted RAG packing.

---

## 3) How is MMR lambda (0.7) used? State the formula in words.

In `RagServiceImpl.mmrSearch(...)`:

- Query relevance term:
  - from vector distance mapped to `querySim = 1 / (1 + distance)`
- Diversity penalty term:
  - `maxSimToSelected` = max cosine similarity between current candidate and already selected chunks

MMR score is computed as:

- `lambda * querySim - (1 - lambda) * maxSimToSelected`

With lambda = 0.7:

- 70% weight on "relevance to user query"
- 30% weight on "penalty for being too similar to already chosen chunks"

---

## 4) Do you compute doc-doc pairwise every time? 20 candidates means 20x20 scale.

Current implementation is iterative greedy MMR:

- candidate set default is `topK * mmrCandidateMultiplier` = `5 * 4 = 20`
- each selection step scans remaining candidates and computes cosine to selected set
- complexity is roughly O(topK * candidateCount * selectedCount), not full precomputed dense matrix

With default 20/5 this is small and acceptable.

No explicit caching or matrix precompute optimization is implemented.

---

## 5) MMR runs on vector candidates or keyword results?

MMR runs on **vector candidates only**:

- SQL `searchCandidates(...)` returns `(doc_id, content, embedding, distance)`
- then Java MMR selects final topK

Keyword fallback path (`searchChunksByKeyword`, `searchByKeyword`) is separate and does not run MMR.

---

## 6) "keyword extraction -> embedding" ambiguity: what is actually embedded?

Current code embeds the **original query string**:

- `embeddingService.embed(query)` in `search(...)`

Keyword extraction is used for:

- hit-check sanity (`hasKeywordHit`)
- fallback retrieval when vector result is weak/mismatched

We do **not** replace query embedding input with "concatenated keywords".

Reason in current code: preserve original semantics for vector retrieval; keywords are auxiliary guardrail/fallback only.

---

## 7) Why put RAG context into `system` prompt, not user/tool message?

Current design choice in `mergeSystemPrompt(...)`:

- prepend as "Knowledge base context" + instruction policy in system role

Why this was chosen:

- system role has highest policy priority in most chat models
- keeps "use KB context / if unknown say don't know" as hard instruction

Tradeoff:

- mixing evidence and policy in system role increases prompt-injection risk if evidence is untrusted
- stricter design would separate:
  - system: policy only
  - tool/user: evidence payload only

So current design is pragmatic but not strongest isolation model.

---

## 8) Citation format (doc_id/chunk_id/page) implemented?

Current answer generation does not enforce structured citations.

What we have:

- retrieval returns `doc_id` + `content` in service layer
- final merged context is plain text

What is missing:

- no mandatory citation schema in final answer
- no chunk_id/page number grounding in prompt output contract

If you want evidence-traceable output, we need to change context format to include source tags and add output constraints/checks.

---

## 9) Prompt injection inside retrieved docs: how defended?

Current defense is limited.

What exists:

- system instruction says "use KB context to answer; if absent say don't know"
- historical system messages are filtered and cannot override session system prompt

What is missing:

- no explicit sanitization/neutralization of malicious instructions inside retrieved content
- no parser-level policy like "treat retrieved text as untrusted data, never executable instruction"
- no citation-verified answer post-check

So prompt-injection risk from corpus text is currently not fully mitigated.

---

## 10) Clear "implemented vs not implemented" summary

Implemented:

- char-based chunk split with overlap
- per-chunk embedding at ingest
- query embedding at retrieval
- pgvector distance search
- in-memory MMR over vector candidates
- keyword fallback retrieval
- history sliding window
- char-based RAG context truncation

Not implemented yet:

- token-aware chunking
- token-budgeted retrieval packing into prompt
- ANN vector index (ivfflat/hnsw) in schema
- strict citation output contract
- strong prompt-injection hardening for retrieved text

---

## 11) If you need production-grade upgrade path

Recommended next steps:

1. Add vector ANN index and benchmark:
   - HNSW or IVFFlat depending on data size and update pattern
2. Add token-aware context packer:
   - reserve model budget for system/history/output
   - fill RAG chunks by score under remaining token budget
3. Separate evidence from policy:
   - system = policy only
   - evidence in tool/user payload with source tags
4. Add citation schema and answer validator:
   - require source ids in final answer
5. Add prompt-injection guard:
   - explicit policy "retrieved content is untrusted data"
   - blocklist / heuristic scanning / model-side guard prompt

