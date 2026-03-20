# Feature Specification: [FEATURE NAME]

**Feature Branch**: `[###-feature-name]`  
**Created**: [DATE]  
**Status**: Draft  
**Input**: User description: "$ARGUMENTS"

## Feature Classification *(mandatory)*

### Change Type

- **Primary Domain**: [core-product / skill-layer / rag / agent / admin / frontend / mixed]
- **Feature Shape**: [new feature / refactor / migration / governance / observability]
- **Risk Level**: [low / medium / high]

### Skill Classification *(mandatory when skill-layer is involved)*

- **Skill Scope**: [N/A / built-in skill / user-defined skill / workflow skill / tool-wrapper skill / mixed]
- **Trigger Model**: [N/A / explicit invocation / rule-based / intent-based / hybrid]
- **Dispatch Ownership**: [N/A / chat / agent / dedicated skill router]
- **Fallback Strategy**: [N/A / none / default assistant fallback / alternate skill fallback / safe refusal]

## User Scenarios & Testing *(mandatory)*

### User Story 1 - [Brief Title] (Priority: P1)

[Describe the highest-value user journey in plain language]

**Why this priority**: [Explain why this is the first slice that must work]

**Independent Test**: [Describe how this story can be tested on its own]

**Acceptance Scenarios**:

1. **Given** [initial state], **When** [action], **Then** [expected outcome]
2. **Given** [initial state], **When** [action], **Then** [expected outcome]

---

### User Story 2 - [Brief Title] (Priority: P2)

[Describe the second user journey]

**Why this priority**: [Explain the value]

**Independent Test**: [Describe how this can be tested independently]

**Acceptance Scenarios**:

1. **Given** [initial state], **When** [action], **Then** [expected outcome]

---

### User Story 3 - [Brief Title] (Priority: P3)

[Describe the third user journey]

**Why this priority**: [Explain the value]

**Independent Test**: [Describe how this can be tested independently]

**Acceptance Scenarios**:

1. **Given** [initial state], **When** [action], **Then** [expected outcome]

## Edge Cases

- What happens when a trigger matches multiple skills?
- What happens when the selected skill is unauthorized, unavailable, or misconfigured?
- What happens when the skill returns partial output or side effects fail?
- What happens when the system should fall back to normal assistant behavior?

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST support the primary user journey described in User Story 1.
- **FR-002**: System MUST define the affected module boundary and runtime ownership for this feature.
- **FR-003**: System MUST expose enough runtime information to debug failures.
- **FR-004**: System MUST define failure handling and fallback behavior for the changed path.
- **FR-005**: System MUST preserve auth, quota, and audit expectations for the changed path.

### Skill Contract Requirements *(mandatory when skill-layer is involved)*

- **FR-SKILL-001**: System MUST classify the skill type and trigger mode.
- **FR-SKILL-002**: System MUST validate skill input before execution.
- **FR-SKILL-003**: System MUST define the expected output shape or post-execution state.
- **FR-SKILL-004**: System MUST record skill selection and execution trace.
- **FR-SKILL-005**: System MUST define fallback behavior when the skill is not selected or fails.

### Key Entities *(include if feature involves data)*

- **[Entity 1]**: [Meaning, major fields, relationship summary]
- **[Entity 2]**: [Meaning, major fields, relationship summary]

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: [A measurable user-visible or operator-visible result]
- **SC-002**: [A measurable reliability, latency, or correctness outcome]
- **SC-003**: [A measurable observability or governance outcome]
- **SC-004**: [A measurable rollout or maintenance outcome]
