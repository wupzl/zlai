# Data Model: Refactor Skill Layer To Standard AgentTools

## 1. AgentToolContract

Represents the standard capability contract after refactor.

### Core Fields / Methods

- `key`
- `name`
- `description`
- `parametersSchema`

### Runtime Variants

- `AgentTool`: metadata contract used by registry and prompt-building layers
- `ExecutableAgentTool`: execution-oriented sub-interface used for gradual runtime migration

## 2. AgentToolDefinition

Represents operator-visible tool metadata served by `AgentToolRegistry`.

### Core Fields

- `key`
- `name`
- `description`
- `parametersSchema`
- `executable`

## 3. AgentSkillDefinition

Represents orchestration-level skill configuration.

### Core Fields

- `key`
- `name`
- `description`
- `toolKeys`
- `executionMode`
- `inputSchema`
- `stepConfig`

## 4. SkillRouteDecision

Represents the selected skill and why it was selected.

### Core Fields

- `requestedSkillKey`
- `selectedSkillKey`
- `normalizedInput`
- `reason`
- `candidates`

## 5. AgentExecutionResultView

Represents the common runtime result view shared by tool-backed and skill-backed execution.

### Core Fields

- `success`
- `output`
- `error`
- `model`
- `promptTokens`
- `completionTokens`
- `usedTools`

### Current Implementations

- `ToolExecutionResult` implements `AgentExecutionResultView`
- `SkillExecutionResult` implements `AgentExecutionResultView`

## 6. SkillExecutionRecord

Represents operator-visible execution trace data consumed by follow-up services.

### Core Fields

- `skillKey`
- `usedTools`
- `success`
- `output`
- `error`
- `model`
- `promptTokens`
- `completionTokens`
- `fallbackState`

## Relationships

- `AgentSkillDefinition` references one or more `AgentToolContract` instances.
- `AgentToolRegistry` exposes metadata lookup for all tools and executable lookup for tools that implement `ExecutableAgentTool`.
- `SkillPlanner` produces `SkillRouteDecision`.
- `DefaultSkillExecutor` now prefers `ExecutableAgentTool` and falls back to `ToolExecutor` when no executable tool is registered.
- Mainline follow-up services consume `AgentExecutionResultView`-shaped outcomes through existing `ToolExecutionResult` and `SkillExecutionResult` types.
