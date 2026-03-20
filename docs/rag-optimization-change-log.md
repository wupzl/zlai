# RAG Optimization Change Log

This file records recent RAG-side changes so ingest, retrieval, and evaluation work stay traceable.

## 2026-03-14

### Async Uploads

- Added async markdown upload endpoint `/api/rag/ingest/markdown-upload/async`.
- Reused the same Redis-backed task status flow already used by async text/file ingest.
- Added shared markdown upload preparation logic in `RagController` so sync and async markdown upload use the same validation, image collection, remote image download, OCR quota check, and payload assembly path.
- Kept OCR quota consumption on successful completion only.

### Evaluation Workspace

- Added `test/rag-eval/normalize-study-resource.ps1` to copy source materials into a workspace-owned replica and normalize text files into UTF-8 without mutating the original source tree.
- Added `test/rag-eval/generate-rag-eval-set.ps1` to build a first-pass eval dataset from normalized markdown documents.
- Added `test/rag-eval/run-rag-eval.ps1` to run offline regression checks against `/api/rag/query` and emit per-sample and summary metrics.
- Added `test/rag-eval/README.md` usage notes for normalization, dataset generation, and local eval execution.

### Dataset Filtering

- Added file-level suspicious-text tagging into the normalization manifest.
- Added sample-level dirty-data filtering when generating eval cases.
- Generated a replica workspace from `D:\StudyResources\JavaStudyResource` under `test/rag-eval/workspace-java-study-resource`.
- Generated eval artifacts under `test/rag-eval/output-java-study-resource`.

### Current Notes

- PowerShell console rendering may still display UTF-8 Chinese as mojibake in direct `Get-Content` output, but UTF-8 parsing of generated `jsonl` samples was verified separately.
- Sync and async ingest endpoints currently coexist:
  - Sync: `/ingest`, `/ingest/markdown`, `/ingest/markdown-upload`, `/ingest/file-upload`
  - Async: `/ingest/async`, `/ingest/file-upload/async`, `/ingest/markdown-upload/async`

### Files Changed In This Entry

- `backend/src/main/java/com/harmony/backend/ai/rag/controller/RagController.java`
- `test/rag-eval/normalize-study-resource.ps1`
- `test/rag-eval/generate-rag-eval-set.ps1`
- `test/rag-eval/run-rag-eval.ps1`
- `test/rag-eval/README.md`
- `docs/rag-optimization-change-log.md`

## 2026-03-14 (Frontend Async Upload Switch)

### Summary

- Switched the main RAG upload buttons in the frontend to async task-based endpoints.
- Marked sync `markdown-upload` and sync `file-upload` controller endpoints as deprecated, but kept them for compatibility.

### Implemented Changes

- Updated `frontend/src/views/RagView.vue` so markdown upload now calls `/api/rag/ingest/markdown-upload/async`.
- Updated `frontend/src/views/RagView.vue` so file upload now calls `/api/rag/ingest/file-upload/async`.
- Added task polling in `RagView.vue` against `/api/rag/ingest/tasks/{taskId}` and mapped task progress/messages into the page status area.
- Disabled upload buttons while a task is active to avoid duplicate submissions from the same page state.
- Marked sync upload endpoints in `RagController` with `@Deprecated`.

### Files Changed In This Entry

- `frontend/src/views/RagView.vue`
- `backend/src/main/java/com/harmony/backend/ai/rag/controller/RagController.java`
- `docs/rag-optimization-change-log.md`

## 2026-03-14 (Upload Idempotency And Sync Removal)

### Summary

- Removed sync markdown/file upload controller endpoints so upload flows only go through async tasks.
- Added request-level idempotency for async markdown/file uploads using Redis-backed upload fingerprints.

### Implemented Changes

- Deleted sync `/api/rag/ingest/markdown-upload` and sync `/api/rag/ingest/file-upload` controller methods.
- Added SHA-256 upload fingerprint generation for markdown and file async uploads in `RagController`.
- Reused existing task status storage and added Redis idempotency keys so repeated same-content uploads return the existing task instead of starting duplicate work.
- Failed tasks now clear the idempotency key so user retries can start a fresh task.

### Verification

- `mvn -q -DskipTests=true compile` in `backend/` passed after upload idempotency changes.
- `npm run build` in `frontend/` passed after switching UI upload buttons to async endpoints.

### Files Changed In This Entry

- `backend/src/main/java/com/harmony/backend/ai/rag/controller/RagController.java`
- `frontend/src/views/RagView.vue`
- `docs/rag-optimization-change-log.md`

## 2026-03-15 (RAG Datasource Config Fix)

### Summary

- Fixed a mis-indented `application.yaml` section that placed RAG datasource and embedding settings under `app.network` instead of `app.rag`.
- This prevented `ragJdbcTemplate` from being created and forced the application onto `NoopRagService`, which surfaced as `RAG datasource not configured`.

### Implemented Changes

- Moved `vector-size`, `ocr`, `default-top-k`, `chunk-*`, `search`, `datasource`, and `embedding` back under `app.rag`.
- Kept `app.network.client-ip` as a separate sibling section under `app`.

### Files Changed In This Entry

- `backend/src/main/resources/application.yaml`
- `docs/rag-optimization-change-log.md`
