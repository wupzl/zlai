# RAG Eval Workspace

This directory contains two layers:

1. Simple script-first retrieval comparison flow
2. Optional stricter benchmark scaffolding for later automation

## Recommended Starting Point

Under the current charter, start with the raw-data smoke benchmark first:

- Charter: [docs/rag-retrieval-benchmark-charter.md](/D:/Code/zlAI-v2/docs/rag-retrieval-benchmark-charter.md)
- Raw dataset README: [test/rag-eval/datasets/raw/README.md](/D:/Code/zlAI-v2/test/rag-eval/datasets/raw/README.md)
- Raw smoke corpus: [test/rag-eval/datasets/raw/corpus_manifest.raw.smoke.jsonl](/D:/Code/zlAI-v2/test/rag-eval/datasets/raw/corpus_manifest.raw.smoke.jsonl)
- Raw smoke questions: [test/rag-eval/datasets/raw/questions.raw.smoke.jsonl](/D:/Code/zlAI-v2/test/rag-eval/datasets/raw/questions.raw.smoke.jsonl)
- Raw smoke qrels: [test/rag-eval/datasets/raw/qrels.raw.smoke.jsonl](/D:/Code/zlAI-v2/test/rag-eval/datasets/raw/qrels.raw.smoke.jsonl)

The older simple flow and normalized datasets are still useful, but now they should be treated as diagnostic support rather than the primary benchmark conclusion.

- Simple flow: [test/rag-eval/simple-flow.md](/D:/Code/zlAI-v2/test/rag-eval/simple-flow.md)
- Simple `zlAI` script: [test/rag-eval/run-zlai-retrieval-benchmark.ps1](/D:/Code/zlAI-v2/test/rag-eval/run-zlai-retrieval-benchmark.ps1)
- Dataset schema: [test/rag-eval/datasets/README.md](/D:/Code/zlAI-v2/test/rag-eval/datasets/README.md)
- Runner contract: [test/rag-eval/runner-contract.md](/D:/Code/zlAI-v2/test/rag-eval/runner-contract.md)

## Optional Advanced Docs

These are useful later if you want a stricter local benchmark flow:

- zlAI runner design: [test/rag-eval/zlai-runner-design.md](/D:/Code/zlAI-v2/test/rag-eval/zlai-runner-design.md)
- Config templates:
  - [test/rag-eval/configs/aligned.template.yaml](/D:/Code/zlAI-v2/test/rag-eval/configs/aligned.template.yaml)
  - [test/rag-eval/configs/system-track.template.yaml](/D:/Code/zlAI-v2/test/rag-eval/configs/system-track.template.yaml)
  - [test/rag-eval/configs/retriever-track.template.yaml](/D:/Code/zlAI-v2/test/rag-eval/configs/retriever-track.template.yaml)
- Analysis guide: [test/rag-eval/analysis/README.md](/D:/Code/zlAI-v2/test/rag-eval/analysis/README.md)

## Existing Older Local Regression Scripts

This repository also contains the earlier local RAG regression scripts:

- `normalize-study-resource.ps1`
- `generate-rag-eval-set.ps1`
- `run-rag-eval.ps1`

Those are still useful for older interface-level regression, but the new simple retrieval script is the easier starting point for `zlAI` vs `LlamaIndex` comparison.
