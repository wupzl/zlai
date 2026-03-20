# Research: Refactor Skill Layer To Standard AgentTools

## Question

How should the repository refactor the existing skill layer toward a standard AgentTools-style contract without breaking the current mainline chat and agent flow?

## Existing Reality In This Repository

The current codebase already has a layered model:

- `ai.tool.AgentTool`: tool metadata contract
- `ai.tool.AgentToolRegistry`: built-in tool registry
- `ai.skill.AgentSkillDefinition`: orchestration-layer skill definition
- `ai.skill.SkillPlanner`: skill routing/planning
- `ai.skill.SkillExecutor`: skill execution bridge
- `ToolFollowupService` and `MultiAgentOrchestrator`: mainline integration points

So the real problem is not “there is no skill layer”, but “tool contract and skill execution contract are not yet standardized enough”.

## Option A: Replace Skill Layer With One Flat Interface

Example shape:

```java
public interface AgentSkill {
    String getName();
    String getDescription();
    Map<String, Object> getParametersSchema();
    Result<String> execute(Map<String, Object> params);
}
```

### Pros

- Looks simple
- Easy to explain in isolation

### Cons

- Does not match the current repository layout
- Collapses routing, orchestration, and execution into one abstraction
- Harder to preserve existing managed DB skill definitions
- Increases migration risk on mainline chat path

## Option B: Standardize Tool Contract, Preserve Skill Orchestration Layer

### Pros

- Fits the current repository shape
- Preserves `SkillPlanner` and `SkillExecutor` responsibilities
- Lets built-in tools and skill-backed execution share a more standard runtime contract
- Lower migration risk

### Cons

- Slightly more complex than a single flat interface
- Requires a staged refactor

## Decision

Choose Option B.

The refactor should standardize executable capability contracts, not erase the distinction between tools and skills.

## Design Bias

- Keep mainline safety first
- Introduce new contracts before deleting old ones
- Preserve skill-level routing and fallback semantics
- Let execution results converge before flattening higher-level concepts
