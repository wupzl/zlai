# Research: Built-in Skill Routing

## Question

How should the first iteration of built-in skill routing distinguish built-in skills from ordinary chat behavior while keeping the system safe and inspectable?

## Option A: Inline Routing Logic Inside Existing Chat Flow

### Pros

- Lowest short-term implementation cost
- Minimal new files or abstractions

### Cons

- Skill selection reasons become hidden in ordinary chat logic
- Harder to audit why a skill matched or did not match
- Harder to extend later into user-defined or workflow skills
- Fallback behavior becomes entangled with ordinary response generation

## Option B: Dedicated Built-in Skill Router + Executor + Fallback Path

### Pros

- Clear boundary between ordinary chat and skill routing
- Easier to log route decisions and execution traces
- Easier to grow into additional skill categories later
- Safer failure handling because fallback is a first-class part of the design

### Cons

- Slightly more upfront structure
- More initial documentation and implementation work

## Decision

Choose Option B.

The first iteration should still bias toward explicit invocation and deterministic routing. Hybrid matching can exist, but explicit built-in skill calls should remain the most trusted signal.

## Design Bias

- Prefer explicit invocation over fuzzy matching
- Reject ambiguous multi-skill matches unless a deterministic priority rule exists
- Treat fallback as part of the feature, not an afterthought
