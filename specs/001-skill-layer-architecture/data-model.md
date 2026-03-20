# Data Model: Skill Layer Architecture

## 1. SkillDefinition

Represents a versioned skill specification.

### Core Fields

- `skillId`
- `version`
- `name`
- `skillType`
- `owner`
- `status`
- `capabilityTags`
- `triggerPolicy`
- `inputContract`
- `outputContract`
- `fallbackPolicy`
- `authScope`
- `quotaPolicy`

## 2. SkillRouteDecision

Represents the routing result for one request.

### Core Fields

- `requestId`
- `candidateSkills`
- `selectedSkillId`
- `selectionReason`
- `rejectedReasons`
- `confidence`
- `fallbackTriggered`

## 3. SkillExecutionRecord

Represents the execution trace of a selected skill.

### Core Fields

- `executionId`
- `requestId`
- `skillId`
- `startTime`
- `endTime`
- `status`
- `inputDigest`
- `outputDigest`
- `errorCode`
- `fallbackState`
- `auditMetadata`

## 4. SkillFallbackPolicy

Represents the allowed recovery strategy when a skill is unavailable or fails.

### Core Fields

- `policyType`
- `fallbackTarget`
- `safeFailureMessage`
- `retryAllowed`
- `operatorVisibility`
