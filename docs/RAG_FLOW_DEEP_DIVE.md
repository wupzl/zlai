# RAG Full Call Chain (Code-Accurate)

This document answers the RAG pipeline questions strictly based on current code.

## 0. End-to-end call chain (user query -> final answer)

1. User sends chat message -> `ChatServiceImpl.chat(...)`.
2. If RAG is enabled, `resolveRagContext(...)` is called.
3. `resolveRagContext(...)` calls `ragService.buildContext(userId, query, ragTopK)`.
4. `RagServiceImpl.buildContext(...)`:
   - calls `search(...)` (vector/MMR path),
   - applies keyword checks/fallback if needed,
   - returns final context text.
5. `ChatServiceImpl.mergeSystemPrompt(basePrompt, ragContext)` injects RAG context into system prompt text.
6. `buildContextMessages(...)` composes `system + history + current user`, then `trimContext(...)` keeps last N messages.
7. Adapter sends prompt to LLM and streams/finalizes response.

---

## 1) Where does chunking happen?

Chunking happens during ingest, before retrieval:

- File: `backend/src/main/java/com/harmony/backend/ai/rag/service/impl/RagServiceImpl.java`
- Method: `ingest(...)`
- Call: `splitContent(content, properties.getChunkSize(), properties.getChunkOverlap())`

So the sequence is:

- create document row
- split document to chunks
- embed each chunk
- insert chunk vectors

It is not done by Spring AI internals; this chunking logic is handwritten in this project (`splitContent` sliding-window).

---

## 2) When is embedding done?

Embedding happens at two stages:

1. Ingest time (document side):
   - `RagServiceImpl.ingest(...)`
   - `embeddingService.embed(chunk)` for every chunk
   - insert into `rag_chunk.embedding`

2. Query time (question side):
   - `RagServiceImpl.search(...)`
   - `embeddingService.embed(query)` once per query
   - used for vector similarity SQL

Spring AI only provides the embedding model call (`EmbeddingModel.embed` through `SpringAiEmbeddingService`), not the chunking pipeline.

---

## 3) SQL shape and `<->` operator details

Core vector retrieval SQL:

```sql
SELECT doc_id, content, (embedding <-> ?) AS distance
FROM rag_chunk
WHERE user_id = ?
ORDER BY embedding <-> ?
LIMIT ?
```

Candidate SQL for MMR:

```sql
SELECT doc_id, content, embedding, (embedding <-> ?) AS distance
FROM rag_chunk
WHERE user_id = ?
ORDER BY embedding <-> ?
LIMIT ?
```

`<->` comes from pgvector distance operators.

Important nuance:

- In pgvector, `<->` is Euclidean (L2) distance for standard vector ops.
- This code converts distance to `querySim = 1 / (1 + distance)` and uses that as relevance score.

---

## 4) Indexing status (ivfflat / hnsw / lists)

Current schema file:

- `backend/db/zlai_pg.sql`

Current indexes:

- `idx_rag_chunk_user` on `user_id`
- `idx_rag_chunk_doc` on `doc_id`

There is currently no vector ANN index declaration in repo SQL:

- no `USING ivfflat`
- no `USING hnsw`
- no `lists` parameter

Therefore, yes: vector ordering by `embedding <-> ?` can degrade toward scan-heavy plans as data grows (even with `user_id` filter helping prune by tenant).

---

## 5) Where is MMR executed?

MMR is done in application memory, not in SQL:

- File: `RagServiceImpl.java`
- Method: `mmrSearch(...)`

Flow:

1. Get candidate set from DB (`searchCandidates`) ordered by vector distance.
2. Iteratively select best candidate by MMR objective:
   - `query-doc`: from distance mapped to `querySim = 1/(1+distance)`
   - `doc-doc`: cosine similarity computed in Java (`cosineSimilarity(float[], float[])`)
   - `mmrScore = lambda * querySim - (1 - lambda) * maxSimToSelected`
3. Select top K items.

Candidate sizing:

- `candidateLimit = topK * mmrCandidateMultiplier`
- defaults from config:
  - `topK = 5`
  - `mmrCandidateMultiplier = 4`
- so default is retrieve 20 candidates, then MMR select 5.

Yes, doc-doc is computed as runtime cosine over vectors in memory.

---

## 6) chunkSize unit: chars or tokens?

It is character-length based substring split, not token-based.

- `chunkSize` and `chunkOverlap` are used as Java `String` character window.
- Current default config: `chunk-size: 800`, `chunk-overlap: 100`.

Consequence:

- Chinese/English token density differs.
- So "800 chars" does not mean stable token count across languages.

Current code does not do token-aware chunk sizing.

---

## 7) Final prompt assembly details

RAG context is injected as plain text into system prompt:

- `mergeSystemPrompt(basePrompt, ragContext)`
- prefix template:
  - `Knowledge base context:\n{ragContext}`
  - plus instruction to answer using KB context

Then `buildContextMessages(...)` builds:

- `[system(with merged prompt), chain history (non-system), current user prompt]`

Then `trimContext(...)` applies message-window trimming.

---

## 8) Token limit and truncation strategy (what exists vs missing)

What exists now:

1. Message-count based trimming:
   - `trimContext(...)` keeps last `window-messages` messages.
   - This is by message count, not by token budget.

2. RAG context char cap:
   - `buildContextFromMatches(...)` limits total context length by chars:
   - `maxChars = max(2000, chunkSize * 6)`
   - deduplicates repeated chunks and truncates by remaining chars.

3. Completion token cap:
   - `resolveMaxCompletionTokens(...)` controls output-side limit/cost.

What is not implemented now:

1. No strict prompt-side token budgeting for final merged prompt.
2. No model-context-window token guard for multilingual token variance.
3. No "if 5 chunks exceed token budget then token-aware adaptive cut" logic.

So currently it is mostly:

- window by message count,
- context truncation by characters,
- not exact token-aware truncation.

---

## 9) Direct answers to your exact questions

1. Chunking before or after embedding?
- Before embedding.

2. Chunking self-implemented or Spring AI internal?
- Self-implemented in `RagServiceImpl.splitContent(...)`.
- Spring AI only handles embedding call.

3. `chunkSize=800` char or token?
- Char-based.

4. Chinese vs English token difference handled?
- Not precisely. Current logic is char-based, not token-based.

5. `<->` is cosine or L2?
- In current SQL operator usage, treated as vector distance and mapped to score; pgvector `<->` corresponds to L2 distance semantics in this path.

6. Vector index created? ivfflat/hnsw/lists?
- No vector ANN index found in current schema.
- No ivfflat/hnsw/lists configuration present.

7. Without vector index is it scan-heavy?
- Yes, as corpus grows this query can become scan-heavy.

8. MMR in DB or memory?
- Memory (Java), after candidate fetch.

9. MMR doc-doc similarity how computed?
- Runtime cosine similarity over candidate embeddings.

10. topK=5 then MMR or topK=50 then 5?
- Candidate set first (`topK * multiplier`, default 5*4=20), then MMR select final topK (5).

11. Prompt concatenation direct text?
- Yes, direct text merge into system prompt prefix.

12. Token limit control?
- Partial: message-window + char truncation + completion cap.
- No strict prompt-token hard cap.

13. If 5 chunks exceed token budget?
- Current behavior relies on char truncation and message-window trimming; no exact token-aware chunk budget allocator.

