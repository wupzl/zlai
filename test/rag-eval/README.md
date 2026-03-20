# RAG Eval Workspace

这个目录用于安全地准备 RAG 评测资料，不会直接修改原始学习资料目录。

流程分两步：

1. 先复制并归一化资料目录

```powershell
powershell -ExecutionPolicy Bypass -File .\test\rag-eval\normalize-study-resource.ps1 `
  -SourceDir 'D:\StudyResources\JavaStudyResource' `
  -WorkspaceDir '.\test\rag-eval\workspace'
```

输出：

- `workspace\copied`：原始资料副本
- `workspace\normalized`：转成 UTF-8 的归一化副本
- `workspace\manifest.json`：迁移和编码探测结果

2. 再从归一化副本生成评测集草案

```powershell
powershell -ExecutionPolicy Bypass -File .\test\rag-eval\generate-rag-eval-set.ps1 `
  -NormalizedDir '.\test\rag-eval\workspace\normalized' `
  -OutputDir '.\test\rag-eval\output'
```

输出：

- `output\rag-eval.jsonl`：评测样本
- `output\rag-eval-summary.json`：统计信息

当前生成的是第一版启发式评测集，样本分三类：

- `document_summary`：按文件名总结整篇文档
- `file_name_summary`：显式提文件名做整篇总结
- `section_summary`：按章节提问

这个评测集更适合先验证两类能力：

- 文件级召回是否能命中文档整体
- 章节覆盖是否比单纯 chunk 拼接更完整

3. Run local RAG eval

```powershell
powershell -ExecutionPolicy Bypass -File .\test\rag-eval\run-rag-eval.ps1 `
  -BaseUrl 'http://127.0.0.1:8080' `
  -EvalFile '.\test\rag-eval\output-java-study-resource\rag-eval.jsonl' `
  -OutputDir '.\test\rag-eval\results' `
  -BearerToken '<your-jwt>'
```
