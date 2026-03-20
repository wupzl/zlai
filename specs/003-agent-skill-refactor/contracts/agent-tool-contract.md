# Agent Tool Contract (Refactor Target)

## Goal

Define the standard runtime capability contract for executable tools while preserving the higher-level skill orchestration layer.

## Contract Shape

### Metadata

- `key`
- `name`
- `description`
- `parametersSchema`

### Adopted Compatibility Shape (T011)

The first implementation step does **not** collapse metadata and execution into one mandatory interface for every tool bean.

- `AgentTool` remains the standard metadata contract and now guarantees `parametersSchema`
- `ExecutableAgentTool` is introduced as the compatible execution sub-interface
- existing `ToolHandler` and `ToolExecutor` remain the active runtime execution path for now

This keeps the module boundary intact:
- metadata discovery stays in `AgentTool`
- runtime execution can converge gradually through `ExecutableAgentTool`
- skill routing and follow-up remain outside the tool contract

### Execution Input

- `toolKey`
- `rawInput` or structured args
- `modelOverride`
- `contextMetadata` (optional, if introduced later)

### Execution Output

- `success`
- `output`
- `error`
- `model`
- `promptTokens`
- `completionTokens`
- `usedTools`

## Compatibility Notes

- Existing `AgentToolRegistry` now indexes metadata for all tools and executable lookups for executable-capable tools.
- Existing `SkillExecutor` now prefers executable tools and falls back to `ToolExecutor`.
- `ToolExecutionResult` and `SkillExecutionResult` now satisfy a shared runtime result view.

## Migration Rule

Do not remove the current skill planner / executor path until the new contract is fully adapted and regression-tested.

## Current Status

- `T011` completed the contract-first step by:
  - adding `parametersSchema` to `AgentTool`
  - adding `ExecutableAgentTool` as the execution-oriented compatibility contract
- `T012` updated `AgentToolRegistry` to:
  - preserve metadata lookup for all tools
  - carry `parametersSchema` and `executable` metadata through `AgentToolDefinition`
  - expose executable-capable tool lookup separately from metadata lookup
- `T013` adapted built-in tools to implement `ExecutableAgentTool` while delegating execution to existing handlers.
- `T014` introduced `AgentExecutionResultView` so tool-backed and skill-backed execution expose a compatible runtime shape.
- `T016` updated `DefaultSkillExecutor` to consume executable tools internally while preserving `ToolExecutor` fallback.
- runtime execution still keeps `ToolExecutor` and `ToolHandler` as the general fallback path; no controller or follow-up logic was collapsed into the tool contract.

## T019 Safety Conclusion

- The migration now has passing targeted regression coverage, but it is still not safe to delete ToolHandler / ToolExecutor or collapse AgentToolDefinition into ExecutableAgentTool.
- Those structures remain the compatibility layer that protects mainline behavior, fallback semantics, and controller-facing metadata contracts.
- Only non-behavioral duplication has been removed in this step.

