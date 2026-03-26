# Pressure Report

## Scope

This report is the runtime-hardening verification note for the current k6 scripts. It is not a blanket claim that the system is production-ready under all real-model traffic.

## Environment Assumptions

- Backend: `http://localhost:8080`
- Data stores: MySQL + Redis + PostgreSQL
- Test users: `test01`, `test02`, `test03`
- Default model in pressure scripts: `deepseek-chat`
- Unless explicitly stated otherwise, older baseline runs used mock or low-variance model behavior and should not be treated as proof for real external-model latency tolerance.

## Current Verification Targets

### `k6-load.js`

Validate the core chat chain under bounded concurrent load:

- session lookup and creation
- sync chat send path
- message persistence
- optional stream path
- duplicate request replay or in-flight conflict behavior
- long-session repeated turns on the same chat

Pass criteria:

- no unexpected `5xx`
- no empty success payloads
- no missing persisted user messages
- duplicate replay remains bounded to acceptable outcomes: `200` replay or `409` in-flight rejection

### `k6-mix.js`

Validate mixed traffic under bounded concurrent load:

- chat sync and optional stream
- GPT Store browsing
- agent listing
- RAG query
- async RAG ingest contention

Pass criteria:

- no unexpected `5xx`
- no chat persistence regressions
- async RAG ingest either succeeds with `200` or degrades explicitly with `503`

## Important Caveats

- Real provider latency, rate limits, and streaming behavior may be significantly worse than local or mock baselines.
- These scripts validate bounded degradation and common runtime regressions, not absolute throughput limits.
- Real production readiness still requires runs with real model latency, realistic document sizes, and representative user concurrency.

## Recommended Execution

```bash
k6 run test/k6-load.js
k6 run test/k6-mix.js
```

Optional environment overrides:

- `BASE_URL`
- `MODEL`
- `STREAM=true|false`
- `DUPLICATE_REPLAY=true|false`
- `LONG_SESSION_TURNS=<n>`
- `ASYNC_INGEST=true|false`

## Result Recording Template

Record each run with:

- date and environment
- whether mock or real model endpoints were used
- VU/stage settings
- 429 count
- 5xx count
- duplicate replay bounded result
- persistence failures
- async ingest busy vs accepted counts
- final pass/fail decision

## Interpretation Rule

If a run only proves correctness under mock or low-latency dependencies, label it as:

`runtime-regression pass under non-production dependency profile`

Do not label it as full high-concurrency production proof.

## Latest Recorded Runs

### 2026-03-21 Scenario A: Real LLM, low concurrency, non-stream

Command:

```bash
k6 run --stage 10s:3 --stage 20s:6 --stage 20s:10 --stage 10s:0 -e STREAM=false -e MODEL=deepseek-chat test/k6-load.js
```

Observed result:

- `5xx = 0`
- `429 = 0`
- `message_success = 45`
- `duplicate_replay_bounded_ok = 15`
- `message_persist_fail = 0`
- functional result: PASS
- performance threshold result: FAIL

Interpretation:

- The sync chat chain, persistence, and duplicate request handling behaved correctly under low real-model concurrency.
- The k6 latency threshold was crossed, so this is not a performance pass.

### 2026-03-21 Scenario B: Mock LLM, higher concurrency, non-stream

Command:

```bash
k6 run --stage 15s:10 --stage 30s:40 --stage 30s:60 --stage 15s:0 -e STREAM=false -e DUPLICATE_REPLAY=true -e LONG_SESSION_TURNS=4 -e MODEL=deepseek-chat test/k6-load.js
```

Observed result:

- `5xx = 0`
- `429 = 17924`
- `message_success = 228`
- `message_fail = 10616`
- `message_persist_fail = 322`
- `duplicate_replay_bounded_ok = 62`
- `duplicate_replay_fail = 2649`
- overall result: FAIL

Interpretation:

- The service degraded by rejecting load rather than crashing, which is better than overload collapse.
- The current rate-limit envelope is the dominant limiter under this pressure profile.
- This run does not qualify as a high-concurrency pass; it shows bounded rejection, not sustained throughput success.
