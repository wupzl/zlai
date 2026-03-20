# Data Model: Built-in Skill Routing

## 1. BuiltInSkillDefinition

Represents a built-in skill known to the platform.

### Core Fields

- `skillId`
- `name`
- `version`
- `triggerPolicy`
- `priority`
- `authScope`
- `quotaPolicy`
- `inputContract`
- `outputContract`
- `fallbackPolicy`
- `enabled`

## 2. SkillRouteDecision

Represents the routing decision for a request.

### Core Fields

- `requestId`
- `candidateSkills`
- `selectedSkillId`
- `selectionReason`
- `rejectedReasons`
- `fallbackRequired`
- `decisionTimestamp`

## 3. SkillExecutionRecord

Represents the execution trace for the selected built-in skill.

### Core Fields

- `executionId`
- `requestId`
- `skillId`
- `status`
- `startTime`
- `endTime`
- `errorCode`
- `fallbackState`
- `traceSummary`

## Relationships

- One `BuiltInSkillDefinition` can produce many `SkillExecutionRecord` items.
- One request can produce one `SkillRouteDecision` and zero or one `SkillExecutionRecord`.
