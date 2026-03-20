# Feature Specification: Built-in Skill Routing

**Feature Branch**: `[002-builtin-skill-routing]`  
**Created**: 2026-03-19  
**Status**: Draft  
**Input**: User description: "Implement built-in skill routing so the system can distinguish built-in skills from ordinary chat handling and route requests through a dedicated skill path."

## Feature Classification *(mandatory)*

### Change Type

- **Primary Domain**: skill-layer
- **Feature Shape**: new feature
- **Risk Level**: high

### Skill Classification *(mandatory when skill-layer is involved)*

- **Skill Scope**: built-in skill
- **Trigger Model**: hybrid
- **Dispatch Ownership**: dedicated skill router
- **Fallback Strategy**: default assistant fallback

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Route Explicit Built-in Skill Calls (Priority: P1)

As a user, I want requests that explicitly target a built-in skill to be routed into that skill path instead of being treated like ordinary chat, so the system can execute capability-specific logic deterministically.

**Why this priority**: Explicit invocation is the safest and smallest MVP for skill routing. It gives deterministic control and proves the routing boundary works.

**Independent Test**: Send a request that explicitly targets a built-in skill and verify that the router selects that skill, records a route decision, executes the skill, and returns either skill output or documented fallback.

**Acceptance Scenarios**:

1. **Given** a request that explicitly names a built-in skill, **When** the request reaches the router, **Then** the router selects that built-in skill instead of the default assistant path.
2. **Given** a request that explicitly names a built-in skill that is disabled or unavailable, **When** the router evaluates it, **Then** the system records the rejection reason and falls back according to policy.

### User Story 2 - Distinguish Built-in Skill Routing From Ordinary Chat (Priority: P2)

As a developer or reviewer, I want the built-in skill path to be visibly separated from ordinary chat handling so that routing logic, skill execution, and fallback behavior remain inspectable and maintainable.

**Why this priority**: If built-in skill routing is mixed into ordinary chat flow without a clear boundary, future skill work will become hard to reason about and debug.

**Independent Test**: Inspect runtime trace data and verify that the request clearly shows whether it went through the built-in skill router or the normal assistant path.

**Acceptance Scenarios**:

1. **Given** a normal chat request without a built-in skill match, **When** the request is processed, **Then** the system continues through the ordinary assistant path and does not emit a false skill execution record.
2. **Given** a built-in skill match, **When** the request is processed, **Then** the system emits a route decision and execution trace that identify the built-in skill path.

### User Story 3 - Support Safe Fallback For Misses and Failures (Priority: P3)

As an operator, I want built-in skill routing to fail safely so that an unavailable skill, bad input, or execution error does not break the overall chat experience.

**Why this priority**: Routing without safe fallback creates brittle user experience and raises operational risk.

**Independent Test**: Force a no-match, invalid-input, and execution-failure case and verify that the system records the reason and falls back to normal assistant handling or safe refusal as specified.

**Acceptance Scenarios**:

1. **Given** a request that looks similar to a built-in skill but does not meet routing rules, **When** the router evaluates it, **Then** it rejects the match and preserves normal chat behavior.
2. **Given** a selected built-in skill that throws an execution error, **When** fallback policy is enabled, **Then** the system records the failure and returns fallback behavior instead of an unhandled error path.

## Edge Cases

- What happens when more than one built-in skill matches the same request?
- What happens when a built-in skill is selected but required inputs are missing?
- What happens when a built-in skill is selected but the current user lacks permission or quota?
- What happens when a built-in skill partially succeeds and fallback is still needed?

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The system MUST support a dedicated routing path for built-in skills.
- **FR-002**: The system MUST distinguish explicit built-in skill invocation from ordinary assistant handling.
- **FR-003**: The system MUST define deterministic selection rules when multiple built-in skills are candidates.
- **FR-004**: The system MUST record a route decision for built-in skill evaluation.
- **FR-005**: The system MUST define safe fallback behavior for no-match, invalid input, unauthorized invocation, and execution failure.
- **FR-006**: The system MUST preserve auth, quota, and audit expectations while executing built-in skills.

### Skill Contract Requirements *(mandatory when skill-layer is involved)*

- **FR-SKILL-001**: The system MUST classify the routed capability as a built-in skill.
- **FR-SKILL-002**: The system MUST define explicit and hybrid trigger conditions for built-in skill selection.
- **FR-SKILL-003**: The system MUST validate built-in skill input before execution.
- **FR-SKILL-004**: The system MUST record skill selection reason, execution result, and fallback state.
- **FR-SKILL-005**: The system MUST support default assistant fallback when no safe built-in skill execution is possible.

### Key Entities *(include if feature involves data)*

- **BuiltInSkillDefinition**: A versioned definition for a built-in skill, including trigger rules, auth scope, input contract, fallback policy, and implementation binding.
- **SkillRouteDecision**: A runtime record describing candidate skills, the selected built-in skill, or the reason no skill was selected.
- **SkillExecutionRecord**: A runtime trace of built-in skill execution, including status, error information, and fallback state.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: An explicitly targeted built-in skill request is routed into the built-in skill path instead of the default assistant path.
- **SC-002**: A non-skill request remains on the ordinary assistant path without false-positive skill execution.
- **SC-003**: Route decisions and execution traces make it clear why a built-in skill matched, failed, or fell back.
- **SC-004**: Failure cases degrade safely through documented fallback behavior rather than breaking the chat chain.
