# Skill Contract

## Purpose

This file defines the minimum contract shape for a skill-layer feature.

## 1. Skill Identity

- `skillId`:
- `skillName`:
- `skillType`:
- `version`:

## 2. Trigger Contract

- `triggerMode`: explicit / rule-based / intent-based / hybrid
- `dispatchOwner`:
- `matchInputs`:
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
- `postExecutionState`:
- `operatorVisibleTrace`:

## 5. Error Contract

- `recoverableErrors`:
- `nonRecoverableErrors`:
- `userFacingFailureMode`:
- `adminFacingFailureMode`:

## 6. Fallback Contract

- `fallbackEnabled`:
- `fallbackTarget`:
- `fallbackTriggerConditions`:
- `safeResponseIfFallbackFails`:
