# Built-in Skill Contract

## 1. Skill Identity

- `skillId`:
- `skillName`:
- `version`:
- `enabled`:

## 2. Trigger Contract

- `triggerMode`: explicit / hybrid
- `explicitInvocationFormat`:
- `hybridMatchSignals`:
- `priorityRule`:
- `conflictResolution`:

## 3. Input Contract

- `requiredFields`:
- `optionalFields`:
- `contextDependencies`:
- `authorizationRequirements`:
- `quotaRequirements`:

## 4. Output Contract

- `successShape`:
- `sideEffects`:
- `traceFields`:

## 5. Error Contract

- `invalidInputBehavior`:
- `unauthorizedBehavior`:
- `executionFailureBehavior`:

## 6. Fallback Contract

- `fallbackEnabled`:
- `fallbackTarget`: default assistant / safe refusal / alternate built-in skill
- `fallbackTriggerConditions`:
- `operatorVisibleDiagnostics`:
